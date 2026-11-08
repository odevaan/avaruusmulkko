package com.avaruus.astro.camera

import java.nio.ByteBuffer

/**
 * Closed-loop step-based auto-exposure controller for the live astrophotography viewfinder.
 *
 * Evaluates the luminance (Y plane) of the most recently completed preview frame and adjusts
 * the preview exposure up or down by at most ONE discrete step at a time, intentionally targeting
 * an overexposed framing brightness so faint stars, nebulae, and horizon landmarks stand out clearly.
 *
 * Note: This affects ONLY the live preview stream. Still RAW DNG captures always use the exact
 * manual exposure time and ISO selected by the user.
 */
object PreviewAutoExposure {

    data class PreviewStep(
        val exposureTimeNs: Long,
        val iso: Int
    )

    data class FrameLuminanceStats(
        val meanY: Float,
        val p90Y: Float,
        val clippedFraction: Float
    ) {
        /**
         * Combines overall background sky/scene luminance (60%) with bright structure/star-field
         * 90th-percentile luminance (40%) on a 0..255 scale.
         */
        val effectiveBrightness: Float
            get() = 0.60f * meanY + 0.40f * p90Y
    }

    /**
     * Monotonically increasing preview exposure ladder (~0.65 EV / ~1.58x per step).
     * Across the twilight and night-sky range (8ms to 1.6s), ISO stays at 3200 while exposure
     * duration steps smoothly; top steps boost up to 2.5s @ ISO 6400 for dark skies / f/2.8 tele,
     * and bottom steps lower shutter + ISO to handle bright indoor or lunar scenes without whiteout.
     */
    val STEPS: List<PreviewStep> = listOf(
        PreviewStep(100_000L, 100),         // 0:  0.1 ms, ISO 100
        PreviewStep(200_000L, 100),         // 1:  0.2 ms, ISO 100
        PreviewStep(400_000L, 100),         // 2:  0.4 ms, ISO 100
        PreviewStep(500_000L, 200),         // 3:  0.5 ms, ISO 200
        PreviewStep(800_000L, 200),         // 4:  0.8 ms, ISO 200
        PreviewStep(1_000_000L, 400),       // 5:  1.0 ms, ISO 400
        PreviewStep(1_600_000L, 400),       // 6:  1.6 ms, ISO 400
        PreviewStep(2_000_000L, 800),       // 7:  2.0 ms, ISO 800
        PreviewStep(3_200_000L, 800),       // 8:  3.2 ms, ISO 800
        PreviewStep(4_000_000L, 1600),      // 9:  4.0 ms, ISO 1600
        PreviewStep(6_300_000L, 1600),      // 10: 6.3 ms, ISO 1600
        PreviewStep(8_000_000L, 3200),      // 11: 8.0 ms, ISO 3200
        PreviewStep(12_500_000L, 3200),     // 12: 12.5 ms, ISO 3200
        PreviewStep(20_000_000L, 3200),     // 13: 20.0 ms, ISO 3200
        PreviewStep(33_333_333L, 3200),     // 14: 33.3 ms, ISO 3200 (30 fps)
        PreviewStep(50_000_000L, 3200),     // 15: 50.0 ms, ISO 3200 (20 fps)
        PreviewStep(80_000_000L, 3200),     // 16: 80.0 ms, ISO 3200 (12.5 fps)
        PreviewStep(100_000_000L, 3200),    // 17: 100.0 ms, ISO 3200 (10 fps - initial step)
        PreviewStep(160_000_000L, 3200),    // 18: 160.0 ms, ISO 3200
        PreviewStep(250_000_000L, 3200),    // 19: 250.0 ms, ISO 3200
        PreviewStep(400_000_000L, 3200),    // 20: 400.0 ms, ISO 3200
        PreviewStep(650_000_000L, 3200),    // 21: 650.0 ms, ISO 3200
        PreviewStep(1_000_000_000L, 3200),  // 22: 1.00 s, ISO 3200
        PreviewStep(1_600_000_000L, 3200),  // 23: 1.60 s, ISO 3200
        PreviewStep(2_000_000_000L, 4800),  // 24: 2.00 s, ISO 4800
        PreviewStep(2_500_000_000L, 6400)   // 25: 2.50 s, ISO 6400
    )

    const val DEFAULT_STEP_INDEX = 17 // Starts at 100 ms @ ISO 3200

    // Intentionally overexposed target band (0..255) so dim stars and nebulae pop for easy framing.
    // The 1.50x deadband width exceeds a single ~1.23x gamma step to prevent hunting/oscillation.
    const val TARGET_BRIGHTNESS_LOW = 110f
    const val TARGET_BRIGHTNESS_HIGH = 165f

    // Highlight clipping hysteresis (fraction of pixels with Y >= 245)
    const val MAX_CLIP_FOR_STEP_UP = 0.22f
    const val FORCE_STEP_DOWN_CLIP = 0.35f

    /**
     * Adjusts [currentStepIndex] by at most +1 or -1 step based on [stats] from the previous frame.
     */
    fun nextStepIndex(currentStepIndex: Int, stats: FrameLuminanceStats): Int {
        val safeCurrent = currentStepIndex.coerceIn(0, STEPS.lastIndex)
        val brightness = stats.effectiveBrightness

        val candidate = when {
            brightness > TARGET_BRIGHTNESS_HIGH || stats.clippedFraction > FORCE_STEP_DOWN_CLIP ->
                safeCurrent - 1
            brightness < TARGET_BRIGHTNESS_LOW && stats.clippedFraction < MAX_CLIP_FOR_STEP_UP ->
                safeCurrent + 1
            else ->
                safeCurrent
        }
        return candidate.coerceIn(0, STEPS.lastIndex)
    }

    /**
     * Subsamples the 8-bit Y (luminance) plane of a YUV_420_888 preview frame into a 256-bin
     * histogram without heap allocations.
     */
    fun analyzeYPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        histogramScratch: IntArray = IntArray(256)
    ): FrameLuminanceStats? {
        if (width <= 0 || height <= 0 || histogramScratch.size < 256) return null
        histogramScratch.fill(0)

        val limit = buffer.limit()
        var sumY = 0L
        var count = 0
        val sampleStep = 2

        var y = 0
        while (y < height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < width) {
                val idx = rowOffset + x * pixelStride
                if (idx in 0 until limit) {
                    val luma = buffer.get(idx).toInt() and 0xFF
                    histogramScratch[luma]++
                    sumY += luma
                    count++
                }
                x += sampleStep
            }
            y += sampleStep
        }

        if (count == 0) return null

        val meanY = sumY.toFloat() / count.toFloat()

        val top10Target = (count * 0.10f).toInt().coerceAtLeast(1)
        var accTop = 0
        var p90Y = 0
        for (v in 255 downTo 0) {
            accTop += histogramScratch[v]
            if (accTop >= top10Target) {
                p90Y = v
                break
            }
        }

        var clippedCount = 0
        for (v in 245..255) {
            clippedCount += histogramScratch[v]
        }
        val clippedFraction = clippedCount.toFloat() / count.toFloat()

        return FrameLuminanceStats(
            meanY = meanY,
            p90Y = p90Y.toFloat(),
            clippedFraction = clippedFraction
        )
    }
}
