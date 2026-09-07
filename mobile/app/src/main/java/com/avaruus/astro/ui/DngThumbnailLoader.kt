package com.avaruus.astro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DngThumbnailLoader"

data class DngMetadata(
    val file: File,
    val exposureTimeStr: String,
    val isoStr: String,
    val dateStr: String,
    val dimensions: String,
    val sizeMb: Double
)

object DngThumbnailLoader {

    suspend fun loadBitmap(file: File, maxDim: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val exif = ExifInterface(file.absolutePath)
            // 1. Try embedded thumbnail if large enough
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
                // Fallback attempt with standard decoding
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
}
