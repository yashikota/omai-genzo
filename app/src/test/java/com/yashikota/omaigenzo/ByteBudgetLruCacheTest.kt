package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.BucketedCache
import com.yashikota.omaigenzo.data.ByteBudgetLruCache
import com.yashikota.omaigenzo.data.PreviewBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ByteBudgetLruCacheTest {

    private fun cache(maxBytes: Long) = ByteBudgetLruCache<String, ByteArray>(maxBytes) { it.size.toLong() }

    @Test
    fun replacingAKeyCountsOnlyTheNewValue() {
        val cache = cache(100)
        cache.put("a", ByteArray(40))
        cache.put("a", ByteArray(10))

        assertEquals(10L, cache.sizeBytes)
        assertEquals(listOf("a"), cache.keysInLruOrder())
        assertEquals(10, cache["a"]?.size)
    }

    @Test
    fun aValueExactlyAtTheBudgetIsKept() {
        val cache = cache(8)
        cache.put("fits", ByteArray(8))

        assertNotNull(cache["fits"])
        assertEquals(8L, cache.sizeBytes)
    }

    @Test
    fun oneLargeInsertEvictsAsManyOldEntriesAsItNeeds() {
        val cache = cache(10)
        cache.put("a", ByteArray(3))
        cache.put("b", ByteArray(3))
        cache.put("c", ByteArray(3))

        cache.put("d", ByteArray(9))

        assertEquals(listOf("d"), cache.keysInLruOrder())
        assertEquals(9L, cache.sizeBytes)
    }

    @Test
    fun evictionStopsAsSoonAsTheBudgetIsMet() {
        val cache = cache(10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))

        cache.put("c", ByteArray(4))

        assertNull(cache["a"])
        assertEquals(listOf("b", "c"), cache.keysInLruOrder())
    }

    @Test
    fun replacingAKeyWithAnOversizedValueDropsItEntirely() {
        val cache = cache(8)
        cache.put("a", ByteArray(4))

        cache.put("a", ByteArray(16))

        assertNull(cache["a"])
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun aMissLeavesTheRecencyOrderUntouched() {
        val cache = cache(100)
        cache.put("a", ByteArray(1))
        cache.put("b", ByteArray(1))

        assertNull(cache["missing"])

        assertEquals(listOf("a", "b"), cache.keysInLruOrder())
    }

    @Test
    fun replacingAKeyMakesItTheMostRecentlyUsed() {
        val cache = cache(10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        cache.put("a", ByteArray(4))

        cache.put("c", ByteArray(4))

        assertNull("b was the least recently used", cache["b"])
        assertEquals(listOf("a", "c"), cache.keysInLruOrder())
    }

    @Test
    fun clearReleasesEveryByteAndTheCacheStaysUsable() {
        val cache = cache(10)
        cache.put("a", ByteArray(5))
        cache.put("b", ByteArray(5))

        cache.clear()

        assertEquals(0L, cache.sizeBytes)
        assertTrue(cache.keysInLruOrder().isEmpty())
        cache.put("c", ByteArray(10))
        assertEquals(10L, cache.sizeBytes)
    }

    @Test
    fun getReturnsTheStoredInstanceWithoutCopying() {
        val cache = cache(10)
        val value = ByteArray(4)
        cache.put("a", value)

        assertSame(value, cache["a"])
    }

    @Test
    fun concurrentWritersNeverBreakTheByteAccounting() {
        val cache = cache(4_096)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { worker ->
            pool.execute {
                repeat(5_000) { i ->
                    val key = "k${(worker * 31 + i) % 200}"
                    cache.put(key, ByteArray(1 + (i % 97)))
                    cache[key]
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        val retained = cache.keysInLruOrder().sumOf { (cache[it]?.size ?: 0).toLong() }
        assertEquals(retained, cache.sizeBytes)
        assertTrue(cache.sizeBytes <= 4_096)
    }

    @Test
    fun bucketsKeepIndependentEntriesForTheSameKey() {
        val cache = BucketedCache<ByteArray>(previewBytes = 100, thumbnailBytes = 100) { it.size.toLong() }
        val preview = ByteArray(50)
        val thumbnail = ByteArray(5)

        cache.put(PreviewBucket.PREVIEW, "photo", preview)
        cache.put(PreviewBucket.THUMBNAIL, "photo", thumbnail)

        assertSame(preview, cache[PreviewBucket.PREVIEW, "photo"])
        assertSame(thumbnail, cache[PreviewBucket.THUMBNAIL, "photo"])
        assertEquals(50L, cache.sizeBytes(PreviewBucket.PREVIEW))
        assertEquals(5L, cache.sizeBytes(PreviewBucket.THUMBNAIL))
    }

    @Test
    fun previewChurnCanNeverEvictAThumbnail() {
        val cache = BucketedCache<ByteArray>(previewBytes = 30, thumbnailBytes = 30) { it.size.toLong() }
        cache.put(PreviewBucket.THUMBNAIL, "thumb", ByteArray(10))

        repeat(100) { cache.put(PreviewBucket.PREVIEW, "preview-$it", ByteArray(20)) }

        assertNotNull(cache[PreviewBucket.THUMBNAIL, "thumb"])
        assertTrue(cache.sizeBytes(PreviewBucket.PREVIEW) <= 30)
    }
}
