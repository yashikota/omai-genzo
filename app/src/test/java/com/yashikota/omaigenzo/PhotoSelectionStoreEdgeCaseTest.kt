package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PhotoSelectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSelectionStoreEdgeCaseTest {

    private fun photos(count: Int) = List(count) { PhotoItem(id = "p$it", baseName = "IMG_$it") }

    private fun PhotoSelectionStore.states() = photos.map { it.selectionState }

    @Test
    fun anEmptyStoreRejectsEveryUpdate() {
        val store = PhotoSelectionStore()

        assertFalse(store.update("p0", SelectionState.ACCEPT))
        assertFalse(store.undo())
        assertTrue(store.photos.isEmpty())
    }

    @Test
    fun clearDropsPhotosAndHistory() {
        val store = PhotoSelectionStore(photos(2))
        store.update("p0", SelectionState.ACCEPT)

        store.clear()

        assertTrue(store.photos.isEmpty())
        assertFalse(store.undo())
        assertFalse(store.update("p0", SelectionState.REJECT))
    }

    @Test
    fun undoWalksBackAcrossDifferentPhotosInReverseOrder() {
        val store = PhotoSelectionStore(photos(3))
        store.update("p0", SelectionState.ACCEPT)
        store.update("p1", SelectionState.REJECT)
        store.update("p2", SelectionState.ACCEPT)

        assertTrue(store.undo())
        assertEquals(listOf(SelectionState.ACCEPT, SelectionState.REJECT, SelectionState.PENDING), store.states())
        assertTrue(store.undo())
        assertEquals(listOf(SelectionState.ACCEPT, SelectionState.PENDING, SelectionState.PENDING), store.states())
        assertTrue(store.undo())
        assertEquals(List(3) { SelectionState.PENDING }, store.states())
        assertFalse(store.undo())
    }

    @Test
    fun repeatingTheSameDecisionIsStillOneUndoStepEach() {
        val store = PhotoSelectionStore(photos(1))
        store.update("p0", SelectionState.ACCEPT)
        store.update("p0", SelectionState.ACCEPT)

        assertTrue(store.undo())
        assertEquals(SelectionState.ACCEPT, store.photos[0].selectionState)
        assertTrue(store.undo())
        assertEquals(SelectionState.PENDING, store.photos[0].selectionState)
    }

    @Test
    fun aZeroUndoDepthKeepsNoHistory() {
        val store = PhotoSelectionStore(photos(2), maxUndoDepth = 0)
        store.update("p0", SelectionState.ACCEPT)

        assertFalse(store.undo())
        assertEquals(SelectionState.ACCEPT, store.photos[0].selectionState)
    }

    @Test
    fun theBoundedHistoryForgetsTheOldestStepsFirst() {
        val store = PhotoSelectionStore(photos(4), maxUndoDepth = 2)
        store.update("p0", SelectionState.ACCEPT)
        store.update("p1", SelectionState.ACCEPT)
        store.update("p2", SelectionState.ACCEPT)

        assertTrue(store.undo())
        assertTrue(store.undo())
        assertFalse(store.undo())
        // The step that fell off the history stays applied.
        assertEquals(SelectionState.ACCEPT, store.photos[0].selectionState)
        assertEquals(SelectionState.PENDING, store.photos[1].selectionState)
        assertEquals(SelectionState.PENDING, store.photos[2].selectionState)
    }

    @Test
    fun anUpdateTouchesOnlyItsOwnPhoto() {
        val store = PhotoSelectionStore(photos(5))
        val before = store.photos

        store.update("p2", SelectionState.REJECT)

        for (i in 0 until 5) {
            if (i == 2) continue
            assertSame("unchanged photos are shared, not copied", before[i], store.photos[i])
        }
        assertEquals(SelectionState.REJECT, store.photos[2].selectionState)
    }

    @Test
    fun undoPublishesANewListToo() {
        val store = PhotoSelectionStore(photos(2))
        store.update("p0", SelectionState.ACCEPT)
        val afterUpdate = store.photos

        store.undo()

        assertEquals(SelectionState.ACCEPT, afterUpdate[0].selectionState)
        assertEquals(SelectionState.PENDING, store.photos[0].selectionState)
    }

    @Test
    fun theOtherFieldsSurviveASelectionChange() {
        val original = PhotoItem(
            id = "x",
            baseName = "DSC0001",
            rawUriString = "content://raw",
            jpgUriString = "content://jpg",
            rawExtension = "arw",
            jpgExtension = "jpg",
            fileSize = 42L,
            modifiedAt = 7L,
            fileType = PhotoType.RAW_AND_JPEG,
        )
        val store = PhotoSelectionStore(listOf(original))

        store.update("x", SelectionState.REJECT)

        assertEquals(original.copy(selectionState = SelectionState.REJECT), store.photos[0])
    }
}
