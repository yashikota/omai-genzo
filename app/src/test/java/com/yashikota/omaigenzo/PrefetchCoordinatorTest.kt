package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PrefetchCoordinator
import com.yashikota.omaigenzo.data.PrefetchPriority
import com.yashikota.omaigenzo.data.PrefetchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testPrefetchOrderMiddleIndex() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher)
        val requests = mutableListOf<PrefetchRequest>()

        coordinator.onPrefetchRequested = { req ->
            requests.add(req)
        }

        coordinator.updateCurrentIndex(currentIndex = 5, totalSize = 20)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, requests.size)
        // Priority 1: Next photo (6)
        assertEquals(6, requests[0].index)
        assertEquals(PrefetchPriority.IMMEDIATE_NEXT, requests[0].priority)

        // Priority 2: Previous photo (4)
        assertEquals(4, requests[1].index)
        assertEquals(PrefetchPriority.PREVIOUS, requests[1].priority)

        // Priority 3: Lookahead (+2 = 7)
        assertEquals(7, requests[2].index)
        assertEquals(PrefetchPriority.LOOKAHEAD, requests[2].priority)
    }

    @Test
    fun testPrefetchStartIndex() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher)
        val requests = mutableListOf<PrefetchRequest>()

        coordinator.onPrefetchRequested = { req ->
            requests.add(req)
        }

        coordinator.updateCurrentIndex(currentIndex = 0, totalSize = 5)
        testDispatcher.scheduler.advanceUntilIdle()

        // Index 0: Next (1), Lookahead (2), no previous
        assertEquals(2, requests.size)
        assertEquals(1, requests[0].index)
        assertEquals(2, requests[1].index)
    }

    @Test
    fun testPrefetchLastIndex() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher)
        val requests = mutableListOf<PrefetchRequest>()

        coordinator.onPrefetchRequested = { req ->
            requests.add(req)
        }

        coordinator.updateCurrentIndex(currentIndex = 9, totalSize = 10)
        testDispatcher.scheduler.advanceUntilIdle()

        // Index 9 (Last): Previous (8) only
        assertEquals(1, requests.size)
        assertEquals(8, requests[0].index)
        assertEquals(PrefetchPriority.PREVIOUS, requests[0].priority)
    }

    @Test
    fun testRapidSwipeStateTracking() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher)

        coordinator.updateCurrentIndex(0, 100)
        coordinator.updateCurrentIndex(1, 100)
        coordinator.updateCurrentIndex(2, 100)
        coordinator.updateCurrentIndex(3, 100)

        assertEquals(3, coordinator.latestActiveIndex)
    }
}
