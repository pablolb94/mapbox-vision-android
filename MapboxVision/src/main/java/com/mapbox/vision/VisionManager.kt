package com.mapbox.vision

import android.app.Application
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.vision.location.LocationEngine
import com.mapbox.vision.location.android.AndroidLocationEngineImpl
import com.mapbox.vision.mobile.core.NativeVisionManager
import com.mapbox.vision.mobile.core.interfaces.VisionEventsListener
import com.mapbox.vision.mobile.core.models.*
import com.mapbox.vision.mobile.core.models.detection.FrameDetections
import com.mapbox.vision.mobile.core.models.frame.ImageFormat
import com.mapbox.vision.mobile.core.models.frame.ImageSize
import com.mapbox.vision.mobile.core.models.frame.PixelCoordinate
import com.mapbox.vision.mobile.core.models.position.GeoCoordinate
import com.mapbox.vision.mobile.core.models.world.WorldCoordinate
import com.mapbox.vision.performance.ModelPerformanceConfig
import com.mapbox.vision.performance.PerformanceManager
import com.mapbox.vision.sensors.SensorDataListener
import com.mapbox.vision.sensors.SensorsManager
import com.mapbox.vision.telemetry.MapboxTelemetryEventManager
import com.mapbox.vision.telemetry.TelemetryImageSaver
import com.mapbox.vision.telemetry.TelemetrySessionManager
import com.mapbox.vision.telemetry.TelemetrySyncManager
import com.mapbox.vision.utils.FileUtils
import com.mapbox.vision.utils.VisionLogger
import com.mapbox.vision.video.videoprocessor.VideoProcessor
import com.mapbox.vision.video.videoprocessor.VideoProcessorListener
import com.mapbox.vision.video.videosource.VideoSource
import com.mapbox.vision.video.videosource.VideoSourceListener
import com.mapbox.vision.video.videosource.camera.Camera2VideoSourceImpl
import com.mapbox.vision.video.videosource.camera.SurfaceVideoRecorder
import com.mapbox.vision.video.videosource.camera.VideoRecorder
import java.io.File

object VisionManager {

    private const val MAPBOX_VISION_IDENTIFIER = "MapboxVision"
    private const val MAPBOX_TELEMETRY_USER_AGENT = "$MAPBOX_VISION_IDENTIFIER/${BuildConfig.VERSION_NAME}"
    private const val TAG = "VisionManager"

    private const val DIR_TELEMETRY = "Telemetry"

    private lateinit var application: Application
    private lateinit var mapboxToken: String

    private lateinit var nativeVisionManager: NativeVisionManager

    private lateinit var mapboxTelemetry: MapboxTelemetry
    private lateinit var videoSource: VideoSource
    private lateinit var sensorsManager: SensorsManager
    private lateinit var locationEngine: LocationEngine
    private lateinit var videoProcessor: VideoProcessor
    private lateinit var telemetrySyncManager: TelemetrySyncManager
    private lateinit var telemetryImageSaver: TelemetryImageSaver
    private lateinit var performanceManager: PerformanceManager
    private lateinit var visionEventsListener: VisionEventsListener

    private var sessionManager: TelemetrySessionManager? = null
    private var videoRecorder: VideoRecorder? = null
    private var externalVideoSourceListener: VideoSourceListener? = null

    private lateinit var rootTelemetryDir: String

    @Volatile
    private var isStarted = false
    @Volatile
    private var isCreated = false

    private var isTurnstileEventSent = false
    private var country: Country = Country.Unknown

    private val sensorDataListener = object : SensorDataListener {
        override fun onDeviceMotionData(deviceMotionData: DeviceMotionData) {
            nativeVisionManager.setMotion(
                rotations = deviceMotionData.rotations,
                orientations = deviceMotionData.orientations,
                screenOrientation = deviceMotionData.screenOrientation,
                userAccelerationRelativeToGravity = deviceMotionData.userAccelerationRelativeToGravity,
                gravity = deviceMotionData.gravity,
                heading = deviceMotionData.heading
            )
        }

        override fun onHeadingData(headingData: HeadingData) {
            nativeVisionManager.setHeading(
                trueHeading = headingData.trueHeading,
                geomagneticHeading = headingData.geomagneticHeading,
                timestamp = headingData.timestamp
            )
        }
    }

