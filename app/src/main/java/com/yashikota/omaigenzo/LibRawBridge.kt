package com.yashikota.omaigenzo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.yashikota.omaigenzo.data.ByteBudgetLruCache
import com.yashikota.omaigenzo.data.PreviewMetrics
import com.yashikota.omaigenzo.data.PreviewSizing
import com.yashikota.omaigenzo.data.SuspendSingleFlight
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
        private val cacheBytes = (Runtime.getRuntime().maxMemory() / 12L).coerceIn(24L shl 20, 96L shl 20)
        private val bitmapCache = ByteBudgetLruCache<String, Bitmap>(cacheBytes) { it.byteCount.toLong() }
        private val singleFlight = SuspendSingleFlight<String, Bitmap?>()

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
    external fun decodeThumbnailFromFd(fd: Int): ByteArray?
    external fun decodeThumbnailBitmapFromFd(fd: Int, targetMaxDimension: Int): Bitmap?
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
        context: Context,
        filePath: String,
        isRaw: Boolean,
        fastMode: Boolean = true,
        targetMaxDimension: Int = 2048,
        cacheVersion: Long = 0L,
    ): Bitmap? {
        val cacheKey = "$filePath|$cacheVersion|$isRaw|$fastMode|$targetMaxDimension"
        bitmapCache[cacheKey]?.let {
            PreviewMetrics.recordCacheHit()
            return it
        }
        return singleFlight.run(cacheKey, cached = { bitmapCache[cacheKey] }) {
            val startedAt = System.nanoTime()
            decodePhoto(context, filePath, isRaw, fastMode, targetMaxDimension).also { decoded ->
                PreviewMetrics.recordDecode(System.nanoTime() - startedAt)
                if (decoded != null) bitmapCache.put(cacheKey, decoded)
            }
        }
    }

    private suspend fun decodePhoto(
        context: Context,
        filePath: String,
        isRaw: Boolean,
        fastMode: Boolean,
        targetMaxDimension: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val uri = filePath.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        var localPath = filePath
        if (uri == null && !File(localPath).exists()) return@withContext null

        if (isRaw) {
            if (fastMode) {
                val directBitmap = if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        decodeThumbnailBitmapFromFd(it.fd, targetMaxDimension)
                    }
                } else {
                    null
                }
                if (directBitmap != null) return@withContext directBitmap

                val thumbBytes = if (uri != null) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { decodeThumbnailFromFd(it.fd) }
                } else {
                    decodeThumbnail(localPath)
                }
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val bitmap = decodeJpegBytes(thumbBytes, targetMaxDimension)
                    if (bitmap != null) {
                        return@withContext if (uri != null) bitmap else rotateBitmapIfNeeded(bitmap, localPath)
                    }
                }
                if (uri != null) localPath = cacheUriForNativeDecode(context, uri, filePath)
                val halfBitmap = decodeFullRaw(localPath, halfSize = true)
                if (halfBitmap != null) return@withContext halfBitmap
            } else {
                if (uri != null) localPath = cacheUriForNativeDecode(context, uri, filePath)
                val fullBitmap = decodeFullRaw(localPath, halfSize = false)
                if (fullBitmap != null) return@withContext fullBitmap
            }
        }

        // Standard image fallback (JPG, PNG, WEBP)
        try {
            val options = bitmapOptions(context, uri, localPath, if (fastMode) targetMaxDimension else Int.MAX_VALUE)
            val bitmap = if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } else {
                BitmapFactory.decodeFile(localPath, options)
            } ?: return@withContext null
            return@withContext if (uri != null) rotateBitmapIfNeeded(context, bitmap, uri) else rotateBitmapIfNeeded(bitmap, localPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode standard image: ${e.message}")
            null
        }
    }

    private fun decodeJpegBytes(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun bitmapOptions(context: Context, uri: Uri?, path: String, target: Int): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } else {
            BitmapFactory.decodeFile(path, bounds)
        }
        return BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
    }

    private fun cacheUriForNativeDecode(context: Context, uri: Uri, key: String): String {
        val extension = key.substringAfterLast('.', "raw").take(8)
        val target = File(context.cacheDir, "raw_preview_${key.hashCode()}.$extension")
        if (!target.exists() || target.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: return ""
        }
        return target.absolutePath
    }

    private fun rotateBitmapIfNeeded(context: Context, bitmap: Bitmap, uri: Uri): Bitmap = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            rotateBitmap(bitmap, ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } ?: bitmap
    } catch (_: Exception) {
        bitmap
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, path: String): Bitmap = try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        rotateBitmap(bitmap, orientation)
    } catch (e: Exception) {
        bitmap
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
}
