package com.yashikota.omaigenzo.data

/** Gallery thumbnails and full-screen previews never share cache space or eviction pressure. */
enum class PreviewBucket {
    THUMBNAIL,
    PREVIEW,
    ;

    companion object {
        const val MAX_THUMBNAIL_DIMENSION = 768

        fun forTarget(targetMaxDimension: Int): PreviewBucket = if (targetMaxDimension <= MAX_THUMBNAIL_DIMENSION) THUMBNAIL else PREVIEW
    }
}

data class PreviewBudget(val previewBytes: Long, val thumbnailBytes: Long) {
    companion object {
        private const val MB = 1L shl 20

        /**
         * Pixel memory of Bitmaps lives outside the Java heap, so the budget follows device RAM
         * rather than `Runtime.maxMemory()`, which is far too small to hold a few 2048px previews.
         */
        fun compute(totalRamBytes: Long, lowRam: Boolean): PreviewBudget {
            val preview = (totalRamBytes / 16).coerceIn(48 * MB, 192 * MB)
            val thumbnails = (totalRamBytes / 64).coerceIn(16 * MB, 64 * MB)
            return if (lowRam) PreviewBudget(preview / 2, thumbnails / 2) else PreviewBudget(preview, thumbnails)
        }

        /**
         * How many photos may be decoded speculatively. The current card and the previous one (for
         * instant undo) are reserved first, so speculative work can never evict what is on screen.
         */
        fun maxPrefetchEntries(previewBytes: Long, targetMaxDimension: Int): Int {
            val fits = previewBytes / PreviewSizing.estimateBytes(targetMaxDimension)
            return (fits - 2).toInt().coerceIn(0, 3)
        }
    }
}

/** Two independent byte-bounded LRU caches, one per [PreviewBucket]. */
class BucketedCache<V : Any>(
    previewBytes: Long,
    thumbnailBytes: Long,
    sizeOf: (V) -> Long,
) {
    private val preview = ByteBudgetLruCache<String, V>(previewBytes, sizeOf)
    private val thumbnails = ByteBudgetLruCache<String, V>(thumbnailBytes, sizeOf)

    private fun bucket(bucket: PreviewBucket) = if (bucket == PreviewBucket.PREVIEW) preview else thumbnails

    operator fun get(bucket: PreviewBucket, key: String): V? = bucket(bucket)[key]

    fun put(bucket: PreviewBucket, key: String, value: V) = bucket(bucket).put(key, value)

    fun sizeBytes(bucket: PreviewBucket): Long = bucket(bucket).sizeBytes
}

/** A JPEG stored inside a RAW container: its address in the mapped file and its probed pixel size. */
data class EmbeddedJpeg(val address: Long, val length: Long, val width: Int, val height: Int) {
    val maxDimension: Int get() = maxOf(width, height)
}

object EmbeddedPreviewPolicy {
    /**
     * Embedded JPEGs worth trying, best first: those that cover [targetMaxDimension], smallest
     * first (cheapest to decode), then the larger-is-better remainder, as long as they are not so
     * small that full-screen output would be a blur. Callers try them in order, so one corrupt entry
     * costs another JPEG decode instead of a full RAW development.
     */
    fun rank(candidates: List<EmbeddedJpeg>, targetMaxDimension: Int): List<EmbeddedJpeg> {
        val usable = candidates.filter { it.length > 0 && it.width > 0 && it.height > 0 }
        val covering = usable.filter { it.maxDimension >= targetMaxDimension }.sortedBy { it.maxDimension }
        val minimumAcceptable = if (PreviewBucket.forTarget(targetMaxDimension) == PreviewBucket.THUMBNAIL) 1 else targetMaxDimension / 2
        val smaller = usable.filter { it.maxDimension < targetMaxDimension && it.maxDimension >= minimumAcceptable }
            .sortedByDescending { it.maxDimension }
        return covering + smaller
    }

    fun choose(candidates: List<EmbeddedJpeg>, targetMaxDimension: Int): EmbeddedJpeg? = rank(candidates, targetMaxDimension).firstOrNull()
}
