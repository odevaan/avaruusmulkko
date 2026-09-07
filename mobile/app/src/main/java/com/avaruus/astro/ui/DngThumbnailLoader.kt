package com.avaruus.astro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "DngThumbnailLoader"

data class DngMetadata(
    val file: File,
    val exposureTimeStr: String,
    val isoStr: String,
    val dateStr: String,
    val dimensions: String,
    val sizeMb: Double
)

data class HistogramData(
    val red: IntArray = IntArray(256),
    val green: IntArray = IntArray(256),
    val blue: IntArray = IntArray(256),
    val lum: IntArray = IntArray(256),
    val maxCount: Int = 1
)

enum class AstroStretchMode(val label: String, val shortLabel: String, val description: String) {
    ARCSINH("ARCSINH", "ARCSINH", "Universal Astrophotography Stretch: Arcsinh with Highlight Protection & True Star Colors"),
    BOOST("BOOST", "BOOST", "Deep Boost: High-Gain Stretch for Faint Nebulae & Dust"),
    OFF("LINEAR", "OFF", "Linear Unmodified Raw Sensor Data")
}

object DngThumbnailLoader {

    suspend fun loadBitmap(file: File, maxDim: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val exif = ExifInterface(file.absolutePath)
            // 1. Try embedded thumbnail if high resolution
            if (exif.hasThumbnail()) {
                val thumbBytes = exif.thumbnailBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false }
                    val bmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size, opts)
                    if (bmp != null && bmp.width >= 1000) {
                        return@withContext bmp
                    }
                }
            }

            // 2. Decode file with inSampleSize
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)

            val rawW = boundsOpts.outWidth
            val rawH = boundsOpts.outHeight
            if (rawW <= 0 || rawH <= 0) {
                return@withContext BitmapFactory.decodeFile(file.absolutePath)
            }

            var sampleSize = 1
            while (rawW / sampleSize > maxDim || rawH / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return@withContext BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap for ${file.name}", e)
            null
        }
    }

    fun extractMetadata(file: File): DngMetadata {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "-"
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) ?: exif.getAttribute("PhotographicSensitivity") ?: "-"
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "-"
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val sizeMb = file.length() / (1024.0 * 1024.0)

            val expFmt = try {
                val expVal = exp.toDouble()
                if (expVal >= 1.0) "${"%.1f".format(expVal)}s" else "1/${(1.0 / expVal).toInt()}s"
            } catch (e: Exception) {
                exp
            }

            DngMetadata(
                file = file,
                exposureTimeStr = expFmt,
                isoStr = "ISO $iso",
                dateStr = date,
                dimensions = if (w > 0 && h > 0) "${w}x${h} (~${"%.0f".format(w * h / 1_000_000.0)}MP)" else "RAW",
                sizeMb = sizeMb
            )
        } catch (e: Exception) {
            DngMetadata(file, "-", "-", "-", "-", file.length() / (1024.0 * 1024.0))
        }
    }

    /**
     * Computes 256-bin RGB and Luminance histograms for the bitmap.
     */
    fun computeHistogram(bmp: Bitmap): HistogramData {
        val width = bmp.width
        val height = bmp.height
        val totalPixels = width * height
        val step = max(1, totalPixels / 100_000)
        val pixels = IntArray(totalPixels)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        val rHist = IntArray(256)
        val gHist = IntArray(256)
        val bHist = IntArray(256)
        val lHist = IntArray(256)

        for (i in 0 until totalPixels step step) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (218 * r + 732 * g + 74 * b) shr 10

            rHist[r]++
            gHist[g]++
            bHist[b]++
            lHist[lum.coerceIn(0, 255)]++
        }

        var maxC = 1
        for (i in 1..254) {
            if (lHist[i] > maxC) maxC = lHist[i]
        }

        return HistogramData(rHist, gHist, bHist, lHist, maxC)
    }

    /**
     * Universal Astrophotography Stretch:
     * - Inverse Hyperbolic Sine (Arcsinh) non-linear expansion in the deep shadows (brings out faint nebulae & starlight)
     * - Filmic soft-shoulder highlight rolloff (prevents star cores and bright galaxy centers from burning out)
     * - Lupton Chrominance Scaling (preserves true RGB star and nebula colors without desaturating to white)
     * - Adaptive sky background noise floor estimation (zero black clipping, clean dark contrast)
     */
    fun createStretchedBitmap(src: Bitmap, mode: AstroStretchMode = AstroStretchMode.ARCSINH): Bitmap {
        if (mode == AstroStretchMode.OFF) return src

        val w = src.width
        val h = src.height
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Compute robust luminance distribution from representative samples
        val lHist = IntArray(256)
        val step = max(1, totalPixels / 100_000)
        var sampled = 0
        for (i in 0 until totalPixels step step) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (218 * r + 732 * g + 74 * b) shr 10
            lHist[lum.coerceIn(0, 255)]++
            sampled++
        }

        // 2. Find median (sky background) and noise dispersion (16th and 84th percentiles)
        var accum = 0
        var p16 = 5
        var median = 15
        var p84 = 25
        for (i in 0..255) {
            accum += lHist[i]
            if (accum >= sampled * 0.16 && p16 == 5) p16 = i
            if (accum >= sampled * 0.50 && median == 15) median = i
            if (accum >= sampled * 0.84) {
                p84 = i
                break
            }
        }

        val sigma = max(1.0, (p84 - p16) / 2.0)
        // Black point safely below the sky background noise floor (no clipping of faint starlight)
        val cBlack = max(0.0, median - 2.5 * sigma)
        val skyBg = max(cBlack + 1.0, median.toDouble())

        // 3. Stretch parameters based on selected mode
        val stretchFactor = if (mode == AstroStretchMode.BOOST) 800.0 else 250.0
        val targetBg = if (mode == AstroStretchMode.BOOST) 0.15 else 0.11 // Clean, dark sky
        val highlightAlpha = 0.25 // Soft-shoulder compression to prevent highlight blowout

        val normDenom = max(1.0, 255.0 - cBlack)
        val asinhMax = asinh(stretchFactor)

        // Calculate gamma exponent to anchor sky background cleanly
        val skyNorm = (skyBg - cBlack) / normDenom
        val skyAsinh = asinh(stretchFactor * skyNorm) / asinhMax
        val gamma = if (skyAsinh in 0.001..0.999) {
            ln(targetBg) / ln(skyAsinh)
        } else {
            1.0
        }

        // 4. Precompute 256-entry Luminance Lookup Table (LUT) with soft-shoulder rolloff
        val scaleLut = FloatArray(256)

        for (l in 0..255) {
            if (l <= cBlack) {
                scaleLut[l] = 0f
            } else {
                val x = (l - cBlack) / normDenom
                val yAsinh = asinh(stretchFactor * x) / asinhMax
                val yAnchored = yAsinh.pow(gamma)
                // Filmic soft-shoulder compression: prevents star cores and bright nebulae from burning out
                val yRolloff = (yAnchored / (1.0 + highlightAlpha * yAnchored)) * (1.0 + highlightAlpha)
                val finalLum = (yRolloff.coerceIn(0.0, 1.0) * 255.0).toFloat()
                scaleLut[l] = finalLum / max(1f, l.toFloat())
            }
        }

        // 5. Apply Lupton Chrominance Scaling to every pixel:
        // Multiplying each color channel (R, G, B) by the exact same luminance scale factor
        // guarantees 100% color fidelity (stars stay blue/orange, emission stays deep red)
        val outPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val a = p and -0x1000000
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val lum = (218 * r + 732 * g + 74 * b) shr 10
            val scale = scaleLut[lum.coerceIn(0, 255)]

            var rOut = r * scale
            var gOut = g * scale
            var bOut = b * scale

            // If a star core saturates, gently roll off peak without color artifact
            val maxC = max(rOut, max(gOut, bOut))
            if (maxC > 255f) {
                val normScale = 255f / maxC
                rOut *= normScale
                gOut *= normScale
                bOut *= normScale
            }

            outPixels[i] = a or
                    (rOut.toInt().coerceIn(0, 255) shl 16) or
                    (gOut.toInt().coerceIn(0, 255) shl 8) or
                    bOut.toInt().coerceIn(0, 255)
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        return outBmp
    }

    /** Legacy alias */
    fun createAutoStretchedBitmap(src: Bitmap): Bitmap =
        createStretchedBitmap(src, AstroStretchMode.ARCSINH)

    private fun asinh(x: Double): Double = ln(x + sqrt(x * x + 1.0))
}
