package com.avaruus.astro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
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
    private var previewSurface: Surface? = null

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
     * Identifies Ultrawide (0.5x), Main Wide (1x), and Telephoto (5x).
     */
    fun detectLenses() {
        val lenses = mutableListOf<CameraLens>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

                    val maxExp = expRange?.upper ?: 16_000_000_000L
                    val minExp = expRange?.lower ?: 100_000L
                    val minIso = isoRange?.lower ?: 50
                    val maxIso = isoRange?.upper ?: 6400

                    val physicalIds = chars.physicalCameraIds
                    val physicalSensorsFound = mutableListOf<CameraLens>()

                    if (physicalIds.isNotEmpty()) {
                        for (pId in physicalIds) {
                            try {
                                val pChars = cameraManager.getCameraCharacteristics(pId)
                                val focalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val focalLength = focalLengths?.firstOrNull() ?: 5.0f
                                val apertures = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                                val aperture = apertures?.firstOrNull() ?: 1.8f
                                val minFocus = pChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: minFocusDist

                                val (zoomRatio, zoomStr, lensLabel) = when {
                                    focalLength < 3.5f -> Triple(0.5f, "0.5x", "0.5x Ultra-Wide (48MP ƒ/${"%.1f".format(aperture)})")
                                    focalLength in 3.5f..10.0f -> Triple(1.0f, "1.0x", "1.0x Main Wide (50MP ƒ/${"%.2f".format(aperture)})")
                                    else -> Triple(5.0f, "5.0x", "5.0x Telephoto (48MP ƒ/${"%.1f".format(aperture)})")
                                }

                                physicalSensorsFound.add(
                                    CameraLens(
                                        id = id,
                                        physicalCameraId = pId,
                                        name = lensLabel,
                                        opticalZoomRatio = zoomRatio,
                                        opticalZoom = zoomStr,
                                        focalLengthMm = focalLength,
                                        aperture = aperture,
                                        maxExposureNs = maxExp,
                                        minExposureNs = minExp,
                                        minIso = minIso,
                                        maxIso = maxIso,
                                        minFocusDistance = minFocus,
                                        isRawSupported = true
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading physical camera $pId", e)
                            }
                        }
                    }

                    if (physicalSensorsFound.size >= 2) {
                        lenses.addAll(physicalSensorsFound.sortedBy { it.opticalZoomRatio })
                    } else {
                        // Pixel 9 Pro XL Optical Triplet guarantee:
                        // 0.5x Ultra-Wide (48 MP Sony IMX858, ƒ/1.7)
                        // 1.0x Main Wide (50 MP Samsung GNK, ƒ/1.68)
                        // 5.0x Telephoto (48 MP Sony IMX858 periscope, ƒ/2.8)
                        val pixel9OpticalLenses = listOf(
                            CameraLens(
                                id = id,
                                name = "0.5x Ultra-Wide (48MP ƒ/1.7)",
                                opticalZoomRatio = 0.5f,
                                opticalZoom = "0.5x",
                                focalLengthMm = 2.1f,
                                aperture = 1.7f,
                                maxExposureNs = maxExp,
                                minExposureNs = minExp,
                                minIso = minIso,
                                maxIso = maxIso,
                                minFocusDistance = 10.0f
                            ),
                            CameraLens(
                                id = id,
                                name = "1.0x Main Wide (50MP ƒ/1.68)",
                                opticalZoomRatio = 1.0f,
                                opticalZoom = "1.0x",
                                focalLengthMm = 6.9f,
                                aperture = 1.68f,
                                maxExposureNs = maxExp,
                                minExposureNs = minExp,
                                minIso = minIso,
                                maxIso = maxIso,
                                minFocusDistance = 10.0f
                            ),
                            CameraLens(
                                id = id,
                                name = "5.0x Telephoto (48MP ƒ/2.8)",
                                opticalZoomRatio = 5.0f,
                                opticalZoom = "5.0x",
                                focalLengthMm = 19.0f,
                                aperture = 2.8f,
                                maxExposureNs = maxExp,
                                minExposureNs = minExp,
                                minIso = minIso,
                                maxIso = maxIso,
                                minFocusDistance = 5.0f
                            )
                        )
                        lenses.addAll(pixel9OpticalLenses)
                    }
                    break
                }
            }

            _availableLenses.value = lenses
            if (_currentLens.value == null && lenses.isNotEmpty()) {
                val defaultLens = lenses.find { it.opticalZoomRatio == 1.0f } ?: lenses.first()
                _currentLens.value = defaultLens
                _settings.value = _settings.value.copy(lens = defaultLens)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras", e)
        }
    }

    fun selectLens(lens: CameraLens, previewSurface: Surface) {
        val previousLens = _currentLens.value
        _currentLens.value = lens
        _settings.value = _settings.value.copy(lens = lens)

        if (previousLens?.id == lens.id && cameraDevice != null && captureSession != null) {
            // Optical zoom ratio switch on the same logical camera device:
            // Hardware switches sensors seamlessly without restarting camera
            updatePreviewRepeating()
        } else {
            closeCamera()
            openCamera(lens.id, previewSurface)
        }
    }

    fun updateSettings(newSettings: CaptureSettings) {
        _settings.value = newSettings
        updatePreviewRepeating()
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, surface: Surface) {
        this.previewSurface = surface
        startBackgroundThread()

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Setup RAW_SENSOR ImageReader with largest resolution
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = rawSizes?.maxByOrNull { it.width * it.height } ?: Size(4080, 3072)

            rawImageReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                3 // buffer up to 3 frames
            )

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession(camera, surface, rawImageReader!!.surface)
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

    private fun createCaptureSession(camera: CameraDevice, preview: Surface, rawSurface: Surface) {
        try {
            val surfaces = listOf(preview, rawSurface)
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updatePreviewRepeating()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    /**
     * Sets preview parameters for live viewfinder and focus assist.
     */
    private fun updatePreviewRepeating() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            val current = _settings.value

            // Set manual focus distance (0.0f = infinity)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, current.focusDistance)

            // For preview, we use auto or high-gain manual so the user can see stars in viewfinder
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            // Preview exposure limited to 1/10s (100ms) for responsive 10fps preview
            val previewExp = minOf(current.exposureTimeNs, 100_000_000L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, maxOf(current.iso, 3200)) // boosted preview gain

            // OIS off for astronomy on mount/tripod
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

            // Select physical optical camera via native optical zoom ratio (0.5x, 1.0x, 5.0x)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, current.lens?.opticalZoomRatio ?: 1.0f)

            // Digital zoom loupe preview if set
            if (current.zoomFactor > 1.0f) {
                val chars = cameraManager.getCameraCharacteristics(device.id)
                val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (sensorRect != null) {
                    val cropW = (sensorRect.width() / current.zoomFactor).toInt()
                    val cropH = (sensorRect.height() / current.zoomFactor).toInt()
                    val cropX = sensorRect.centerX() - cropW / 2
                    val cropY = sensorRect.centerY() - cropH / 2
                    val cropRect = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }

            session.setRepeatingRequest(builder.build(), null, cameraHandler)
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
                    saveDng(image, chars, outputFile, resultDeferred)
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

            // Engage exact physical optical camera (0.5x, 1.0x, 5.0x) for RAW capture
            captureBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, current.lens?.opticalZoomRatio ?: 1.0f)

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
        chars: CameraCharacteristics,
        outFile: File,
        deferred: CompletableDeferred<File?>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait briefly if capture result hasn't arrived yet
                var attempts = 0
                while (lastCaptureResult == null && attempts < 30) {
                    delay(50)
                    attempts++
                }

                val result = lastCaptureResult
                if (result != null) {
                    val dngCreator = DngCreator(chars, result)
                    dngCreator.setDescription("Avaruusmulkko Astro Suite RAW; Exp=${_settings.value.exposureTimeNs}ns, ISO=${_settings.value.iso}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }
}
