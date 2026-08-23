package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.ByteBudgetLruCache
import com.yashikota.omaigenzo.data.PreviewMetrics
import com.yashikota.omaigenzo.data.PreviewSizing
import com.yashikota.omaigenzo.data.SuspendSingleFlight
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPipelineTest {
    @Test
    fun byteBudgetEvictsLeastRecentlyUsedValue() {
        val cache = ByteBudgetLruCache<String, ByteArray>(10) { it.size.toLong() }
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        cache["a"]
        cache.put("c", ByteArray(4))

        assertNull(cache["b"])
        assertEquals(listOf("a", "c"), cache.keysInLruOrder())
        assertEquals(8L, cache.sizeBytes)
    }

    @Test
    fun oversizedValueIsNotRetained() {
        val cache = ByteBudgetLruCache<String, ByteArray>(8) { it.size.toLong() }
        cache.put("huge", ByteArray(16))

        assertNull(cache["huge"])
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun previewSamplingBoundsDecodedMemory() {
        assertEquals(4, PreviewSizing.sampleSize(8_000, 6_000, 2_048))
        assertEquals(4, PreviewSizing.sampleSize(6_000, 4_000, 2_048))
        assertEquals(1, PreviewSizing.sampleSize(1_920, 1_080, 2_048))
        assertEquals(1, PreviewSizing.sampleSize(0, 0, 512))
    }

    @Test
    fun cacheHotPathHandlesLargeSwipeBurstQuickly() {
        val cache = ByteBudgetLruCache<Int, ByteArray>(64 * 1024) { it.size.toLong() }
        val startedAt = System.nanoTime()
        repeat(100_000) { index ->
            cache.put(index, ByteArray(128))
            cache[index]
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("LRU hot path took ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue(cache.sizeBytes <= 64 * 1024)
    }

    @Test
    fun concurrentPreviewRequestsDecodeOnlyOnce() = runTest {
        val singleFlight = SuspendSingleFlight<String, Int?>()
        var decodes = 0

        val results = List(50) {
            async {
                singleFlight.run("same-photo", cached = { null }) {
                    decodes++
                    delay(10)
                    42
                }
            }
        }.awaitAll()

        assertEquals(1, decodes)
        assertTrue(results.all { it == 42 })
    }

    @Test
    fun previewMetricsTrackLatencyWithoutAllocatingPerSample() {
        PreviewMetrics.reset()
        PreviewMetrics.recordCacheHit()
        PreviewMetrics.recordDecode(2_000_000)
        PreviewMetrics.recordDecode(4_000_000)

        val snapshot = PreviewMetrics.snapshot()
        assertEquals(1L, snapshot.cacheHits)
        assertEquals(2L, snapshot.decodes)
        assertEquals(3.0, snapshot.averageDecodeMillis, 0.001)
        assertEquals(4_000_000L, snapshot.maxDecodeNanos)
    }
}
