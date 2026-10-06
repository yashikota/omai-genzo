package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.BucketedCache
import com.yashikota.omaigenzo.data.PrefetchPlanner
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.data.SuspendSingleFlight
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guards for the algorithms on the swipe path. Thresholds are deliberately loose (they protect
 * against accidental O(n) or per-call allocation regressions, not against noise); end-to-end
 * latency is measured on a device by PreviewDecodeBenchmark.
 */
class HotPathBenchmarkTest {

    private inline fun nanosPerOp(iterations: Int, warmup: Int = iterations / 10, block: (Int) -> Unit): Double {
        repeat(warmup) { block(it) }
        val startedAt = System.nanoTime()
        repeat(iterations) { block(it) }
        return (System.nanoTime() - startedAt).toDouble() / iterations
    }

    @Test
    fun planningPrefetchIsCheapEnoughToRunOnEverySwipe() {
        val nanos = nanosPerOp(200_000) { PrefetchPlanner.plan(it % 5_000, 5_000, if (it % 7 == 0) -1 else 1, 3) }
        assertTrue("plan() took ${nanos}ns", nanos < 20_000)
    }

    @Test
    fun bucketedCacheHitIsMicrosecondsEvenWithLargeValues() {
        val cache = BucketedCache<ByteArray>(64L shl 20, 16L shl 20) { it.size.toLong() }
        repeat(8) { cache.put(PreviewBucket.PREVIEW, "p$it", ByteArray(4 shl 20)) }

        val nanos = nanosPerOp(500_000) { cache[PreviewBucket.PREVIEW, "p${it % 8}"] }

        assertTrue("cache hit took ${nanos}ns", nanos < 20_000)
    }

    @Test
    fun singleFlightCacheHitNeverStartsAnyWork() = runBlocking {
        val flight = SuspendSingleFlight<String, ByteArray?>()
        val cached = ByteArray(1)
        var produced = 0

        repeat(100_000) {
            flight.run("k", cached = { cached }) {
                produced++
                null
            }
        }

        assertEquals(0, produced)
    }

    @Test
    fun thumbnailBurstsAreBoundedByTheirOwnBudget() {
        val cache = BucketedCache<ByteArray>(32, 100) { it.size.toLong() }
        repeat(10_000) { cache.put(PreviewBucket.THUMBNAIL, "t$it", ByteArray(10)) }

        assertTrue(cache.sizeBytes(PreviewBucket.THUMBNAIL) <= 100)
        assertEquals(0L, cache.sizeBytes(PreviewBucket.PREVIEW))
    }
}
