package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PrefetchCoordinator
import com.yashikota.omaigenzo.data.PrefetchPlanner
import com.yashikota.omaigenzo.data.PrefetchPriority
import com.yashikota.omaigenzo.data.PrefetchRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun indices(requests: List<PrefetchRequest>) = requests.map { it.index }

    // --- planning --------------------------------------------------------------------------------

    @Test
    fun forwardSwipingPrefetchesAheadFirstAndTheJustViewedPhotoLast() {
        val plan = PrefetchPlanner.plan(currentIndex = 5, totalSize = 20, direction = 1, maxEntries = 4)

        assertEquals(listOf(6, 7, 8, 4), indices(plan))
        assertEquals(PrefetchPriority.IMMEDIATE_NEXT, plan[0].priority)
        assertEquals(PrefetchPriority.LOOKAHEAD, plan[1].priority)
        assertEquals(PrefetchPriority.PREVIOUS, plan[3].priority)
    }

    @Test
    fun backwardNavigationMirrorsThePlan() {
        val plan = PrefetchPlanner.plan(currentIndex = 5, totalSize = 20, direction = -1, maxEntries = 4)

        assertEquals(listOf(4, 3, 2, 6), indices(plan))
        assertEquals(PrefetchPriority.IMMEDIATE_NEXT, plan[0].priority)
    }

    @Test
    fun theBudgetCapsHowManyPhotosAreDecodedSpeculatively() {
        assertEquals(listOf(6, 7), indices(PrefetchPlanner.plan(5, 20, 1, maxEntries = 2)))
        assertEquals(listOf(6), indices(PrefetchPlanner.plan(5, 20, 1, maxEntries = 1)))
        assertTrue(PrefetchPlanner.plan(5, 20, 1, maxEntries = 0).isEmpty())
    }

    @Test
    fun planStaysInsideTheList() {
        assertEquals(listOf(1, 2, 3), indices(PrefetchPlanner.plan(0, 5, 1, maxEntries = 4)))
        assertEquals(listOf(8), indices(PrefetchPlanner.plan(9, 10, 1, maxEntries = 4)))
        assertEquals(listOf(8, 7, 6), indices(PrefetchPlanner.plan(9, 10, -1, maxEntries = 3)))
        assertTrue(PrefetchPlanner.plan(0, 1, 1, maxEntries = 4).isEmpty())
    }

    // --- coordinator -----------------------------------------------------------------------------

    @Test
    fun coordinatorRunsTheFirstPlanInPriorityOrder() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(currentIndex = 5, totalSize = 20)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(6, 7, 8), indices(seen))
    }

    @Test
    fun coordinatorInfersDirectionFromTheLastIndex() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 2)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(10, 50)
        testDispatcher.scheduler.advanceUntilIdle()
        seen.clear()
        coordinator.updateCurrentIndex(9, 50) // undo: moving backwards
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(8, 7), indices(seen))
    }

    @Test
    fun staleRequestsNeverRunAfterTheCurrentPhotoChanged() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(0, 100)
        coordinator.updateCurrentIndex(1, 100)
        coordinator.updateCurrentIndex(2, 100)
        coordinator.updateCurrentIndex(3, 100)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, coordinator.latestActiveIndex)
        assertEquals("only the latest generation may run", listOf(4, 5, 6), indices(seen))
    }

    @Test
    fun anInFlightStaleRequestIsCancelled() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 1)
        val gate = CompletableDeferred<Unit>()
        var finishedStale = false
        coordinator.onPrefetchRequested = { request ->
            if (request.index == 1) {
                gate.await()
                finishedStale = true
            }
        }

        coordinator.updateCurrentIndex(0, 10)
        testDispatcher.scheduler.runCurrent()
        coordinator.updateCurrentIndex(5, 10)
        testDispatcher.scheduler.advanceUntilIdle()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(finishedStale)
    }

    @Test
    fun cancelAllStopsPendingWork() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(0, 10)
        coordinator.cancelAll()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun aSingleItemListHasNothingToPrefetch() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(0, 1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(seen.isEmpty())
    }
}