    private val videoSourceListener = object : VideoSourceListener {
        override fun onNewFrame(
            rgbaBytes: ByteArray,
            imageFormat: ImageFormat,
            imageSize: ImageSize
        ) {
            nativeVisionManager.setFrame(
                rgbaByteArray = rgbaBytes,
                imageFormat = imageFormat,
                width = imageSize.imageWidth,
                height = imageSize.imageHeight
            )
            externalVideoSourceListener?.onNewFrame(rgbaBytes, imageFormat, imageSize)
        }

        override fun onNewCameraParameters(cameraParameters: CameraParameters) {
            nativeVisionManager.setCameraParameters(
                width = cameraParameters.width,
                height = cameraParameters.height,
                focalXPixels = cameraParameters.focalInPixelsX,
                focalYPixels = cameraParameters.focalInPixelsY
            )
            externalVideoSourceListener?.onNewCameraParameters(cameraParameters)
        }
    }

    private val sessionListener: (String, Long, String, Array<VideoClip>) -> Unit =
        { telemetryDir, sessionStartMillis, videoPath, clips ->
            videoProcessor.splitVideoClips(
                clips = clips,
                videoPath = videoPath,
                outputDir = telemetryDir,
                sessionStartMillis = sessionStartMillis
            )
        }

    private val videoProcessorListener = object : VideoProcessorListener {
        override fun onVideoClipsReady(
            videoClips: HashMap<String, VideoClip>,
            videoDir: String,
            jsonFile: String
        ) {
            telemetrySyncManager.syncSessionDir(videoDir)
        }
    }

    /**
     * Initialize SDK with mapbox access token and application instance.
     * Do it once per application session, eg in [android.app.Application.onCreate].
     */
    @JvmStatic
    fun init(application: Application, mapboxToken: String) {
        this.mapboxToken = mapboxToken
        this.application = application
    }

    /**
     * Initialize SDK. Creates core services and allocates necessary resources.
     * No-op if called while SDK is created already.
     */
    @JvmOverloads
    @JvmStatic
    fun create(
        videoSource: VideoSource = Camera2VideoSourceImpl(application)
    ) {
        checkManagerInit()
        if (isCreated) {
            VisionLogger.w(TAG, "VisionManager was already created!")
            return
        }

        mapboxTelemetry = MapboxTelemetry(application, mapboxToken, MAPBOX_TELEMETRY_USER_AGENT)
        mapboxTelemetry.updateDebugLoggingEnabled(BuildConfig.DEBUG)

        if (!isTurnstileEventSent) {
            mapboxTelemetry.push(
                AppUserTurnstile(MAPBOX_VISION_IDENTIFIER, BuildConfig.VERSION_NAME)
            )
            isTurnstileEventSent = true
        }

        telemetryImageSaver = TelemetryImageSaver()

        nativeVisionManager = NativeVisionManager(mapboxToken, application)
        nativeVisionManager.create(
            telemetryEventManager = MapboxTelemetryEventManager(mapboxTelemetry),
            telemetryImageSaver = telemetryImageSaver
        )

        sensorsManager = SensorsManager(application)
        sensorsManager.setSensorDataListener(sensorDataListener)
        locationEngine = AndroidLocationEngineImpl(application)
        videoProcessor = VideoProcessor.Impl()

        rootTelemetryDir = FileUtils.getAppRelativeDir(application, DIR_TELEMETRY)
        telemetrySyncManager = TelemetrySyncManager.Impl(
            mapboxTelemetry = mapboxTelemetry,
            context = application,
            rootTelemetryDir = rootTelemetryDir
        )
        performanceManager = PerformanceManager.getPerformanceManager(nativeVisionManager)

        this.videoSource = videoSource
        (videoSource as? Camera2VideoSourceImpl)?.let {
            val videoRecorder = SurfaceVideoRecorder.MediaCodecPersistentSurfaceImpl(application)
            it.setVideoRecorder(videoRecorder)

            this.videoRecorder = videoRecorder

            sessionManager = TelemetrySessionManager.RotatedBuffersImpl(
                application,
                nativeVisionManager,
                rootTelemetryDir,
                videoRecorder,
                telemetryImageSaver,
                sessionListener
            )
        }

        isCreated = true
    }

    /**
     * Start delivering events from SDK.
     * Should be called with all permission granted, and after [create] is called.
     * No-op if called while SDK is started already.
     */
    @JvmStatic
    fun start(visionEventsListener: VisionEventsListener) {
        checkManagerInit()
        checkManagerCreated()
        if (isStarted) {
            VisionLogger.w(TAG, "VisionManager was already started.")
            return
        }

        isStarted = true
        this.visionEventsListener = visionEventsListener
        nativeVisionManager.start(object : VisionEventsListener by visionEventsListener {
            override fun onCountryUpdated(country: Country) {
                visionEventsListener.onCountryUpdated(country)
                setCountry(country)
            }
        })
        sensorsManager.start()
        locationEngine.attach(nativeVisionManager)
        videoProcessor.attach(videoProcessorListener)
        sessionManager?.start()
        videoSource.attach(videoSourceListener)

    }

