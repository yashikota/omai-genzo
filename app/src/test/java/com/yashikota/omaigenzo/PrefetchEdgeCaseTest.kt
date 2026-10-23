package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PrefetchCoordinator
import com.yashikota.omaigenzo.data.PrefetchPlanner
import com.yashikota.omaigenzo.data.PrefetchPriority
import com.yashikota.omaigenzo.data.PrefetchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchEdgeCaseTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun indices(requests: List<PrefetchRequest>) = requests.map { it.index }

    // --- planning --------------------------------------------------------------------------------

    @Test
    fun aLargeBudgetStillPlansAtMostFourNeighbours() {
        val plan = PrefetchPlanner.plan(currentIndex = 5, totalSize = 20, direction = 1, maxEntries = 10)
        assertEquals(listOf(6, 7, 8, 4), indices(plan))
    }

    @Test
    fun anEmptyListPlansNothing() {
        assertTrue(PrefetchPlanner.plan(currentIndex = 0, totalSize = 0, direction = 1, maxEntries = 3).isEmpty())
    }

    @Test
    fun aNegativeBudgetPlansNothing() {
        assertTrue(PrefetchPlanner.plan(currentIndex = 3, totalSize = 10, direction = 1, maxEntries = -1).isEmpty())
    }

    @Test
    fun aZeroDirectionCountsAsForward() {
        assertEquals(
            indices(PrefetchPlanner.plan(5, 20, direction = 1, maxEntries = 4)),
            indices(PrefetchPlanner.plan(5, 20, direction = 0, maxEntries = 4)),
        )
    }

    @Test
    fun anyNegativeDirectionCountsAsBackward() {
        assertEquals(listOf(4, 3, 2, 6), indices(PrefetchPlanner.plan(5, 20, direction = -42, maxEntries = 4)))
    }

    @Test
    fun onTheCompletionScreenOnlyTheLastPhotoIsWarmedForUndo() {
        // After the last swipe currentIndex == totalSize; undo goes back to the last photo.
        val plan = PrefetchPlanner.plan(currentIndex = 10, totalSize = 10, direction = 1, maxEntries = 4)

        assertEquals(listOf(9), indices(plan))
        assertEquals(PrefetchPriority.PREVIOUS, plan.single().priority)
    }

    @Test
    fun goingBackwardsAtTheStartOnlyLooksForward() {
        val plan = PrefetchPlanner.plan(currentIndex = 0, totalSize = 10, direction = -1, maxEntries = 4)

        assertEquals(listOf(1), indices(plan))
        assertEquals(PrefetchPriority.PREVIOUS, plan.single().priority)
    }

    @Test
    fun aTwoPhotoListPrefetchesTheOtherOne() {
        assertEquals(listOf(1), indices(PrefetchPlanner.plan(0, 2, 1, maxEntries = 4)))
        assertEquals(listOf(0), indices(PrefetchPlanner.plan(1, 2, 1, maxEntries = 4)))
    }

    @Test
    fun priorityLabelsFollowThePlanOrder() {
        val plan = PrefetchPlanner.plan(currentIndex = 10, totalSize = 20, direction = 1, maxEntries = 4)
        assertEquals(
            listOf(PrefetchPriority.IMMEDIATE_NEXT, PrefetchPriority.LOOKAHEAD, PrefetchPriority.LOOKAHEAD, PrefetchPriority.PREVIOUS),
            plan.map { it.priority },
        )
    }

    @Test
    fun planIndicesAreAlwaysInsideTheListAndNeverTheCurrentPhoto() {
        for (size in 0..6) {
            for (current in 0..size) {
                for (direction in listOf(-1, 1)) {
                    val plan = PrefetchPlanner.plan(current, size, direction, maxEntries = 4)
                    assertTrue(plan.all { it.index in 0 until size && it.index != current })
                    assertEquals("no duplicates", plan.size, plan.map { it.index }.toSet().size)
                }
            }
        }
    }

    // --- coordinator -----------------------------------------------------------------------------

    @Test
    fun changingMaxEntriesAppliesFromTheNextUpdate() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.maxEntries = 1
        coordinator.updateCurrentIndex(5, 20)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(6), indices(seen))
    }

    @Test
    fun withoutACallbackUpdatesAreHarmless() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)

        coordinator.updateCurrentIndex(0, 10)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, coordinator.latestActiveIndex)
    }

    @Test
    fun theLatestIndexIsTrackedEvenWhenThereIsNothingToPrefetch() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)

        coordinator.updateCurrentIndex(4, 5)
        coordinator.updateCurrentIndex(0, 1)

        assertEquals(0, coordinator.latestActiveIndex)
    }

    @Test
    fun anEmptyPlanStillCancelsTheOlderGeneration() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 3)
        val seen = mutableListOf<PrefetchRequest>()
        coordinator.onPrefetchRequested = { seen += it }

        coordinator.updateCurrentIndex(0, 10)
        coordinator.updateCurrentIndex(0, 1) // the list shrank to a single photo
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun swipingForwardAgainAfterAnUndoLooksAheadAgain() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 1)
        val seen = mutableListOf<Int>()
        coordinator.onPrefetchRequested = { seen += it.index }

        coordinator.updateCurrentIndex(10, 50)
        coordinator.updateCurrentIndex(9, 50)
        coordinator.updateCurrentIndex(10, 50)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(11), seen)
    }

    @Test
    fun stayingOnTheSamePhotoIsTreatedAsForward() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 1)
        val seen = mutableListOf<Int>()
        coordinator.onPrefetchRequested = { seen += it.index }

        coordinator.updateCurrentIndex(10, 50)
        coordinator.updateCurrentIndex(10, 50)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(11), seen)
    }

    @Test
    fun theCoordinatorKeepsWorkingAfterCancelAll() = runTest(testDispatcher) {
        val coordinator = PrefetchCoordinator(testDispatcher, maxEntries = 2)
        val seen = mutableListOf<Int>()
        coordinator.onPrefetchRequested = { seen += it.index }

        coordinator.updateCurrentIndex(0, 10)
        coordinator.cancelAll()
        coordinator.updateCurrentIndex(1, 10)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(2, 3), seen)
    }

    @Test
    fun cancelAllBeforeAnyUpdateIsANoOp() {
        PrefetchCoordinator(testDispatcher).cancelAll()
    }
}
