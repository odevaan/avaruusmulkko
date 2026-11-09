package com.avaruus.astro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AstroCameraManager"

class AstroCameraManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Dedicated single background thread for ALL Camera2 operations — never blocks the UI Main Thread.
    private val cameraThread: HandlerThread = HandlerThread("AstroCameraBackground").apply { start() }
    private val cameraHandler: Handler = Handler(cameraThread.looper)
    private val cameraExecutor = java.util.concurrent.Executor { command ->
        if (!cameraThread.isAlive) return@Executor
        cameraHandler.post(command)
    }

    // Cached CameraCharacteristics so preview updates and lens switches avoid repeated Binder IPC stalls.
    private val charsCache = ConcurrentHashMap<String, CameraCharacteristics>()

    // All Camera2 state below is accessed/mutated exclusively on [cameraHandler].
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawImageReader: ImageReader? = null
    private var previewAnalysisReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var isOpeningDevice: Boolean = false
    private var sessionEpoch: Long = 0L

    private var includeAnalysisInSession: Boolean = true
    private var currentPreviewStepIndex: Int = PreviewAutoExposure.DEFAULT_STEP_INDEX
    private var previewGeneration: Long = 0L
    private var acceptedGenStartTimestamp: Long = Long.MAX_VALUE
    private var lastAeEvalTimeMs: Long = 0L

    // Single-frame chaining state for long (>160ms) dark-sky preview exposures (keeps HAL queue depth = 1)
    private var isUsingSinglePreviewChain: Boolean = false
    private var singlePreviewInFlight: Boolean = false
    private var singlePreviewCaptureDone: Boolean = false
    private var singlePreviewImageEvaluated: Boolean = false
    private var lastSinglePreviewRequest: CaptureRequest? = null

    private val histogramScratch = IntArray(256)
    private val binningWorkspace = PreviewAutoExposure.BinningWorkspace()

    @Volatile
    private var displayRotationDeg: Int = 0

    @Volatile
    private var pendingLensSwitch: Pair<CameraLens, Surface>? = null

    private val _availableLenses = MutableStateFlow<List<CameraLens>>(emptyList())
    val availableLenses: StateFlow<List<CameraLens>> = _availableLenses.asStateFlow()

    private val _currentLens = MutableStateFlow<CameraLens?>(null)
    val currentLens: StateFlow<CameraLens?> = _currentLens.asStateFlow()

    private val _settings = MutableStateFlow(CaptureSettings())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    private val _lastCapturedFile = MutableStateFlow<File?>(null)
    val lastCapturedFile: StateFlow<File?> = _lastCapturedFile.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _binnedPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val binnedPreviewBitmap: StateFlow<Bitmap?> = _binnedPreviewBitmap.asStateFlow()

    private val _currentBinFactor = MutableStateFlow(1)
    val currentBinFactor: StateFlow<Int> = _currentBinFactor.asStateFlow()

    private var currentSessionDir: File? = null

    private val nextSinglePreviewRunnable = Runnable {
        if (!_isCapturing.value && isUsingSinglePreviewChain && !singlePreviewInFlight) {
            updatePreviewInternal()
        }
    }

    private val coalescedPreviewUpdateRunnable = Runnable {
        if (!_isCapturing.value) {
            updatePreviewInternal()
        }
    }

    private val coalescedSelectLensRunnable = Runnable {
        val req = pendingLensSwitch ?: return@Runnable
        pendingLensSwitch = null
        selectLensInternal(req.first, req.second)
    }

    init {
        detectLenses()
    }

    private fun postToCameraThread(block: () -> Unit) {
        if (!cameraThread.isAlive) return
        if (Looper.myLooper() == cameraHandler.looper) {
            block()
        } else {
            cameraHandler.post(block)
        }
    }

    private fun getChars(cameraId: String): CameraCharacteristics {
        return charsCache.getOrPut(cameraId) {
            cameraManager.getCameraCharacteristics(cameraId)
        }
    }

    private fun getActiveLensCharacteristics(logicalCameraId: String, lens: CameraLens?): CameraCharacteristics {
        val pId = lens?.physicalCameraId
        return if (!pId.isNullOrEmpty()) {
            try {
                getChars(pId)
            } catch (e: Exception) {
                getChars(logicalCameraId)
            }
        } else {
            getChars(logicalCameraId)
        }
    }

    /**
     * Enumerates cameras on the Pixel 9 Pro XL.
     * Identifies exactly three physical optical cameras:
     * - 0.5x 48MP f/1.7 (Ultra-Wide)
     * - 1.0x 50MP f/1.68 (Main Wide)
     * - 5.0x 48MP f/2.8 (Telephoto)
     */
    fun detectLenses() {
        val detectedLenses = mutableListOf<CameraLens>()
        try {
            var defaultBackId = "0"
            val physicalSensors = mutableListOf<CameraLens>()
            val physicalCharsList = mutableListOf<CameraCharacteristics>()

            for (id in cameraManager.cameraIdList) {
                val chars = getChars(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    defaultBackId = id
                    val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

                    val maxExp = expRange?.upper ?: 16_000_000_000L
                    val minExp = expRange?.lower ?: 100_000L
                    val minIso = isoRange?.lower ?: 50
                    val maxIso = isoRange?.upper ?: 6400

                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) {
                        for (pId in physicalIds) {
                            try {
                                val pChars = getChars(pId)
                                physicalCharsList.add(pChars)
                                val focalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val focalLength = focalLengths?.firstOrNull() ?: 5.0f
                                val apertures = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                                val aperture = apertures?.firstOrNull() ?: 1.8f
                                val minFocus = pChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: minFocusDist

                                val pMap = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                val rawSizes = pMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                                val maxRaw = rawSizes?.maxByOrNull { it.width * it.height }
                                val rawW = maxRaw?.width ?: 0
                                val rawH = maxRaw?.height ?: 0
                                val pOrientation = pChars.get(CameraCharacteristics.SENSOR_ORIENTATION)
                                    ?: chars.get(CameraCharacteristics.SENSOR_ORIENTATION)
                                    ?: 90

                                val zoomRatio = when {
                                    focalLength < 3.5f -> 0.5f
                                    focalLength in 3.5f..10.0f -> 1.0f
                                    else -> 5.0f
                                }

                                physicalSensors.add(
                                    CameraLens(
                                        id = id,
                                        physicalCameraId = pId,
                                        name = "",
                                        opticalZoomRatio = zoomRatio,
                                        opticalZoom = "${zoomRatio}x",
                                        focalLengthMm = focalLength,
                                        aperture = aperture,
                                        maxExposureNs = maxExp,
                                        minExposureNs = minExp,
                                        minIso = minIso,
                                        maxIso = maxIso,
                                        minFocusDistance = minFocus,
                                        isRawSupported = true,
                                        rawWidth = rawW,
                                        rawHeight = rawH,
                                        sensorOrientation = pOrientation
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading physical camera $pId", e)
                            }
                        }
                    }
                }
            }

            // Group physical sensors by magnification and pick the best RAW resolution for each
            val best05 = physicalSensors.filter { it.opticalZoomRatio == 0.5f }
                .maxByOrNull { it.rawWidth.toLong() * it.rawHeight.toLong() }
            val best10 = physicalSensors.filter { it.opticalZoomRatio == 1.0f }
                .maxByOrNull { it.rawWidth.toLong() * it.rawHeight.toLong() }
            val best50 = physicalSensors.filter { it.opticalZoomRatio == 5.0f }
                .maxByOrNull { it.rawWidth.toLong() * it.rawHeight.toLong() }

            val chars = getChars(defaultBackId)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val maxAnalogSensitivity = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 1600
            val defaultOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // Shared 4:3 full-frame preview buffer size across all lenses so SurfaceView never reallocates on lens switch
            val sharedPreviewSize = chooseSharedFullFramePreviewSize(chars, physicalCharsList)
            val maxExp = expRange?.upper ?: 16_000_000_000L
            val minExp = expRange?.lower ?: 100_000L
            val minIso = isoRange?.lower ?: 50
            val maxIso = isoRange?.upper ?: 6400
            Log.i(
                TAG,
                "Hardware max analog sensitivity: $maxAnalogSensitivity; shared 4:3 preview: ${sharedPreviewSize.width}x${sharedPreviewSize.height}"
            )

            // Pixel 9 Pro XL Optical Triplet - exactly one entry per physical lens
            val lens05 = CameraLens(
                id = defaultBackId,
                physicalCameraId = best05?.physicalCameraId,
                name = "0.5x 48MP f/1.7",
                opticalZoomRatio = 0.5f,
                opticalZoom = "0.5x",
                focalLengthMm = best05?.focalLengthMm ?: 2.1f,
                aperture = 1.7f,
                maxExposureNs = best05?.maxExposureNs ?: maxExp,
                minExposureNs = best05?.minExposureNs ?: minExp,
                minIso = best05?.minIso ?: minIso,
                maxIso = best05?.maxIso ?: maxIso,
                maxAnalogIso = maxAnalogSensitivity,
                minFocusDistance = best05?.minFocusDistance ?: 10.0f,
                isRawSupported = true,
                rawWidth = if (best05 != null && best05.rawWidth > 0) best05.rawWidth else 8160,
                rawHeight = if (best05 != null && best05.rawHeight > 0) best05.rawHeight else 6120,
                sensorOrientation = best05?.sensorOrientation ?: defaultOrientation,
                previewWidth = sharedPreviewSize.width,
                previewHeight = sharedPreviewSize.height
            )

            val lens10 = CameraLens(
                id = defaultBackId,
                physicalCameraId = best10?.physicalCameraId,
                name = "1.0x 50MP f/1.68",
                opticalZoomRatio = 1.0f,
                opticalZoom = "1.0x",
                focalLengthMm = best10?.focalLengthMm ?: 6.9f,
                aperture = 1.68f,
                maxExposureNs = best10?.maxExposureNs ?: maxExp,
                minExposureNs = best10?.minExposureNs ?: minExp,
                minIso = best10?.minIso ?: minIso,
                maxIso = best10?.maxIso ?: maxIso,
                maxAnalogIso = maxAnalogSensitivity,
                minFocusDistance = best10?.minFocusDistance ?: 10.0f,
                isRawSupported = true,
                rawWidth = if (best10 != null && best10.rawWidth > 0) best10.rawWidth else 8160,
                rawHeight = if (best10 != null && best10.rawHeight > 0) best10.rawHeight else 6120,
                sensorOrientation = best10?.sensorOrientation ?: defaultOrientation,
                previewWidth = sharedPreviewSize.width,
                previewHeight = sharedPreviewSize.height
            )

            val lens50 = CameraLens(
                id = defaultBackId,
                physicalCameraId = best50?.physicalCameraId,
                name = "5.0x 48MP f/2.8",
                opticalZoomRatio = 5.0f,
                opticalZoom = "5.0x",
                focalLengthMm = best50?.focalLengthMm ?: 19.0f,
                aperture = 2.8f,
                maxExposureNs = best50?.maxExposureNs ?: maxExp,
                minExposureNs = best50?.minExposureNs ?: minExp,
                minIso = best50?.minIso ?: minIso,
                maxIso = best50?.maxIso ?: maxIso,
                maxAnalogIso = maxAnalogSensitivity,
                minFocusDistance = best50?.minFocusDistance ?: 5.0f,
                isRawSupported = true,
                rawWidth = if (best50 != null && best50.rawWidth > 0) best50.rawWidth else 8160,
                rawHeight = if (best50 != null && best50.rawHeight > 0) best50.rawHeight else 6120,
                sensorOrientation = best50?.sensorOrientation ?: defaultOrientation,
                previewWidth = sharedPreviewSize.width,
                previewHeight = sharedPreviewSize.height
            )

            detectedLenses.add(lens05)
            detectedLenses.add(lens10)
            detectedLenses.add(lens50)

            _availableLenses.value = detectedLenses
            if (_currentLens.value == null) {
                _currentLens.value = lens10
                _settings.value = _settings.value.copy(lens = lens10)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras", e)
        }
    }

    /**
     * Picks a single unified 4:3 SurfaceHolder preview size within Camera2's PREVIEW tier (<= 1920x1080)
     * supported across the logical camera and physical lenses so lens switching never resizes SurfaceView.
     */
    private fun chooseSharedFullFramePreviewSize(
        logicalChars: CameraCharacteristics,
        physicalCharsList: List<CameraCharacteristics>
    ): Size {
        val targetAspect = 4f / 3f
        val logicalSizes = logicalChars
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceHolder::class.java)
            ?.toList()
            .orEmpty()

        val valid4x3Logical = logicalSizes.filter { sz ->
            sz.width <= 1920 &&
                sz.height <= 1080 &&
                kotlin.math.abs((sz.width.toFloat() / sz.height.toFloat()) - targetAspect) <= 0.03f
        }

        val commonAcrossAll = if (physicalCharsList.isNotEmpty() && valid4x3Logical.isNotEmpty()) {
            valid4x3Logical.filter { candidate ->
                physicalCharsList.all { pChars ->
                    val pSizes = pChars
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceHolder::class.java)
                    pSizes == null || pSizes.any { it.width == candidate.width && it.height == candidate.height }
                }
            }.ifEmpty { valid4x3Logical }
        } else {
            valid4x3Logical
        }

        return commonAcrossAll.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    /**
     * Non-blocking lens switch: updates UI state immediately and performs all session/stream
     * reconfiguration asynchronously on [cameraHandler].
     */
    fun selectLens(lens: CameraLens, previewSurface: Surface) {
        val previous = _currentLens.value
        _currentLens.value = lens
        _settings.value = _settings.value.copy(lens = lens)

        if (previous?.opticalZoomRatio == lens.opticalZoomRatio &&
            previous.physicalCameraId == lens.physicalCameraId &&
            pendingLensSwitch == null
        ) {
            return
        }

        pendingLensSwitch = Pair(lens, previewSurface)
        cameraHandler.removeCallbacks(coalescedSelectLensRunnable)
        cameraHandler.post(coalescedSelectLensRunnable)
    }

    fun setDisplayRotationDegrees(deg: Int) {
        displayRotationDeg = deg
    }

    private fun selectLensInternal(lens: CameraLens, surface: Surface) {
        if (!surface.isValid) return
        this.previewSurface = surface

        // Reset preview AE to responsive 100ms 1x1 step on lens switch
        if (currentPreviewStepIndex > PreviewAutoExposure.DEFAULT_STEP_INDEX) {
            currentPreviewStepIndex = PreviewAutoExposure.DEFAULT_STEP_INDEX
        }
        _currentBinFactor.value = 1
        _binnedPreviewBitmap.value = null

        cancelPreviewChainCallbacks()
        sessionEpoch++

        val device = cameraDevice
        if (device != null && device.id == lens.id) {
            try {
                tearDownCurrentSessionInternal()
                setupRawImageReader(device.id, lens)
                setupPreviewAnalysisReader(device.id, lens)
                createCaptureSession(device, surface, lens)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Seamless physical switch failed, reopening camera", e)
            }
        }

        closeCameraInternal()
        openCameraInternal(lens.id, surface)
    }

    /**
     * Non-blocking settings update: only schedules a background preview refresh when focus or
     * preview zoom loupe changed (capture EXPOSURE/GAIN sliders do not need to touch Camera2).
     */
    fun updateSettings(newSettings: CaptureSettings) {
        val prev = _settings.value
        _settings.value = newSettings

        val previewNeedsRefresh =
            prev.focusDistance != newSettings.focusDistance ||
                prev.zoomFactor != newSettings.zoomFactor ||
                prev.mode != newSettings.mode

        if (previewNeedsRefresh && !_isCapturing.value) {
            cameraHandler.removeCallbacks(coalescedPreviewUpdateRunnable)
            cameraHandler.post(coalescedPreviewUpdateRunnable)
        }
    }

    private fun setupRawImageReader(logicalCameraId: String, lens: CameraLens?) {
        rawImageReader?.close()
        rawImageReader = null

        val chars = getActiveLensCharacteristics(logicalCameraId, lens)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawSize = rawSizes?.maxByOrNull { it.width * it.height }
            ?: (if (lens != null && lens.rawWidth > 0 && lens.rawHeight > 0) Size(lens.rawWidth, lens.rawHeight) else Size(4080, 3072))

        Log.i(TAG, "Setting up RAW ImageReader for ${lens?.name ?: logicalCameraId}: ${rawSize.width}x${rawSize.height}")

        rawImageReader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            3
        )
    }

    private fun setupPreviewAnalysisReader(logicalCameraId: String, lens: CameraLens?) {
        previewAnalysisReader?.close()
        previewAnalysisReader = null

        val chars = getActiveLensCharacteristics(logicalCameraId, lens)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        val sensorAspect = if (lens != null && lens.rawWidth > 0 && lens.rawHeight > 0) {
            lens.rawWidth.toFloat() / lens.rawHeight.toFloat()
        } else {
            4f / 3f
        }
        // Choose a 4:3 YUV_420_888 resolution (preferring 640..1440 width divisible by 12 so 2x2, 3x3, and 4x4 divide evenly)
        val aspectMatched = yuvSizes
            ?.filter {
                it.width in 480..1440 &&
                    it.height in 360..1080 &&
                    kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - sensorAspect) <= 0.03f
            }
        val divisibleBy12 = aspectMatched?.filter { it.width % 12 == 0 && it.height % 12 == 0 }
        val analysisSize = divisibleBy12?.maxByOrNull { it.width * it.height }
            ?: aspectMatched?.maxByOrNull { it.width * it.height }
            ?: yuvSizes?.filter { it.width <= 1440 && it.height <= 1080 }?.maxByOrNull { it.width * it.height }
            ?: Size(960, 720)

        Log.i(TAG, "Setting up Preview Binning/AE YUV ImageReader: ${analysisSize.width}x${analysisSize.height}")

        previewAnalysisReader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                onPreviewFrameAvailable(reader)
            }, cameraHandler)
        }
    }

    /**
     * Non-blocking camera open: posts all Camera2 work to [cameraHandler].
     */
    fun openCamera(cameraId: String, surface: Surface) {
        postToCameraThread {
            openCameraInternal(cameraId, surface)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(cameraId: String, surface: Surface) {
        if (!surface.isValid) return
        if (cameraDevice?.id == cameraId && captureSession != null && this.previewSurface === surface) {
            return
        }
        if (isOpeningDevice) {
            this.previewSurface = surface
            return
        }

        this.previewSurface = surface
        isOpeningDevice = true
        val openEpoch = ++sessionEpoch

        try {
            val targetLens = _currentLens.value
            setupRawImageReader(cameraId, targetLens)
            setupPreviewAnalysisReader(cameraId, targetLens)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpeningDevice = false
                    if (openEpoch != sessionEpoch || previewSurface?.isValid != true) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession(camera, previewSurface ?: surface, _currentLens.value)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpeningDevice = false
                    camera.close()
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        captureSession = null
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpeningDevice = false
                    camera.close()
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        captureSession = null
                    }
                    Log.e(TAG, "Camera open error: $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            isOpeningDevice = false
            Log.e(TAG, "Failed to open camera $cameraId", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: Surface, lens: CameraLens?) {
        val reader = rawImageReader ?: return
        if (!surface.isValid) return
        val analysisReader = previewAnalysisReader
        val expectedEpoch = sessionEpoch

        try {
            val previewConfig = OutputConfiguration(surface)
            val rawConfig = OutputConfiguration(reader.surface)
            val analysisConfig = analysisReader?.let { OutputConfiguration(it.surface) }

            val physicalId = lens?.physicalCameraId
            if (!physicalId.isNullOrEmpty()) {
                try {
                    previewConfig.setPhysicalCameraId(physicalId)
                    rawConfig.setPhysicalCameraId(physicalId)
                    analysisConfig?.setPhysicalCameraId(physicalId)
                    Log.i(TAG, "Configuring session with physical camera ID: $physicalId")
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not set physical camera ID $physicalId", e)
                }
            }

            val outputConfigs = buildList {
                add(previewConfig)
                if (analysisConfig != null) add(analysisConfig)
                add(rawConfig)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== camera || expectedEpoch != sessionEpoch) {
                            try {
                                session.close()
                            } catch (_: Exception) {
                            }
                            return
                        }
                        captureSession = session
                        includeAnalysisInSession = analysisConfig != null
                        Log.i(TAG, "Capture session configured for ${lens?.name} (AE stream=$includeAnalysisInSession)")
                        updatePreviewInternal()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cameraDevice !== camera || expectedEpoch != sessionEpoch) return
                        Log.e(TAG, "Capture session with physical ID $physicalId failed, falling back to standard session")
                        fallbackStandardSession(camera, surface, reader.surface, expectedEpoch)
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session with SessionConfiguration, falling back", e)
            fallbackStandardSession(camera, surface, reader.surface, expectedEpoch)
        }
    }

    private fun fallbackStandardSession(
        camera: CameraDevice,
        preview: Surface,
        rawSurface: Surface,
        expectedEpoch: Long
    ) {
        if (!preview.isValid) return
        try {
            includeAnalysisInSession = false
            val surfaces = listOf(preview, rawSurface)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice !== camera || expectedEpoch != sessionEpoch) {
                        try {
                            session.close()
                        } catch (_: Exception) {
                        }
                        return
                    }
                    captureSession = session
                    updatePreviewInternal()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Fallback capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback createCaptureSession failed", e)
        }
    }

    private fun cancelPreviewChainCallbacks() {
        cameraHandler.removeCallbacks(nextSinglePreviewRunnable)
        cameraHandler.removeCallbacks(coalescedPreviewUpdateRunnable)
        singlePreviewInFlight = false
        singlePreviewCaptureDone = false
        singlePreviewImageEvaluated = false
        isUsingSinglePreviewChain = false
        lastSinglePreviewRequest = null
    }

    private fun tearDownCurrentSessionInternal() {
        val existing = captureSession ?: return
        captureSession = null
        try {
            existing.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            existing.abortCaptures()
        } catch (_: Exception) {
        }
        try {
            existing.close()
        } catch (_: Exception) {
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            val tagGen = request.tag as? Long
            if (tagGen != null && tagGen == previewGeneration && acceptedGenStartTimestamp == Long.MAX_VALUE) {
                acceptedGenStartTimestamp = timestamp
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (isUsingSinglePreviewChain && request === lastSinglePreviewRequest) {
                singlePreviewInFlight = false
                singlePreviewCaptureDone = true
                if (!_isCapturing.value && captureSession === session && pendingLensSwitch == null) {
                    cameraHandler.removeCallbacks(nextSinglePreviewRunnable)
                    // If YUV luminance frame was already evaluated, chain immediately; otherwise allow up to 80ms for YUV delivery
                    val delayMs = if (singlePreviewImageEvaluated) 10L else 80L
                    cameraHandler.postDelayed(nextSinglePreviewRunnable, delayMs)
                }
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (isUsingSinglePreviewChain && request === lastSinglePreviewRequest) {
                singlePreviewInFlight = false
                singlePreviewCaptureDone = true
                if (!_isCapturing.value && captureSession === session && pendingLensSwitch == null) {
                    cameraHandler.removeCallbacks(nextSinglePreviewRunnable)
                    cameraHandler.postDelayed(nextSinglePreviewRunnable, 60L)
                }
            }
        }
    }

    private fun onPreviewFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            if (_isCapturing.value || pendingLensSwitch != null || image.timestamp < acceptedGenStartTimestamp) {
                return
            }

            if (isUsingSinglePreviewChain && singlePreviewImageEvaluated) {
                return
            }

            val currentIdx = currentPreviewStepIndex.coerceIn(0, PreviewAutoExposure.STEPS.lastIndex)
            val currentStep = PreviewAutoExposure.STEPS[currentIdx]
            val activeBin = currentStep.binFactor
            val planes = image.planes
            val yPlane = planes.firstOrNull() ?: return

            val stats: PreviewAutoExposure.FrameLuminanceStats
            if (activeBin >= 2) {
                val uPlane = planes.getOrNull(1)
                val vPlane = planes.getOrNull(2)
                val sensorOrientation = _currentLens.value?.sensorOrientation ?: 90
                val rotDeg = ((sensorOrientation - displayRotationDeg) % 360 + 360) % 360

                val binnedResult = PreviewAutoExposure.binYuvFrameToArgb(
                    yBuffer = yPlane.buffer,
                    uBuffer = uPlane?.buffer,
                    vBuffer = vPlane?.buffer,
                    width = image.width,
                    height = image.height,
                    yRowStride = yPlane.rowStride,
                    yPixelStride = yPlane.pixelStride,
                    uvRowStride = uPlane?.rowStride ?: 0,
                    uvPixelStride = uPlane?.pixelStride ?: 1,
                    binFactor = activeBin,
                    rotationDeg = rotDeg,
                    workspace = binningWorkspace
                ) ?: return

                val bmp = Bitmap.createBitmap(
                    binningWorkspace.outArgb,
                    0,
                    binnedResult.outWidth,
                    binnedResult.outWidth,
                    binnedResult.outHeight,
                    Bitmap.Config.ARGB_8888
                )
                _currentBinFactor.value = activeBin
                _binnedPreviewBitmap.value = bmp
                stats = binnedResult.stats
            } else {
                if (_currentBinFactor.value != 1 || _binnedPreviewBitmap.value != null) {
                    _currentBinFactor.value = 1
                    _binnedPreviewBitmap.value = null
                }
                val nowCheck = SystemClock.elapsedRealtime()
                if (!isUsingSinglePreviewChain && nowCheck - lastAeEvalTimeMs < 180L) {
                    return
                }
                stats = PreviewAutoExposure.analyzeYPlane(
                    buffer = yPlane.buffer,
                    width = image.width,
                    height = image.height,
                    rowStride = yPlane.rowStride,
                    pixelStride = yPlane.pixelStride,
                    histogramScratch = histogramScratch
                ) ?: return
            }

            val now = SystemClock.elapsedRealtime()
            if (!isUsingSinglePreviewChain && now - lastAeEvalTimeMs < 180L) {
                return
            }
            lastAeEvalTimeMs = now
            singlePreviewImageEvaluated = true

            val nextIdx = PreviewAutoExposure.nextStepIndex(currentIdx, stats)
            if (nextIdx != currentIdx) {
                currentPreviewStepIndex = nextIdx
                val nextStep = PreviewAutoExposure.STEPS[nextIdx]
                _currentBinFactor.value = nextStep.binFactor
                if (nextStep.binFactor == 1) {
                    _binnedPreviewBitmap.value = null
                }
                Log.d(
                    TAG,
                    "Preview AE step $currentIdx -> $nextIdx (${nextStep.exposureTimeNs / 1_000_000.0}ms, ISO ${nextStep.iso}, bin ${nextStep.binFactor}x${nextStep.binFactor}) " +
                        "[meanY=${"%.1f".format(stats.meanY)}, p90Y=${"%.1f".format(stats.p90Y)}, clip=${"%.2f".format(stats.clippedFraction)}]"
                )
                val sensorParamsChanged =
                    nextStep.exposureTimeNs != currentStep.exposureTimeNs || nextStep.iso != currentStep.iso
                if (sensorParamsChanged) {
                    acceptedGenStartTimestamp = Long.MAX_VALUE
                    if (!isUsingSinglePreviewChain) {
                        updatePreviewInternal()
                        return
                    }
                }
            }

            if (isUsingSinglePreviewChain && singlePreviewCaptureDone && !singlePreviewInFlight) {
                cameraHandler.removeCallbacks(nextSinglePreviewRunnable)
                cameraHandler.postDelayed(nextSinglePreviewRunnable, 5L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview AE frame evaluation error", e)
        } finally {
            image.close()
        }
    }

    /**
     * Configures live preview parameters exclusively on [cameraHandler].
     * Uses setRepeatingRequest for fast shutter speeds (<= 160ms) and single-frame capture chaining
     * (queue depth = 1) for long dark-sky exposures so the HAL never builds up a multi-second backlog.
     */
    private fun updatePreviewInternal() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        if (!surface.isValid || _isCapturing.value || pendingLensSwitch != null) return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            if (includeAnalysisInSession) {
                previewAnalysisReader?.surface?.let { analysisSurface ->
                    if (analysisSurface.isValid) {
                        builder.addTarget(analysisSurface)
                    }
                }
            }

            val gen = ++previewGeneration
            acceptedGenStartTimestamp = Long.MAX_VALUE
            builder.setTag(gen)

            val current = _settings.value
            val lensChars = getActiveLensCharacteristics(device.id, current.lens)

            // Set manual focus distance (0.0f = infinity)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, current.focusDistance)

            val previewStep = PreviewAutoExposure.STEPS[
                currentPreviewStepIndex.coerceIn(0, PreviewAutoExposure.STEPS.lastIndex)
            ]
            val expRange = lensChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = lensChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val safeExpNs = if (expRange != null) {
                previewStep.exposureTimeNs.coerceIn(expRange.lower, expRange.upper)
            } else {
                previewStep.exposureTimeNs
            }
            val safeIso = if (isoRange != null) {
                previewStep.iso.coerceIn(isoRange.lower, isoRange.upper)
            } else {
                previewStep.iso
            }

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExpNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(safeExpNs, 33_333_333L))

            // OIS off for astronomy on mount/tripod
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

            // Select physical optical camera via native optical zoom ratio (0.5x, 1.0x, 5.0x)
            val logicalChars = getChars(device.id)
            val zoomRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val targetRatio = current.lens?.opticalZoomRatio ?: 1.0f
            if (zoomRange != null) {
                val safeRatio = targetRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeRatio)
            }

            // Digital zoom loupe preview if set
            if (current.zoomFactor > 1.0f) {
                val sensorRect = lensChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (sensorRect != null) {
                    val cropW = (sensorRect.width() / current.zoomFactor).toInt()
                    val cropH = (sensorRect.height() / current.zoomFactor).toInt()
                    val cropX = sensorRect.centerX() - cropW / 2
                    val cropY = sensorRect.centerY() - cropH / 2
                    val cropRect = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }

            val request = builder.build()
            val wantSingleChain = safeExpNs > PreviewAutoExposure.LONG_EXPOSURE_CHAIN_THRESHOLD_NS

            if (!wantSingleChain) {
                cameraHandler.removeCallbacks(nextSinglePreviewRunnable)
                isUsingSinglePreviewChain = false
                singlePreviewInFlight = false
                singlePreviewCaptureDone = false
                singlePreviewImageEvaluated = false
                lastSinglePreviewRequest = null
                session.setRepeatingRequest(request, previewCaptureCallback, cameraHandler)
            } else {
                if (!isUsingSinglePreviewChain) {
                    isUsingSinglePreviewChain = true
                    try {
                        session.stopRepeating()
                    } catch (_: Exception) {
                    }
                }
                if (!singlePreviewInFlight) {
                    singlePreviewInFlight = true
                    singlePreviewCaptureDone = false
                    singlePreviewImageEvaluated = !includeAnalysisInSession
                    lastSinglePreviewRequest = request
                    session.capture(request, previewCaptureCallback, cameraHandler)
                }
            }
        } catch (e: Exception) {
            singlePreviewInFlight = false
            Log.e(TAG, "Failed to update preview request", e)
        }
    }

    /**
     * Executes a single still astrophotography exposure saving RAW DNG.
     * Submits Camera2 commands strictly on [cameraHandler] and writes DNG asynchronously on [Dispatchers.IO].
     */
    suspend fun captureAstrophotoFrame(
        sessionName: String,
        frameIndex: Int,
        frameType: FrameType
    ): File? = withContext(Dispatchers.IO) {
        val current = _settings.value
        val activeLens = current.lens
        val targetDir = getOrCreateSessionDir(sessionName)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${frameType.prefix}_${"%04d".format(frameIndex)}_${timestamp}_${current.exposureTimeNs / 1_000_000}ms_ISO${current.iso}.dng"
        val outputFile = File(targetDir, fileName)

        val rawImageDeferred = CompletableDeferred<Image?>()
        val captureResultDeferred = CompletableDeferred<TotalCaptureResult?>()
        val logicalIdDeferred = CompletableDeferred<String?>()

        _isCapturing.value = true

        try {
            postToCameraThread {
                val session = captureSession
                val device = cameraDevice
                val reader = rawImageReader
                if (session == null || device == null || reader == null) {
                    logicalIdDeferred.complete(null)
                    rawImageDeferred.complete(null)
                    captureResultDeferred.complete(null)
                    return@postToCameraThread
                }

                logicalIdDeferred.complete(device.id)
                cancelPreviewChainCallbacks()

                try {
                    session.stopRepeating()
                } catch (_: Exception) {
                }

                reader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
                    override fun onImageAvailable(r: ImageReader) {
                        reader.setOnImageAvailableListener(null, null)
                        val img = try {
                            r.acquireLatestImage()
                        } catch (e: Exception) {
                            null
                        }
                        if (!rawImageDeferred.complete(img)) {
                            img?.close()
                        }
                    }
                }, cameraHandler)

                try {
                    val chars = getChars(device.id)
                    val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
                    captureBuilder.addTarget(reader.surface)

                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, current.focusDistance)

                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, current.exposureTimeNs)
                    captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, current.iso)
                    captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, current.exposureTimeNs)

                    // Critical for Astrophotography: disable noise reduction and hot pixel suppression
                    // to preserve linear sensor data and faint stars
                    captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    captureBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                    captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    captureBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    val captureRatio = activeLens?.opticalZoomRatio ?: 1.0f
                    if (zoomRange != null) {
                        val safeRatio = captureRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                        captureBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeRatio)
                    }

                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            captureResultDeferred.complete(result)
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            Log.e(TAG, "Capture failed: reason ${failure.reason}")
                            captureResultDeferred.complete(null)
                            if (!rawImageDeferred.isCompleted) {
                                rawImageDeferred.complete(null)
                            }
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Error submitting astro capture on camera thread", e)
                    rawImageDeferred.complete(null)
                    captureResultDeferred.complete(null)
                }
            }

            val timeoutMs = ((current.exposureTimeNs / 1_000_000_000L) + 15L) * 1000L
            val pair = withTimeoutOrNull(timeoutMs) {
                val img = rawImageDeferred.await() ?: return@withTimeoutOrNull null
                try {
                    val res = captureResultDeferred.await()
                    val devId = logicalIdDeferred.await()
                    if (res != null && devId != null) {
                        Triple(img, res, devId)
                    } else {
                        img.close()
                        null
                    }
                } catch (t: Throwable) {
                    img.close()
                    throw t
                }
            } ?: return@withContext null

            val (image, result, logicalId) = pair
            try {
                val dngChars = getActiveLensCharacteristics(logicalId, activeLens)
                DngCreator(dngChars, result).use { dngCreator ->
                    dngCreator.setDescription(
                        "Avaruusmulkko Astro Suite RAW (${activeLens?.name ?: "1.0x"}); Exp=${current.exposureTimeNs}ns, ISO=${current.iso}"
                    )
                    FileOutputStream(outputFile).use { fos ->
                        dngCreator.writeImage(fos, image)
                    }
                }
                Log.i(TAG, "Saved DNG: ${outputFile.absolutePath} (${outputFile.length() / 1024 / 1024} MB)")
                _lastCapturedFile.value = outputFile
                outputFile
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing astro capture", e)
            null
        } finally {
            _isCapturing.value = false
            rawImageDeferred.cancel()
            postToCameraThread {
                rawImageReader?.setOnImageAvailableListener(null, null)
                updatePreviewInternal()
            }
        }
    }

    private fun getOrCreateSessionDir(sessionName: String): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val astroRoot = File(picturesDir, "AvaruusAstro")
        val sessionDir = File(astroRoot, sessionName)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
        currentSessionDir = sessionDir
        return sessionDir
    }

    /**
     * Non-blocking camera close (called when SurfaceView is destroyed, e.g. switching to VIEW mode).
     */
    fun closeCamera() {
        pendingLensSwitch = null
        postToCameraThread {
            closeCameraInternal()
        }
    }

    private fun closeCameraInternal() {
        sessionEpoch++
        isOpeningDevice = false
        previewSurface = null
        if (currentPreviewStepIndex > PreviewAutoExposure.DEFAULT_STEP_INDEX) {
            currentPreviewStepIndex = PreviewAutoExposure.DEFAULT_STEP_INDEX
        }
        _currentBinFactor.value = 1
        _binnedPreviewBitmap.value = null
        cancelPreviewChainCallbacks()
        try {
            tearDownCurrentSessionInternal()
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            previewAnalysisReader?.close()
            previewAnalysisReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    /**
     * Shuts down camera resources and quits the dedicated background thread asynchronously on ViewModel clear.
     */
    fun release() {
        pendingLensSwitch = null
        if (!cameraThread.isAlive) return
        cameraHandler.post {
            closeCameraInternal()
            cameraThread.quitSafely()
        }
    }
}
