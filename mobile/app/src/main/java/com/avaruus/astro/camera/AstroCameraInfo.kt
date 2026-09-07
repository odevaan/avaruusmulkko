package com.avaruus.astro.camera

/**
 * Metadata representation of a physical or logical camera lens on the Pixel 9 Pro XL.
 */
data class CameraLens(
    val id: String,
    val name: String,
    val opticalZoom: String = "1.0x",
    val focalLengthMm: Float,
    val aperture: Float,
    val maxExposureNs: Long,
    val minExposureNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val minFocusDistance: Float, // Hyperfocal / closest focus. 0.0f = infinity
    val isRawSupported: Boolean = true,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0
) {
    val maxExposureSec: Double get() = maxExposureNs / 1_000_000_000.0
    val minExposureSec: Double get() = minExposureNs / 1_000_000_000.0
    val megaPixels: Double get() = (rawWidth.toDouble() * rawHeight.toDouble()) / 1_000_000.0
}

enum class FrameType(val prefix: String, val description: String) {
    LIGHT("LIGHT", "Light frame (normal sky exposure)"),
    DARK("DARK", "Dark frame (thermal noise & hot pixel calibration - lens covered)"),
    BIAS("BIAS", "Bias/Offset frame (read noise calibration - shortest exposure)"),
    FLAT("FLAT", "Flat field frame (vignetting & dust calibration - uniform light)")
}

enum class AstroMode(val label: String, val description: String, val frameType: FrameType?) {
    LIGHT("LIGHT", "Sky exposures (stars & deep sky)", FrameType.LIGHT),
    DARK("DARK", "Dark frames (lens covered, OLED screen blanked)", FrameType.DARK),
    BIAS("BIAS", "Bias frames (readout noise, min exposure)", FrameType.BIAS),
    FLAT("FLAT", "Flat frames (vignetting & sensor dust)", FrameType.FLAT),
    FOCUS("FOCUS", "Star focus assist (high gain & zoom loupe)", null),
    VIEW("VIEW", "Inspect captured photos (tracking & focus)", null)
}

data class ExposurePreset(
    val label: String,
    val durationNs: Long
) {
    val durationSec: Double get() = durationNs / 1_000_000_000.0
}

object AstroDefaults {
    val EXPOSURE_PRESETS = listOf(
        ExposurePreset("1/1000s", 1_000_000L),
        ExposurePreset("1/100s", 10_000_000L),
        ExposurePreset("1/10s", 100_000_000L),
        ExposurePreset("0.5s", 500_000_000L),
        ExposurePreset("1.0s", 1_000_000_000L),
        ExposurePreset("2.0s", 2_000_000_000L),
        ExposurePreset("4.0s", 4_000_000_000L),
        ExposurePreset("8.0s", 8_000_000_000L),
        ExposurePreset("10.0s", 10_000_000_000L),
        ExposurePreset("15.0s", 15_000_000_000L),
        ExposurePreset("16.0s", 16_000_000_000L),
        ExposurePreset("30.0s", 30_000_000_000L)
    )

    val ISO_PRESETS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
}

data class CaptureSettings(
    val lens: CameraLens? = null,
    val exposureTimeNs: Long = 10_000_000_000L, // Default 10.0s
    val iso: Int = 1600,
    val focusDistance: Float = 0.0f, // 0.0f = Infinity in Camera2
    val mode: AstroMode = AstroMode.LIGHT,
    val zoomFactor: Float = 1.0f // Preview zoom loupe
)
