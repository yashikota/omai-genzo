package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.SuspendSingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightConcurrencyTest {

    @Test
    fun differentKeysNeverWaitForEachOther() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        val decodes = AtomicInteger()

        val results = listOf("a", "b", "c").map { key ->
            async {
                flight.run(key, cached = { null }) {
                    decodes.incrementAndGet()
                    delay(100)
                    key.length
                }
            }
        }
        advanceUntilIdle()

        assertEquals(listOf(1, 1, 1), results.awaitAll())
        assertEquals(3, decodes.get())
        assertEquals("all three ran side by side", 100L, testScheduler.currentTime)
    }

    @Test
    fun aNullResultIsDeliveredButNotRemembered() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var decodes = 0

        val first = flight.run("k", cached = { null }) {
            decodes++
            null
        }
        val second = flight.run("k", cached = { null }) {
            decodes++
            null
        }

        assertNull(first)
        assertNull(second)
        assertEquals("a failed decode is retried by the next request", 2, decodes)
    }

    @Test
    fun aValueCachedWhileWaitingForTheLockIsUsed() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var lookups = 0

        // The first lookup misses, the double-check under the lock hits: no work may start.
        val result = flight.run("k", cached = { if (lookups++ == 0) null else 11 }) { error("must not decode") }

        assertEquals(11, result)
        assertEquals(2, lookups)
    }

    @Test
    fun aCompletedFlightIsForgottenSoTheNextMissDecodesAgain() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var decodes = 0

        repeat(3) {
            flight.run("k", cached = { null }) {
                decodes++
                it
            }
        }

        assertEquals(3, decodes)
    }

    @Test
    fun joinersReceiveTheLeadersResultEvenWhenTheyArriveLate() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        val gate = CompletableDeferred<Unit>()
        var decodes = 0

        val leader = async {
            flight.run("k", cached = { null }) {
                decodes++
                gate.await()
                21
            }
        }
        testScheduler.runCurrent()
        val joiners = List(10) { async { flight.run("k", cached = { null }) { error("joined") } } }
        testScheduler.runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(21, leader.await())
        assertTrue(joiners.awaitAll().all { it == 21 })
        assertEquals(1, decodes)
    }

    @Test
    fun manyThreadsRequestingOnePhotoDecodeItOnce() = runBlocking {
        val flight = SuspendSingleFlight<String, ByteArray?>()
        val decoded = AtomicReference<ByteArray?>(null)
        val decodes = AtomicInteger()

        val results = withTimeout(10_000) {
            List(64) {
                async(Dispatchers.Default) {
                    // Late callers find the decoded value in the cache, as the bitmap cache does.
                    flight.run("photo", cached = { decoded.get() }) {
                        decodes.incrementAndGet()
                        delay(50)
                        ByteArray(8).also(decoded::set)
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, decodes.get())
        assertTrue(results.all { it === decoded.get() })
    }

    @Test
    fun manyThreadsOnDistinctPhotosEachDecodeExactlyOnce() = runBlocking {
        val flight = SuspendSingleFlight<Int, Int?>()
        val cache = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val decodes = AtomicInteger()

        withTimeout(10_000) {
            List(256) { i ->
                async(Dispatchers.Default) {
                    val key = i % 16
                    flight.run(key, cached = { cache[key] }) {
                        decodes.incrementAndGet()
                        delay(20)
                        (key * 10).also { cache[key] = it }
                    }
                }
            }.awaitAll()
        }

        assertEquals(16, decodes.get())
        assertEquals((0 until 16).associateWith { it * 10 }, cache.toMap())
    }
}
