package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.BucketedCache
import com.yashikota.omaigenzo.data.EmbeddedJpeg
import com.yashikota.omaigenzo.data.EmbeddedPreviewPolicy
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.data.PreviewBudget
import com.yashikota.omaigenzo.data.PreviewSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPolicyTest {

    private val mb = 1L shl 20

    // --- size buckets -------------------------------------------------------------------------

    @Test
    fun galleryThumbnailsAndFullScreenPreviewsUseSeparateBuckets() {
        assertEquals(PreviewBucket.THUMBNAIL, PreviewBucket.forTarget(512))
        assertEquals(PreviewBucket.THUMBNAIL, PreviewBucket.forTarget(768))
        assertEquals(PreviewBucket.PREVIEW, PreviewBucket.forTarget(1_024))
        assertEquals(PreviewBucket.PREVIEW, PreviewBucket.forTarget(2_048))
    }

    @Test
    fun thumbnailChurnCanNeverEvictAFullScreenPreview() {
        val cache = BucketedCache<ByteArray>(previewBytes = 30, thumbnailBytes = 30) { it.size.toLong() }
        cache.put(PreviewBucket.PREVIEW, "preview", ByteArray(20))

        repeat(1_000) { cache.put(PreviewBucket.THUMBNAIL, "thumb-$it", ByteArray(10)) }

        assertNotNull(cache.get(PreviewBucket.PREVIEW, "preview"))
        assertTrue(cache.sizeBytes(PreviewBucket.THUMBNAIL) <= 30)
    }

    // --- budgets --------------------------------------------------------------------------------

    @Test
    fun budgetScalesWithDeviceMemoryAndStaysInsideSafeBounds() {
        val small = PreviewBudget.compute(totalRamBytes = 2_048 * mb, lowRam = false)
        val big = PreviewBudget.compute(totalRamBytes = 16_384 * mb, lowRam = false)

        assertTrue(small.previewBytes >= 48 * mb)
        assertTrue(big.previewBytes <= 192 * mb)
        assertTrue(big.previewBytes > small.previewBytes)
        assertTrue(big.thumbnailBytes <= 64 * mb)
    }

    @Test
    fun lowRamDevicesGetHalfTheBudget() {
        val normal = PreviewBudget.compute(totalRamBytes = 4_096 * mb, lowRam = false)
        val low = PreviewBudget.compute(totalRamBytes = 4_096 * mb, lowRam = true)

        assertEquals(normal.previewBytes / 2, low.previewBytes)
        assertEquals(normal.thumbnailBytes / 2, low.thumbnailBytes)
    }

    @Test
    fun defaultBudgetHoldsTheCurrentCardPlusItsNeighbours() {
        // The old maxMemory/12 budget held 1-2 previews, so prefetching evicted what it just decoded.
        val budget = PreviewBudget.compute(totalRamBytes = 8_192 * mb, lowRam = false)
        val perPreview = PreviewSizing.estimateBytes(2_048)

        assertTrue("budget must hold 5 previews", budget.previewBytes / perPreview >= 5)
    }

    @Test
    fun prefetchDepthIsDerivedFromTheBudgetNeverFromAGuess() {
        val perPreview = PreviewSizing.estimateBytes(2_048)

        assertEquals(0, PreviewBudget.maxPrefetchEntries(perPreview * 2, 2_048))
        assertEquals(1, PreviewBudget.maxPrefetchEntries(perPreview * 3, 2_048))
        assertEquals(2, PreviewBudget.maxPrefetchEntries(perPreview * 4, 2_048))
        assertEquals(3, PreviewBudget.maxPrefetchEntries(perPreview * 100, 2_048))
    }

    @Test
    fun previewByteEstimateMatchesARealFourByThreeBitmap() {
        // 2048x1536 ARGB_8888
        assertEquals(2_048L * 1_536L * 4L, PreviewSizing.estimateBytes(2_048))
    }

    // --- embedded RAW JPEG choice -----------------------------------------------------------------

    private fun jpeg(w: Int, h: Int) = EmbeddedJpeg(address = 1_000L, length = (w * h / 8).toLong(), width = w, height = h)

    @Test
    fun picksTheSmallestEmbeddedJpegThatCoversTheTarget() {
        val chosen = EmbeddedPreviewPolicy.choose(listOf(jpeg(160, 120), jpeg(1_616, 1_080), jpeg(6_000, 4_000)), 1_024)
        assertEquals(1_616, chosen?.width)
    }

    @Test
    fun fallsBackToTheLargestWhenNothingCoversTheTarget() {
        val chosen = EmbeddedPreviewPolicy.choose(listOf(jpeg(160, 120), jpeg(1_616, 1_080)), 2_048)
        assertEquals(1_616, chosen?.width)
    }

    @Test
    fun rejectsPreviewsTooSmallForFullScreenSoRawProcessingCanTakeOver() {
        assertNull(EmbeddedPreviewPolicy.choose(listOf(jpeg(160, 120)), 2_048))
    }

    @Test
    fun gallerySizedRequestsAcceptTinyEmbeddedThumbnails() {
        assertEquals(160, EmbeddedPreviewPolicy.choose(listOf(jpeg(160, 120)), 512)?.width)
    }

    @Test
    fun emptyCandidateListYieldsNothing() {
        assertNull(EmbeddedPreviewPolicy.choose(emptyList(), 2_048))
    }

    @Test
    fun ignoresDegenerateEntries() {
        val broken = EmbeddedJpeg(address = 0, length = 0, width = 4_000, height = 3_000)
        assertNull(EmbeddedPreviewPolicy.choose(listOf(broken), 512))
    }
}
