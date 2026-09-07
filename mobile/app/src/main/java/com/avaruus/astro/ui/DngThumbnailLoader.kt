package com.avaruus.astro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

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
            val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)

            rHist[r]++
            gHist[g]++
            bHist[b]++
            lHist[lum]++
        }

        var maxC = 1
        for (i in 1..254) {
            if (lHist[i] > maxC) maxC = lHist[i]
        }

        return HistogramData(rHist, gHist, bHist, lHist, maxC)
    }

    /**
     * Astrophotography MTF Screen Transfer AutoStretch (PixInsight STF equivalent).
     * Stretches the dark linear starlight and nebulae data to inspect framing and tracking.
     */
    fun createAutoStretchedBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lHist = IntArray(256)
        val step = max(1, totalPixels / 100_000)
        var sampled = 0
        for (i in 0 until totalPixels step step) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
            lHist[lum]++
            sampled++
        }

        var accum = 0
        var shadowClip = 0
        var median = 20
        for (i in 0..255) {
            accum += lHist[i]
            if (accum >= sampled * 0.01 && shadowClip == 0) {
                shadowClip = i
            }
            if (accum >= sampled * 0.5) {
                median = i
                break
            }
        }

        // MTF midtone transfer curve
        val c = ((median - shadowClip).toFloat() / (255 - shadowClip).coerceAtLeast(1)).coerceIn(0.001f, 0.999f)
        val b0 = 0.22f // Target background brightness
        val m = ((c * (1f - b0)) / (c * (1f - 2f * b0) + b0)).coerceIn(0.01f, 0.99f)

        val lut = IntArray(256)
        for (i in 0..255) {
            if (i <= shadowClip) {
                lut[i] = 0
            } else {
                val x = ((i - shadowClip).toFloat() / (255 - shadowClip)).coerceIn(0f, 1f)
                val y = ((m - 1f) * x) / ((2f * m - 1f) * x - m)
                lut[i] = (y.coerceIn(0f, 1f) * 255f).toInt()
            }
        }

        val outPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val a = p and -0x1000000
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            outPixels[i] = a or (r shl 16) or (g shl 8) or b
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        return outBmp
    }
}
