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
        val iso: Int,
        val binFactor: Int = 1
    ) {
        /**
         * Relative total brightness weight combining exposure duration, sensor ISO, and N×N additive pixel binning (N²).
         */
        val totalExposureWeight: Double
            get() = exposureTimeNs.toDouble() * iso.toDouble() * (binFactor * binFactor).toDouble()
    }

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

    data class BinnedFrameResult(
        val outWidth: Int,
        val outHeight: Int,
        val binFactor: Int,
        val stats: FrameLuminanceStats,
        val processingTimeMs: Double
    )

    /**
     * Reusable scratch buffers for zero-allocation YUV_420_888 binning and histogram analysis.
     */
    class BinningWorkspace {
        var yBytes = ByteArray(0)
        var uBytes = ByteArray(0)
        var vBytes = ByteArray(0)
        var outArgb = IntArray(0)
        val histogram = IntArray(256)

        fun ensureCapacity(ySize: Int, uSize: Int, vSize: Int, outPixelCount: Int) {
            if (yBytes.size < ySize) yBytes = ByteArray(ySize)
            if (uBytes.size < uSize) uBytes = ByteArray(uSize)
            if (vBytes.size < vSize) vBytes = ByteArray(vSize)
            if (outArgb.size < outPixelCount) outArgb = IntArray(outPixelCount)
        }
    }

    /**
     * Monotonically increasing preview brightness ladder.
     *
     * Preview exposure duration NEVER exceeds 100 ms (10 fps). For dark-sky framing (steps 18..21),
     * brightness is boosted via 2x2 (4x), 3x3 (9x), and 4x4 (16x) additive pixel binning instead
     * of stretching exposure length, keeping the viewfinder at a responsive 10 fps at all times.
     */
    val STEPS: List<PreviewStep> = listOf(
        PreviewStep(100_000L, 100, 1),         // 0:  0.1 ms, ISO 100, 1x1
        PreviewStep(200_000L, 100, 1),         // 1:  0.2 ms, ISO 100, 1x1
        PreviewStep(400_000L, 100, 1),         // 2:  0.4 ms, ISO 100, 1x1
        PreviewStep(500_000L, 200, 1),         // 3:  0.5 ms, ISO 200, 1x1
        PreviewStep(800_000L, 200, 1),         // 4:  0.8 ms, ISO 200, 1x1
        PreviewStep(1_000_000L, 400, 1),       // 5:  1.0 ms, ISO 400, 1x1
        PreviewStep(1_600_000L, 400, 1),       // 6:  1.6 ms, ISO 400, 1x1
        PreviewStep(2_000_000L, 800, 1),       // 7:  2.0 ms, ISO 800, 1x1
        PreviewStep(3_200_000L, 800, 1),       // 8:  3.2 ms, ISO 800, 1x1
        PreviewStep(4_000_000L, 1600, 1),      // 9:  4.0 ms, ISO 1600, 1x1
        PreviewStep(6_300_000L, 1600, 1),      // 10: 6.3 ms, ISO 1600, 1x1
        PreviewStep(8_000_000L, 3200, 1),      // 11: 8.0 ms, ISO 3200, 1x1
        PreviewStep(12_500_000L, 3200, 1),     // 12: 12.5 ms, ISO 3200, 1x1
        PreviewStep(20_000_000L, 3200, 1),     // 13: 20.0 ms, ISO 3200, 1x1
        PreviewStep(33_333_333L, 3200, 1),     // 14: 33.3 ms, ISO 3200, 1x1 (30 fps)
        PreviewStep(50_000_000L, 3200, 1),     // 15: 50.0 ms, ISO 3200, 1x1 (20 fps)
        PreviewStep(80_000_000L, 3200, 1),     // 16: 80.0 ms, ISO 3200, 1x1 (12.5 fps)
        PreviewStep(100_000_000L, 3200, 1),    // 17: 100.0 ms, ISO 3200, 1x1 (10 fps - initial step)
        PreviewStep(100_000_000L, 3200, 2),    // 18: 100.0 ms, ISO 3200, 2x2 bin (4x brightness, 10 fps)
        PreviewStep(100_000_000L, 3200, 3),    // 19: 100.0 ms, ISO 3200, 3x3 bin (9x brightness, 10 fps)
        PreviewStep(100_000_000L, 3200, 4),    // 20: 100.0 ms, ISO 3200, 4x4 bin (16x brightness, 10 fps)
        PreviewStep(100_000_000L, 6400, 4)     // 21: 100.0 ms, ISO 6400, 4x4 bin (32x brightness, 10 fps)
    )

    const val DEFAULT_STEP_INDEX = 17 // Starts at 100 ms @ ISO 3200 (1x1)
    const val LONG_EXPOSURE_CHAIN_THRESHOLD_NS = 160_000_000L

    // Intentionally overexposed target band (0..255) so dim stars and nebulae pop for easy framing.
    const val TARGET_BRIGHTNESS_LOW = 95f
    const val TARGET_BRIGHTNESS_HIGH = 180f

    // Highlight clipping hysteresis (fraction of pixels with Y >= 245)
    const val MAX_CLIP_FOR_STEP_UP = 0.22f
    const val FORCE_STEP_DOWN_CLIP = 0.38f

    /**
     * Adjusts [currentStepIndex] by at most +1 or -1 step based on [stats] from the previous frame.
     * Scales the step-up threshold inversely by the next step's gain ratio so stepping across
     * 1x1 -> 2x2 (4x), 2x2 -> 3x3 (2.25x), or 3x3 -> 4x4 (1.78x) never overshoots TARGET_BRIGHTNESS_HIGH.
     */
    fun nextStepIndex(currentStepIndex: Int, stats: FrameLuminanceStats): Int {
        val safeCurrent = currentStepIndex.coerceIn(0, STEPS.lastIndex)
        val brightness = stats.effectiveBrightness

        val stepUpThreshold = if (safeCurrent < STEPS.lastIndex) {
            val ratioUp = (STEPS[safeCurrent + 1].totalExposureWeight / STEPS[safeCurrent].totalExposureWeight)
                .toFloat()
                .coerceAtLeast(1.0f)
            minOf(TARGET_BRIGHTNESS_LOW, (TARGET_BRIGHTNESS_HIGH * 0.85f) / ratioUp)
        } else {
            TARGET_BRIGHTNESS_LOW
        }

        val candidate = when {
            brightness > TARGET_BRIGHTNESS_HIGH || stats.clippedFraction > FORCE_STEP_DOWN_CLIP ->
                safeCurrent - 1
            brightness < stepUpThreshold && stats.clippedFraction < MAX_CLIP_FOR_STEP_UP ->
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
        return computeStatsFromHistogram(histogramScratch, sumY, count)
    }

    /**
     * Performs additive N×N spatial pixel binning (N = 1, 2, 3, or 4) on a YUV_420_888 preview frame,
     * summing each N×N block of luminance values (4x boost for 2x2, 9x for 3x3, 16x for 4x4),
     * writing upright rotated ARGB_8888 pixels into [workspace.outArgb], and computing the binned
     * luminance histogram in a single pass.
     */
    fun binYuvFrameToArgb(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer?,
        vBuffer: ByteBuffer?,
        width: Int,
        height: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        binFactor: Int,
        rotationDeg: Int = 90,
        workspace: BinningWorkspace = BinningWorkspace()
    ): BinnedFrameResult? {
        val b = binFactor.coerceIn(1, 4)
        val binW = width / b
        val binH = height / b
        if (binW <= 0 || binH <= 0) return null

        val startNs = System.nanoTime()
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer?.limit() ?: 0
        val vLimit = vBuffer?.limit() ?: 0
        val outPixelCount = binW * binH

        workspace.ensureCapacity(yLimit, uLimit, vLimit, outPixelCount)
        val yBytes = workspace.yBytes
        val uBytes = workspace.uBytes
        val vBytes = workspace.vBytes
        val outArgb = workspace.outArgb
        val hist = workspace.histogram
        hist.fill(0)

        val yPos = yBuffer.position()
        yBuffer.get(yBytes, 0, yLimit - yPos)
        yBuffer.position(yPos)

        val hasChroma = uBuffer != null && vBuffer != null && uLimit > 0 && vLimit > 0
        if (hasChroma) {
            val uPos = uBuffer!!.position()
            uBuffer.get(uBytes, 0, uLimit - uPos)
            uBuffer.position(uPos)

            val vPos = vBuffer!!.position()
            vBuffer.get(vBytes, 0, vLimit - vPos)
            vBuffer.position(vPos)
        }

        val normRot = ((rotationDeg % 360) + 360) % 360
        val outW = if (normRot == 90 || normRot == 270) binH else binW
        val outH = if (normRot == 90 || normRot == 270) binW else binH

        var sumY = 0L

        for (by in 0 until binH) {
            val y0 = by * b
            val uvRowOffset = (y0 shr 1) * uvRowStride

            for (bx in 0 until binW) {
                val x0 = bx * b
                var blockSum = 0

                if (yPixelStride == 1) {
                    var dy = 0
                    while (dy < b) {
                        val rowBase = (y0 + dy) * yRowStride + x0
                        var dx = 0
                        while (dx < b) {
                            blockSum += yBytes[rowBase + dx].toInt() and 0xFF
                            dx++
                        }
                        dy++
                    }
                } else {
                    var dy = 0
                    while (dy < b) {
                        val rowBase = (y0 + dy) * yRowStride
                        var dx = 0
                        while (dx < b) {
                            blockSum += yBytes[rowBase + (x0 + dx) * yPixelStride].toInt() and 0xFF
                            dx++
                        }
                        dy++
                    }
                }

                val yVal = if (blockSum > 255) 255 else blockSum
                hist[yVal]++
                sumY += yVal

                val argb = if (hasChroma && yVal < 252) {
                    val uvIdx = uvRowOffset + (x0 shr 1) * uvPixelStride
                    val uRaw = if (uvIdx < uLimit) (uBytes[uvIdx].toInt() and 0xFF) - 128 else 0
                    val vRaw = if (uvIdx < vLimit) (vBytes[uvIdx].toInt() and 0xFF) - 128 else 0
                    val uScaled = (uRaw * b).coerceIn(-128, 127)
                    val vScaled = (vRaw * b).coerceIn(-128, 127)
                    val r = (yVal + ((1436 * vScaled) shr 10)).coerceIn(0, 255)
                    val g = (yVal - ((352 * uScaled + 731 * vScaled) shr 10)).coerceIn(0, 255)
                    val bl = (yVal + ((1815 * uScaled) shr 10)).coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                } else {
                    (0xFF shl 24) or (yVal shl 16) or (yVal shl 8) or yVal
                }

                val outIdx = when (normRot) {
                    90 -> bx * outW + (binH - 1 - by)
                    180 -> (binH - 1 - by) * outW + (binW - 1 - bx)
                    270 -> (binW - 1 - bx) * outW + by
                    else -> by * outW + bx
                }
                outArgb[outIdx] = argb
            }
        }

        val stats = computeStatsFromHistogram(hist, sumY, outPixelCount)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        return BinnedFrameResult(
            outWidth = outW,
            outHeight = outH,
            binFactor = b,
            stats = stats,
            processingTimeMs = elapsedMs
        )
    }

    private fun computeStatsFromHistogram(
        histogramScratch: IntArray,
        sumY: Long,
        count: Int
    ): FrameLuminanceStats {
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
