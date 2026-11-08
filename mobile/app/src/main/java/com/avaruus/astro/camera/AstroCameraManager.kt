package com.avaruus.astro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "AstroCameraManager"

class AstroCameraManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawImageReader: ImageReader? = null
    private var previewAnalysisReader: ImageReader? = null
    private var previewSurface: Surface? = null

    @Volatile
    private var includeAnalysisInSession: Boolean = true

    @Volatile
    private var currentPreviewStepIndex: Int = PreviewAutoExposure.DEFAULT_STEP_INDEX

    @Volatile
    private var previewGeneration: Long = 0L

    @Volatile
    private var activeGenerationLanded: Boolean = false

    @Volatile
    private var lastAeEvalTimeMs: Long = 0L

    private val histogramScratch = IntArray(256)

    private val cameraOpenCloseLock = Semaphore(1)

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

    private var currentSessionDir: File? = null

    init {
        detectLenses()
    }

    fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraBackground").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
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

            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
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
                                val pChars = cameraManager.getCameraCharacteristics(pId)
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
                                val previewSz = chooseFullFramePreviewSize(pChars, chars, rawW, rawH)

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
                                        sensorOrientation = pOrientation,
                                        previewWidth = previewSz.width,
                                        previewHeight = previewSz.height
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

            val chars = cameraManager.getCameraCharacteristics(defaultBackId)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val maxAnalogSensitivity = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: 1600
            val defaultOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val defaultPreviewSize = chooseFullFramePreviewSize(chars, chars, 8160, 6120)
            val maxExp = expRange?.upper ?: 16_000_000_000L
            val minExp = expRange?.lower ?: 100_000L
            val minIso = isoRange?.lower ?: 50
            val maxIso = isoRange?.upper ?: 6400
            Log.i(TAG, "Hardware max analog sensitivity without digital boost: $maxAnalogSensitivity")

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
                previewWidth = best05?.previewWidth ?: defaultPreviewSize.width,
                previewHeight = best05?.previewHeight ?: defaultPreviewSize.height
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
                previewWidth = best10?.previewWidth ?: defaultPreviewSize.width,
                previewHeight = best10?.previewHeight ?: defaultPreviewSize.height
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
                previewWidth = best50?.previewWidth ?: defaultPreviewSize.width,
                previewHeight = best50?.previewHeight ?: defaultPreviewSize.height
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
     * Picks the highest-resolution SurfaceHolder preview stream within the Camera2 PREVIEW tier
     * (<= 1920x1080) that matches the native uncropped RAW sensor aspect ratio (typically 4:3).
     */
    private fun chooseFullFramePreviewSize(
        primaryChars: CameraCharacteristics,
        logicalChars: CameraCharacteristics,
        rawW: Int,
        rawH: Int
    ): Size {
        val targetAspect = if (rawW > 0 && rawH > 0) rawW.toFloat() / rawH.toFloat() else (4f / 3f)
        val primarySizes = primaryChars
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.view.SurfaceHolder::class.java)
            ?.toList()
            .orEmpty()
        val logicalSizes = logicalChars
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.view.SurfaceHolder::class.java)
            ?.toList()
            .orEmpty()

        // Prefer sizes supported by both physical & logical maps so fallback sessions also match
        val commonPool = if (primarySizes.isNotEmpty() && logicalSizes.isNotEmpty()) {
            primarySizes.filter { p -> logicalSizes.any { l -> l.width == p.width && l.height == p.height } }
                .ifEmpty { primarySizes }
        } else {
            primarySizes.ifEmpty { logicalSizes }
        }

        val matchingPreviewTier = commonPool.filter { sz ->
            sz.width <= 1920 &&
                sz.height <= 1080 &&
                kotlin.math.abs((sz.width.toFloat() / sz.height.toFloat()) - targetAspect) <= 0.03f
        }

        return matchingPreviewTier.maxByOrNull { it.width * it.height }
            ?: commonPool.filter {
                kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - targetAspect) <= 0.03f
            }.minByOrNull { kotlin.math.abs(it.width * it.height - 1440 * 1080) }
            ?: Size(1440, 1080)
    }

    fun selectLens(lens: CameraLens, previewSurface: Surface) {
        val previousLens = _currentLens.value
        if (previousLens?.opticalZoomRatio == lens.opticalZoomRatio &&
            previousLens.physicalCameraId == lens.physicalCameraId &&
            cameraDevice != null && captureSession != null) {
            return
        }

        _currentLens.value = lens
        _settings.value = _settings.value.copy(lens = lens)

        val device = cameraDevice
        if (device != null && previousLens?.id == lens.id) {
            try {
                captureSession?.close()
                captureSession = null
                setupRawImageReader(device.id, lens)
                setupPreviewAnalysisReader(device.id, lens)
                createCaptureSession(device, previewSurface, lens)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Seamless physical switch failed, reopening camera", e)
            }
        }

        closeCamera()
        openCamera(lens.id, previewSurface)
    }

    fun updateSettings(newSettings: CaptureSettings) {
        _settings.value = newSettings
        updatePreviewRepeating()
    }

    private fun getActiveLensCharacteristics(logicalCameraId: String, lens: CameraLens?): CameraCharacteristics {
        val pId = lens?.physicalCameraId
        return if (!pId.isNullOrEmpty()) {
            try {
                cameraManager.getCameraCharacteristics(pId)
            } catch (e: Exception) {
                cameraManager.getCameraCharacteristics(logicalCameraId)
            }
        } else {
            cameraManager.getCameraCharacteristics(logicalCameraId)
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
            3 // buffer up to 3 frames
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
        val targetArea = 320 * 240
        val smallYuv = yuvSizes?.filter { it.width in 160..640 && it.height >= 120 }
        val analysisSize = smallYuv
            ?.filter { kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - sensorAspect) <= 0.03f }
            ?.minByOrNull { kotlin.math.abs(it.width * it.height - targetArea) }
            ?: smallYuv?.minByOrNull { kotlin.math.abs(it.width * it.height - targetArea) }
            ?: yuvSizes?.minByOrNull { it.width * it.height }
            ?: Size(320, 240)

        Log.i(TAG, "Setting up Preview AE YUV ImageReader: ${analysisSize.width}x${analysisSize.height}")

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

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, surface: Surface) {
        if (cameraDevice?.id == cameraId && captureSession != null && this.previewSurface === surface) {
            return
        }
        this.previewSurface = surface
        startBackgroundThread()

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val targetLens = _currentLens.value
            setupRawImageReader(cameraId, targetLens)
            setupPreviewAnalysisReader(cameraId, targetLens)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession(camera, surface, _currentLens.value)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera open error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open camera $cameraId", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: Surface, lens: CameraLens?) {
        val reader = rawImageReader ?: return
        val analysisReader = previewAnalysisReader
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

            val cameraExecutor = java.util.concurrent.Executor { command ->
                cameraHandler?.post(command) ?: command.run()
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
                        if (cameraDevice == null) return
                        captureSession = session
                        includeAnalysisInSession = analysisConfig != null
                        Log.i(TAG, "Capture session configured successfully for ${lens?.name} (AE stream=$includeAnalysisInSession)")
                        updatePreviewRepeating()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session with physical ID $physicalId failed, falling back to standard session")
                        fallbackStandardSession(camera, surface, reader.surface)
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session with SessionConfiguration, falling back", e)
            fallbackStandardSession(camera, surface, reader.surface)
        }
    }

    private fun fallbackStandardSession(camera: CameraDevice, preview: Surface, rawSurface: Surface) {
        try {
            includeAnalysisInSession = false
            val surfaces = listOf(preview, rawSurface)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updatePreviewRepeating()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Fallback capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback createCaptureSession failed", e)
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val tagGen = request.tag as? Long
            if (tagGen != null && tagGen == previewGeneration) {
                activeGenerationLanded = true
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
            if (_isCapturing.value || !activeGenerationLanded) {
                return
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAeEvalTimeMs < 180L) {
                return
            }
            lastAeEvalTimeMs = now

            val plane = image.planes.firstOrNull() ?: return
            val stats = PreviewAutoExposure.analyzeYPlane(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                histogramScratch = histogramScratch
            ) ?: return

            val currentIdx = currentPreviewStepIndex
            val nextIdx = PreviewAutoExposure.nextStepIndex(currentIdx, stats)
            if (nextIdx != currentIdx) {
                currentPreviewStepIndex = nextIdx
                activeGenerationLanded = false
                val nextStep = PreviewAutoExposure.STEPS[nextIdx]
                Log.d(
                    TAG,
                    "Preview AE step $currentIdx -> $nextIdx (${nextStep.exposureTimeNs / 1_000_000.0}ms, ISO ${nextStep.iso}) " +
                        "[meanY=${"%.1f".format(stats.meanY)}, p90Y=${"%.1f".format(stats.p90Y)}, clip=${"%.2f".format(stats.clippedFraction)}]"
                )
                updatePreviewRepeating()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview AE frame evaluation error", e)
        } finally {
            image.close()
        }
    }

    /**
     * Sets preview parameters for live viewfinder and focus assist using closed-loop step AE
     * targeting a brightly overexposed image for easy star and nebula framing.
     */
    private fun updatePreviewRepeating() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

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
            activeGenerationLanded = false
            builder.setTag(gen)

            val current = _settings.value
            val lensChars = getActiveLensCharacteristics(device.id, current.lens)

            // Set manual focus distance (0.0f = infinity)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, current.focusDistance)

            // Step-based preview auto-exposure: intentionally overexposed so stars & nebulae pop clearly
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

            // Select physical optical camera via native optical zoom ratio (0.5x, 1.0x, 5.0x) coerced safely to device range
            val logicalChars = cameraManager.getCameraCharacteristics(device.id)
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

            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview request", e)
        }
    }

    /**
     * Executes a single still astrophotography exposure saving RAW DNG.
     */
    suspend fun captureAstrophotoFrame(
        sessionName: String,
        frameIndex: Int,
        frameType: FrameType
    ): File? = withContext(Dispatchers.IO) {
        val session = captureSession ?: return@withContext null
        val device = cameraDevice ?: return@withContext null
        val reader = rawImageReader ?: return@withContext null

        _isCapturing.value = true

        val chars = cameraManager.getCameraCharacteristics(device.id)
        val current = _settings.value
        val activeLens = current.lens

        val targetDir = getOrCreateSessionDir(sessionName)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${frameType.prefix}_${"%04d".format(frameIndex)}_${timestamp}_${current.exposureTimeNs / 1_000_000}ms_ISO${current.iso}.dng"
        val outputFile = File(targetDir, fileName)

        val resultDeferred = CompletableDeferred<File?>()

        // Setup one-shot listener for raw image
        reader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(r: ImageReader) {
                reader.setOnImageAvailableListener(null, null)
                val image = r.acquireLatestImage()
                if (image != null) {
                    saveDng(image, chars, activeLens, outputFile, resultDeferred)
                } else {
                    resultDeferred.complete(null)
                }
            }
        }, cameraHandler)

        try {
            // Pause repeating preview during long exposure
            session.stopRepeating()

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
            captureBuilder.addTarget(reader.surface)

            // Exact astrophotography manual parameters
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

            // Engage exact physical optical camera (0.5x, 1.0x, 5.0x) for RAW capture safely coerced
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val captureRatio = current.lens?.opticalZoomRatio ?: 1.0f
            if (zoomRange != null) {
                val safeRatio = captureRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                captureBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeRatio)
            }

            // Total capture result callback
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastCaptureResult = result
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Capture failed: reason ${failure.reason}")
                    resultDeferred.complete(null)
                }
            }, cameraHandler)

            // Wait for file writing to finish (or timeout based on exposure time + 10s margin)
            val timeoutSec = (current.exposureTimeNs / 1_000_000_000L) + 15L
            val file = withTimeoutOrNull(timeoutSec * 1000) {
                resultDeferred.await()
            }

            _lastCapturedFile.value = file
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error executing astro capture", e)
            null
        } finally {
            _isCapturing.value = false
            updatePreviewRepeating()
        }
    }

    private var lastCaptureResult: TotalCaptureResult? = null

    private fun saveDng(
        image: Image,
        logicalChars: CameraCharacteristics,
        lens: CameraLens?,
        outFile: File,
        deferred: CompletableDeferred<File?>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait briefly if capture result hasn't arrived yet
                var attempts = 0
                while (lastCaptureResult == null && attempts < 40) {
                    delay(50)
                    attempts++
                }

                val result = lastCaptureResult
                if (result != null) {
                    val pId = lens?.physicalCameraId
                    val dngChars = if (!pId.isNullOrEmpty()) {
                        try {
                            cameraManager.getCameraCharacteristics(pId)
                        } catch (e: Exception) {
                            logicalChars
                        }
                    } else {
                        logicalChars
                    }

                    val dngCreator = DngCreator(dngChars, result)
                    dngCreator.setDescription("Avaruusmulkko Astro Suite RAW (${lens?.name ?: "1.0x"}); Exp=${_settings.value.exposureTimeNs}ns, ISO=${_settings.value.iso}")
                    FileOutputStream(outFile).use { fos ->
                        dngCreator.writeImage(fos, image)
                    }
                    dngCreator.close()
                    Log.i(TAG, "Saved DNG: ${outFile.absolutePath} (${outFile.length() / 1024 / 1024} MB)")
                    deferred.complete(outFile)
                } else {
                    Log.e(TAG, "CaptureResult missing for DNG creation")
                    deferred.complete(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing DNG", e)
                deferred.complete(null)
            } finally {
                image.close()
                lastCaptureResult = null
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

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            previewAnalysisReader?.close()
            previewAnalysisReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }
}
