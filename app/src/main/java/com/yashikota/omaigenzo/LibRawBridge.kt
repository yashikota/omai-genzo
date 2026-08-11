package com.yashikota.omaigenzo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ExifInfo(
    val make: String = "",
    val model: String = "",
    val iso: Float = 0f,
    val shutter: Float = 0f,
    val aperture: Float = 0f,
    val focal: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val flip: Int = 0,
)

class LibRawBridge {

    companion object {
        private const val TAG = "LibRawBridge"

        init {
            try {
                System.loadLibrary("native-lib")
                Log.i(TAG, "Native library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    external fun getLibRawVersion(): String
    external fun getMetadata(filePath: String): String
    external fun decodeThumbnail(filePath: String): ByteArray?
    external fun decodeFullRaw(filePath: String, halfSize: Boolean): Bitmap?

    fun parseExif(filePath: String): ExifInfo {
        val jsonStr = getMetadata(filePath)
        if (jsonStr.isEmpty() || jsonStr == "{}") {
            return ExifInfo()
        }
        return try {
            val json = JSONObject(jsonStr)
            ExifInfo(
                make = json.optString("make", ""),
                model = json.optString("model", ""),
                iso = json.optDouble("iso", 0.0).toFloat(),
                shutter = json.optDouble("shutter", 0.0).toFloat(),
                aperture = json.optDouble("aperture", 0.0).toFloat(),
                focal = json.optDouble("focal", 0.0).toFloat(),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                rawWidth = json.optInt("rawWidth", 0),
                rawHeight = json.optInt("rawHeight", 0),
                flip = json.optInt("flip", 0),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EXIF JSON: ${e.message}")
            ExifInfo()
        }
    }

    suspend fun loadPhotoBitmap(
        filePath: String,
        isRaw: Boolean,
        fastMode: Boolean = true,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        if (isRaw) {
            // First try thumbnail fast extraction for super responsive UI
            if (fastMode) {
                val thumbBytes = decodeThumbnail(filePath)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                    if (bitmap != null) return@withContext rotateBitmapIfNeeded(bitmap, filePath)
                }
                // Fallback to half-size demosaic if thumbnail extraction fails or unavailable
                val halfBitmap = decodeFullRaw(filePath, halfSize = true)
                if (halfBitmap != null) return@withContext halfBitmap
            } else {
                // High precision decode
                val fullBitmap = decodeFullRaw(filePath, halfSize = false)
                if (fullBitmap != null) return@withContext fullBitmap
            }
        }

        // Standard image fallback (JPG, PNG, WEBP)
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (fastMode) {
                    inSampleSize = 2
                }
            }
            val bitmap = BitmapFactory.decodeFile(filePath, options) ?: return@withContext null
            return@withContext rotateBitmapIfNeeded(bitmap, filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode standard image: ${e.message}")
            null
        }
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, path: String): Bitmap = try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        bitmap
    }
}
