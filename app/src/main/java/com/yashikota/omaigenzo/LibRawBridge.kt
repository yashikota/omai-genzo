package com.yashikota.omaigenzo

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.yashikota.omaigenzo.data.BucketedCache
import com.yashikota.omaigenzo.data.EmbeddedJpeg
import com.yashikota.omaigenzo.data.EmbeddedPreviewPolicy
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.data.PreviewBudget
import com.yashikota.omaigenzo.data.PreviewMetrics
import com.yashikota.omaigenzo.data.PreviewSizing
import com.yashikota.omaigenzo.data.SuspendSingleFlight
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

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

/** Visible work must never queue behind speculative work. */
enum class DecodePriority { VISIBLE, PREFETCH }

class LibRawBridge(context: Context) {

    private val appContext = context.applicationContext

    val budget: PreviewBudget get() = sharedBudget(appContext)

    /** How many photos prefetch may decode ahead without evicting the cards on screen. */
    fun maxPrefetchEntries(targetMaxDimension: Int = DEFAULT_PREVIEW_TARGET): Int = PreviewBudget.maxPrefetchEntries(budget.previewBytes, targetMaxDimension)

    companion object {
        private const val TAG = "LibRawBridge"
        const val DEFAULT_PREVIEW_TARGET = 2048

        // Bitmaps above this edge are not decoded into GPU memory: older GPUs cap texture size.
        private const val MAX_HARDWARE_EDGE = 4096

        private val cacheLock = Any()

        @Volatile private var budgetCache: PreviewBudget? = null

        @Volatile private var bitmapCaches: BucketedCache<Bitmap>? = null
        private val singleFlight = SuspendSingleFlight<String, Bitmap?>()

        private val visibleDispatcher: CoroutineDispatcher = Dispatchers.IO

        // Two parallel speculative decodes keep the big cores busy without starving the visible one.
        @OptIn(ExperimentalCoroutinesApi::class)
        private val prefetchDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

        init {
            try {
                System.loadLibrary("native-lib")
                Log.i(TAG, "Native library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }

        private fun sharedBudget(context: Context): PreviewBudget = budgetCache ?: synchronized(cacheLock) {
            budgetCache ?: run {
                val manager = context.getSystemService(ActivityManager::class.java)
                val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
                PreviewBudget.compute(info.totalMem, manager.isLowRamDevice).also { budgetCache = it }
            }
        }

        private fun caches(context: Context): BucketedCache<Bitmap> = bitmapCaches ?: synchronized(cacheLock) {
            bitmapCaches ?: run {
                val budget = sharedBudget(context)
                BucketedCache<Bitmap>(budget.previewBytes, budget.thumbnailBytes) { it.allocationByteCount.toLong() }
                    .also { bitmapCaches = it }
            }
        }
    }

    external fun getLibRawVersion(): String
    external fun getMetadata(filePath: String): String

    /** [handle, rawFlip, count, (address, length, width, height) * count], or null. Pair with [closeView]. */
    private external fun openEmbeddedPreviews(fd: Int): LongArray?
    private external fun wrapDirect(address: Long, length: Long): ByteBuffer?
    private external fun closeView(handle: Long)
    private external fun decodeRawFromFd(fd: Int, halfSize: Boolean): Bitmap?

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

    private fun isGpuResident(bitmap: Bitmap) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE

    private fun cacheKey(filePath: String, isRaw: Boolean, fastMode: Boolean, target: Int, version: Long) = "$filePath|$version|$isRaw|$fastMode|$target"

    /** Synchronous cache lookup, cheap enough for composition so a hit paints in the same frame. */
    fun peekCached(
        filePath: String,
        isRaw: Boolean,
        fastMode: Boolean = true,
        targetMaxDimension: Int = DEFAULT_PREVIEW_TARGET,
        cacheVersion: Long = 0L,
        record: Boolean = true,
    ): Bitmap? {
        val bucket = PreviewBucket.forTarget(targetMaxDimension)
        val hit = caches(appContext)[bucket, cacheKey(filePath, isRaw, fastMode, targetMaxDimension, cacheVersion)] ?: return null
        if (!record) return hit
        PreviewMetrics.recordCacheHit()
        PerfLogger.event(
            "preview_cache_hit",
            "\"source\":\"${PerfLogger.escape(filePath)}\",\"target\":$targetMaxDimension,\"bucket\":\"$bucket\"," +
                "\"width\":${hit.width},\"height\":${hit.height},\"bytes\":${hit.allocationByteCount}",
        )
        return hit
    }

    suspend fun loadPhotoBitmap(
        filePath: String,
        isRaw: Boolean,
        fastMode: Boolean = true,
        targetMaxDimension: Int = DEFAULT_PREVIEW_TARGET,
        cacheVersion: Long = 0L,
        priority: DecodePriority = DecodePriority.VISIBLE,
    ): Bitmap? {
        PerfLogger.event(
            "preview_request",
            "\"source\":\"${PerfLogger.escape(filePath)}\",\"raw\":$isRaw,\"fast\":$fastMode," +
                "\"target\":$targetMaxDimension,\"version\":$cacheVersion,\"priority\":\"$priority\"",
        )
        peekCached(filePath, isRaw, fastMode, targetMaxDimension, cacheVersion)?.let { return it }

        val bucket = PreviewBucket.forTarget(targetMaxDimension)
        val key = cacheKey(filePath, isRaw, fastMode, targetMaxDimension, cacheVersion)
        val cache = caches(appContext)
        return singleFlight.run(key, cached = { cache[bucket, key] }) {
            val startedAt = System.nanoTime()
            val dispatcher = if (priority == DecodePriority.PREFETCH) prefetchDispatcher else visibleDispatcher
            withContext(dispatcher) { decodePhoto(filePath, isRaw, fastMode, targetMaxDimension) }.also { decoded ->
                val elapsed = System.nanoTime() - startedAt
                PreviewMetrics.recordDecode(elapsed)
                PerfLogger.event(
                    "preview_decode_end",
                    "\"source\":\"${PerfLogger.escape(filePath)}\",\"success\":${decoded != null},\"cache\":\"miss\"," +
                        "\"bucket\":\"$bucket\",\"priority\":\"$priority\"," +
                        "\"width\":${decoded?.width ?: 0},\"height\":${decoded?.height ?: 0}," +
                        "\"bytes\":${decoded?.allocationByteCount ?: 0},\"duration_ns\":$elapsed",
                )
                if (decoded != null) cache.put(bucket, key, decoded)
            }
        }
    }

    private fun decodePhoto(filePath: String, isRaw: Boolean, fastMode: Boolean, target: Int): Bitmap? {
        val uri = filePath.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        if (uri == null && !File(filePath).exists()) return null

        if (isRaw) {
            val raw = openDescriptor(uri, filePath)?.use { pfd ->
                if (fastMode) decodeRawFast(pfd.fd, filePath, target) else decodeRawFull(pfd.fd, filePath)
            }
            if (raw != null) return raw
        }
        return decodeStandard(uri, filePath, if (fastMode) target else Int.MAX_VALUE)
    }

    /** One Binder round trip per photo; every later stage reuses this descriptor. */
    private fun openDescriptor(uri: Uri?, path: String): ParcelFileDescriptor? = try {
        if (uri != null) {
            appContext.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open $path: ${e.message}")
        null
    }

    // RAW: embedded JPEG (zero copy) -> reduced RAW processing as the last resort.
    private fun decodeRawFast(fd: Int, source: String, target: Int): Bitmap? {
        val packed = openEmbeddedPreviews(fd)
        if (packed != null) {
            val handle = packed[0]
            try {
                val count = packed[2].toInt()
                val candidates = List(count) { i ->
                    val base = 3 + i * 4
                    EmbeddedJpeg(packed[base], packed[base + 1], packed[base + 2].toInt(), packed[base + 3].toInt())
                }
                for (chosen in EmbeddedPreviewPolicy.rank(candidates, target)) {
                    val buffer = wrapDirect(chosen.address, chosen.length) ?: continue
                    val decoded = decodeJpegBuffer(buffer, target) ?: continue
                    PerfLogger.event(
                        "preview_decode_path",
                        "\"path\":\"embedded_jpeg\",\"hardware\":${isGpuResident(decoded)}," +
                            "\"source\":\"${PerfLogger.escape(source)}\",\"compressed_bytes\":${chosen.length}," +
                            "\"embedded_width\":${chosen.width},\"embedded_height\":${chosen.height}," +
                            "\"candidates\":$count",
                    )
                    return decoded
                }
            } finally {
                closeView(handle)
            }
        }
        PerfLogger.event("preview_decode_path", "\"path\":\"half_raw_fallback\",\"source\":\"${PerfLogger.escape(source)}\"")
        return decodeRawFromFd(fd, true)
    }

    private fun decodeRawFull(fd: Int, source: String): Bitmap? {
        PerfLogger.event("preview_decode_path", "\"path\":\"full_raw\",\"source\":\"${PerfLogger.escape(source)}\"")
        return decodeRawFromFd(fd, false)
    }

    private fun decodeJpegBuffer(buffer: ByteBuffer, target: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return decodeWithImageDecoder(ImageDecoder.createSource(buffer), target)
        }
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Single pass: JPEG-native downscale, EXIF orientation and sRGB conversion happen in the codec,
     * and the pixels land directly in GPU-accessible memory, so the render thread never has to
     * upload them on first draw.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(source: ImageDecoder.Source, target: Int): Bitmap? {
        val hardwareAllowed = target <= MAX_HARDWARE_EDGE
        if (hardwareAllowed) {
            try {
                return imageDecoderPass(source, target, hardware = true)
            } catch (e: Exception) {
                Log.w(TAG, "Hardware decode failed, retrying in software: ${e.message}")
            }
        }
        return try {
            imageDecoderPass(source, target, hardware = false)
        } catch (e: Exception) {
            Log.e(TAG, "ImageDecoder failed: ${e.message}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun imageDecoderPass(source: ImageDecoder.Source, target: Int, hardware: Boolean): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setTargetSampleSize(PreviewSizing.sampleSize(info.size.width, info.size.height, target))
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.allocator = if (hardware) ImageDecoder.ALLOCATOR_HARDWARE else ImageDecoder.ALLOCATOR_SOFTWARE
    }

    // JPG / PNG / WEBP sidecars and standalone images.
    private fun decodeStandard(uri: Uri?, path: String, target: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = if (uri != null) {
                ImageDecoder.createSource(appContext.contentResolver, uri)
            } else {
                ImageDecoder.createSource(File(path))
            }
            decodeWithImageDecoder(source, target)?.also { decoded ->
                PerfLogger.event(
                    "preview_decode_path",
                    "\"path\":\"image_decoder\",\"hardware\":${isGpuResident(decoded)}," +
                        "\"source\":\"${PerfLogger.escape(path)}\"",
                )
            }
        } else {
            decodeStandardLegacy(uri, path, target)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode standard image: ${e.message}")
        null
    }

    // API < 28: BitmapFactory has no orientation handling, so this path pays the extra EXIF read.
    private fun decodeStandardLegacy(uri: Uri?, path: String, target: Int): Bitmap? {
        PerfLogger.event("preview_decode_path", "\"path\":\"standard_bitmap\",\"source\":\"${PerfLogger.escape(path)}\"")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uri != null) {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } else {
            BitmapFactory.decodeFile(path, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        val bitmap = if (uri != null) {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } else {
            BitmapFactory.decodeFile(path, options)
        } ?: return null
        val orientation = try {
            if (uri != null) {
                appContext.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } else {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        return rotateBitmap(bitmap, orientation)
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees != 0f) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        } else {
            bitmap
        }
    }
}
