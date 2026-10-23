package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.EmbeddedJpeg
import com.yashikota.omaigenzo.data.EmbeddedPreviewPolicy
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.data.PreviewBudget
import com.yashikota.omaigenzo.data.PreviewSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPolicyEdgeCaseTest {

    private val mb = 1L shl 20

    // --- size buckets -------------------------------------------------------------------------

    @Test
    fun theBucketBoundaryIsInclusiveForThumbnails() {
        assertEquals(PreviewBucket.THUMBNAIL, PreviewBucket.forTarget(PreviewBucket.MAX_THUMBNAIL_DIMENSION))
        assertEquals(PreviewBucket.PREVIEW, PreviewBucket.forTarget(PreviewBucket.MAX_THUMBNAIL_DIMENSION + 1))
    }

    @Test
    fun degenerateAndUnboundedTargetsStillPickABucket() {
        assertEquals(PreviewBucket.THUMBNAIL, PreviewBucket.forTarget(0))
        assertEquals(PreviewBucket.THUMBNAIL, PreviewBucket.forTarget(-1))
        assertEquals(PreviewBucket.PREVIEW, PreviewBucket.forTarget(Int.MAX_VALUE))
    }

    // --- budgets --------------------------------------------------------------------------------

    @Test
    fun tinyDevicesGetTheFloorBudget() {
        val budget = PreviewBudget.compute(totalRamBytes = 512 * mb, lowRam = false)
        assertEquals(48 * mb, budget.previewBytes)
        assertEquals(16 * mb, budget.thumbnailBytes)
    }

    @Test
    fun hugeDevicesAreCappedAtTheCeilingBudget() {
        val budget = PreviewBudget.compute(totalRamBytes = 64_000 * mb, lowRam = false)
        assertEquals(192 * mb, budget.previewBytes)
        assertEquals(64 * mb, budget.thumbnailBytes)
    }

    @Test
    fun midRangeDevicesScaleLinearlyWithRam() {
        val budget = PreviewBudget.compute(totalRamBytes = 2_048 * mb, lowRam = false)
        assertEquals(128 * mb, budget.previewBytes)
        assertEquals(32 * mb, budget.thumbnailBytes)
    }

    @Test
    fun lowRamHalvingAppliesEvenBelowTheFloor() {
        val budget = PreviewBudget.compute(totalRamBytes = 512 * mb, lowRam = true)
        assertEquals(24 * mb, budget.previewBytes)
        assertEquals(8 * mb, budget.thumbnailBytes)
    }

    @Test
    fun anUnknownRamSizeFallsBackToTheFloor() {
        val budget = PreviewBudget.compute(totalRamBytes = 0, lowRam = false)
        assertEquals(48 * mb, budget.previewBytes)
        assertEquals(16 * mb, budget.thumbnailBytes)
    }

    @Test
    fun noPrefetchWhenTheBudgetCannotEvenHoldTheVisibleCards() {
        assertEquals(0, PreviewBudget.maxPrefetchEntries(0, 2_048))
        assertEquals(0, PreviewBudget.maxPrefetchEntries(PreviewSizing.estimateBytes(2_048) - 1, 2_048))
    }

    @Test
    fun smallerTargetsAllowTheFullPrefetchDepthOnTheSameBudget() {
        val budget = PreviewSizing.estimateBytes(2_048) * 3
        assertEquals(1, PreviewBudget.maxPrefetchEntries(budget, 2_048))
        assertEquals(3, PreviewBudget.maxPrefetchEntries(budget, 1_024))
    }

    @Test
    fun everyComputedBudgetAllowsAtLeastOnePrefetchAtFullScreenSize() {
        for (ramMb in listOf(1_024L, 2_048L, 4_096L, 8_192L, 16_384L)) {
            for (lowRam in listOf(false, true)) {
                val budget = PreviewBudget.compute(ramMb * mb, lowRam)
                assertTrue(
                    "ram=${ramMb}MB lowRam=$lowRam",
                    PreviewBudget.maxPrefetchEntries(budget.previewBytes, 2_048) >= if (lowRam) 0 else 1,
                )
            }
        }
    }

    // --- sampling -------------------------------------------------------------------------------

    @Test
    fun sampleSizeIsAlwaysAPowerOfTwo() {
        for (edge in listOf(1, 100, 2_047, 2_048, 2_049, 4_096, 6_000, 12_000, 50_000)) {
            val sample = PreviewSizing.sampleSize(edge, edge / 2 + 1, 2_048)
            assertTrue("edge=$edge sample=$sample", sample > 0 && sample and (sample - 1) == 0)
        }
    }

    @Test
    fun sampledOutputNeverExceedsTheTarget() {
        for (edge in listOf(2_049, 3_000, 4_097, 6_000, 8_256, 11_648)) {
            val sample = PreviewSizing.sampleSize(edge, edge, 2_048)
            assertTrue("edge=$edge sample=$sample", edge / sample <= 2_048)
        }
    }

    @Test
    fun sampleSizeIsTheSameForPortraitAndLandscape() {
        assertEquals(PreviewSizing.sampleSize(6_000, 4_000, 1_024), PreviewSizing.sampleSize(4_000, 6_000, 1_024))
    }

    @Test
    fun exactlyTwiceTheTargetHalvesOnce() {
        assertEquals(2, PreviewSizing.sampleSize(4_096, 3_072, 2_048))
        assertEquals(1, PreviewSizing.sampleSize(2_048, 1_536, 2_048))
    }

    @Test
    fun anUnboundedTargetNeverSubsamples() {
        assertEquals(1, PreviewSizing.sampleSize(12_000, 8_000, Int.MAX_VALUE))
    }

    @Test
    fun invalidDimensionsNeverSubsample() {
        assertEquals(1, PreviewSizing.sampleSize(-1, 4_000, 512))
        assertEquals(1, PreviewSizing.sampleSize(6_000, 0, 512))
    }

    @Test
    fun aOnePixelTargetShrinksAllTheWay() {
        assertEquals(8, PreviewSizing.sampleSize(8, 8, 1))
    }

    @Test
    fun byteEstimatesDoNotOverflowForHugeTargets() {
        assertEquals(100_000L * 75_000L * 4L, PreviewSizing.estimateBytes(100_000))
        assertTrue(PreviewSizing.estimateBytes(100_000) > Int.MAX_VALUE)
    }

    @Test
    fun byteEstimatesGrowWithTheTarget() {
        assertTrue(PreviewSizing.estimateBytes(768) < PreviewSizing.estimateBytes(1_024))
        assertTrue(PreviewSizing.estimateBytes(1_024) < PreviewSizing.estimateBytes(2_048))
        assertEquals(0L, PreviewSizing.estimateBytes(0))
    }

    // --- embedded RAW JPEG choice -----------------------------------------------------------------

    private fun jpeg(w: Int, h: Int, length: Long = 1_000L) = EmbeddedJpeg(address = 1L, length = length, width = w, height = h)

    @Test
    fun portraitPreviewsAreMeasuredByTheirLongEdge() {
        val portrait = jpeg(1_080, 1_616)
        assertEquals(1_616, portrait.maxDimension)
        assertEquals(portrait, EmbeddedPreviewPolicy.choose(listOf(jpeg(160, 120), portrait), 1_024))
    }

    @Test
    fun aPreviewExactlyAtTheTargetCoversIt() {
        assertEquals(2_048, EmbeddedPreviewPolicy.choose(listOf(jpeg(2_048, 1_536), jpeg(6_000, 4_000)), 2_048)?.width)
    }

    @Test
    fun halfTheFullScreenTargetIsTheSmallestAcceptablePreview() {
        assertEquals(1_024, EmbeddedPreviewPolicy.choose(listOf(jpeg(1_024, 768)), 2_048)?.width)
        assertNull(EmbeddedPreviewPolicy.choose(listOf(jpeg(1_023, 767)), 2_048))
    }

    @Test
    fun negativeDimensionsAndLengthsAreIgnored() {
        val ranked = EmbeddedPreviewPolicy.rank(
            listOf(jpeg(-1, 3_000), jpeg(4_000, -1), jpeg(4_000, 3_000, length = -5), jpeg(4_000, 3_000)),
            512,
        )
        assertEquals(listOf(jpeg(4_000, 3_000)), ranked)
    }

    @Test
    fun equallySizedCandidatesKeepTheirContainerOrder() {
        val first = EmbeddedJpeg(address = 10L, length = 100L, width = 1_616, height = 1_080)
        val second = EmbeddedJpeg(address = 20L, length = 100L, width = 1_616, height = 1_080)

        assertEquals(listOf(first, second), EmbeddedPreviewPolicy.rank(listOf(first, second), 1_024))
    }

    @Test
    fun rankingNeverDuplicatesACandidate() {
        val candidates = listOf(jpeg(160, 120), jpeg(1_024, 768), jpeg(2_048, 1_536), jpeg(6_000, 4_000))
        for (target in listOf(256, 512, 768, 1_024, 2_048, 4_096, 8_192)) {
            val ranked = EmbeddedPreviewPolicy.rank(candidates, target)
            assertEquals("target=$target", ranked.size, ranked.toSet().size)
            assertTrue(candidates.containsAll(ranked))
        }
    }

    @Test
    fun thumbnailRequestsStillPreferTheSmallestCoveringPreview() {
        val chosen = EmbeddedPreviewPolicy.choose(listOf(jpeg(6_000, 4_000), jpeg(160, 120), jpeg(1_616, 1_080)), 512)
        assertEquals(1_616, chosen?.width)
    }
}
