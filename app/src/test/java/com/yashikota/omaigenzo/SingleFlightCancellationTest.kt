package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.SuspendSingleFlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightCancellationTest {

    /**
     * Regression: a prefetch (leader) is cancelled by the next swipe while the now-visible card has
     * already joined the same flight. The visible card must still receive its bitmap.
     */
    @Test
    fun cancellingTheFirstCallerDoesNotStarveLaterJoiners() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var decodes = 0

        val prefetch = async {
            flight.run("photo-7", cached = { null }) {
                decodes++
                delay(100)
                7
            }
        }
        testScheduler.advanceTimeBy(10)
        val visible = async { flight.run("photo-7", cached = { null }) { error("must join, not decode") } }
        testScheduler.advanceTimeBy(10)

        prefetch.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(7, visible.await())
        assertEquals(1, decodes)
    }

    @Test
    fun workIsCancelledWhenNobodyIsWaitingAnymore() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var finished = false

        val prefetch = launch {
            flight.run("stale", cached = { null }) {
                delay(1_000)
                finished = true
                1
            }
        }
        testScheduler.advanceTimeBy(10)
        prefetch.cancelAndJoin()
        advanceUntilIdle()

        assertFalse("stale speculative work must not keep running", finished)
    }

    @Test
    fun aNewCallerAfterAbandonmentStartsFreshWork() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        var decodes = 0

        val first = launch { flight.run("k", cached = { null }) { decodes++; delay(1_000); 1 } }
        testScheduler.advanceTimeBy(10)
        first.cancelAndJoin()

        val second = async { flight.run("k", cached = { null }) { decodes++; delay(10); 2 } }
        advanceUntilIdle()

        assertEquals(2, second.await())
        assertEquals(2, decodes)
    }

    @Test
    fun producerFailureReachesEveryWaiterAndIsNotCached() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(CoroutineScope(coroutineContext + SupervisorJob()))
        val boom = IllegalStateException("decode failed")

        val a = async { runCatching { flight.run("k", cached = { null }) { delay(10); throw boom } } }
        val b = async { runCatching { flight.run("k", cached = { null }) { error("joined") } } }
        advanceUntilIdle()

        // Stack-trace recovery may hand each waiter a copy, so compare type and message.
        listOf(a.await(), b.await()).forEach { result ->
            val error = result.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(boom.message, error?.message)
        }

        val retry = async { flight.run("k", cached = { null }) { 5 } }
        advanceUntilIdle()
        assertEquals(5, retry.await())
    }

    @Test
    fun cachedValueShortCircuitsWithoutStartingWork() = runTest {
        val flight = SuspendSingleFlight<String, Int?>(this)
        val result = flight.run("k", cached = { 99 }) { error("must not run") }
        assertEquals(99, result)
    }
}