    fun setCountry(country: Country) {
        if (this.country == country) {
            return
        }

        when (country) {
            Country.China -> {
                telemetrySyncManager.stop()
                sessionManager?.stop()
                File(rootTelemetryDir).apply {
                    deleteRecursively()
                    mkdirs()
                }
            }
            Country.USA, Country.Other -> {
                telemetrySyncManager.start()
                sessionManager?.start()
            }
            Country.Unknown -> {
                telemetrySyncManager.stop()
                sessionManager?.start()
            }
        }

        this.country = country
    }

    /**
     * Stop delivering events from SDK.
     * Stops ML processing and video source.
     * To resume call [start] again.
     * No-op if called while SDK is not created or started.
     */
    @JvmStatic
    fun stop() {
        checkManagerInit()
        if (!isCreated || !isStarted) {
            VisionLogger.w(TAG, "VisionManager was not created yet.")
            return
        }

        videoSource.detach()
        sessionManager?.stop()
        videoProcessor.detach()
        locationEngine.detach()
        sensorsManager.stop()
        nativeVisionManager.stop()
        isStarted = false
    }

    /**
     * Releases all resources.
     * No-op if called while SDK is not created.
     */
    @JvmStatic
    fun destroy() {
        checkManagerInit()
        if (!isCreated) {
            VisionLogger.w(TAG, "VisionManager wasn't created, nothing to destroy.")
            return
        }

        videoRecorder?.release()

        nativeVisionManager.destroy()
        isCreated = false
    }

    @JvmStatic
    fun setVideoSourceListener(videoSourceListener: VideoSourceListener) {
        this.externalVideoSourceListener = videoSourceListener
    }

    @JvmStatic
    fun setModelPerformanceConfig(modelPerformanceConfig: ModelPerformanceConfig) {
        performanceManager.setModelConfig(modelPerformanceConfig)
    }

    @JvmStatic
    fun worldToPixel(worldCoordinate: WorldCoordinate): PixelCoordinate {
        checkManagerStarted()
        return nativeVisionManager.worldToPixel(worldCoordinate)
    }

    @JvmStatic
    fun pixelToWorld(pixelCoordinate: PixelCoordinate): WorldCoordinate {
        checkManagerStarted()
        return nativeVisionManager.pixelToWorld(pixelCoordinate)
    }

    @JvmStatic
    fun worldToGeo(worldCoordinate: WorldCoordinate): GeoCoordinate {
        checkManagerStarted()
        return nativeVisionManager.worldToGeo(worldCoordinate)
    }

    @JvmStatic
    fun geoToWorld(geoCoordinate: GeoCoordinate): WorldCoordinate {
        checkManagerStarted()
        return nativeVisionManager.geoToWorld(geoCoordinate)
    }

    @JvmStatic
    fun getFrameStatistics(): FrameStatistics {
        checkManagerStarted()
        return nativeVisionManager.getFrameStatistics()
    }

    @JvmStatic
    fun getDetectionsImage(frameDetections: FrameDetections): ByteArray {
        checkManagerStarted()
        return nativeVisionManager.getDetectionsFrameBytes(frameDetections.frame.image.identifier)
    }

    @JvmStatic
    fun getSegmentationImage(frameSegmentation: FrameSegmentation): ByteArray {
        checkManagerStarted()
        return nativeVisionManager.getSegmentationFrameBytes(frameSegmentation.frame.image.identifier)
    }

    @JvmStatic
    fun registerModule(moduleInterface: ModuleInterface) {
        moduleInterface.registerModule(nativeVisionManager.getModulePtr())
    }

    private fun checkManagerInit() {
        if (!::application.isInitialized || !::mapboxToken.isInitialized) {
            throw IllegalStateException("VisionManager was not initialized. Set application and valid mapbox token in VisionManager.init() first.")
        }
    }

    private fun checkManagerCreated() {
        if (!isCreated) {
            throw IllegalStateException("VisionManager was not created. Call VisionManager.create() first.")
        }
    }

    private fun checkManagerStarted() {
        if (!isStarted) {
            throw IllegalStateException("VisionManager was not started. Call VisionManager.start() first.")
        }
    }
}

