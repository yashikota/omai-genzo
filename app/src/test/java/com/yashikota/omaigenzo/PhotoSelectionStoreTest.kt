package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PhotoSelectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSelectionStoreTest {

    private fun photos(count: Int) = List(count) { PhotoItem(id = "p$it", baseName = "IMG_$it") }

    @Test
    fun updatePublishesANewListAndLeavesThePreviousOneUntouched() {
        val store = PhotoSelectionStore(photos(3))
        val before = store.photos

        assertTrue(store.update("p1", SelectionState.ACCEPT))

        assertNotSame(before, store.photos)
        assertEquals(SelectionState.PENDING, before[1].selectionState)
        assertEquals(SelectionState.ACCEPT, store.photos[1].selectionState)
        assertEquals(SelectionState.PENDING, store.photos[0].selectionState)
    }

    @Test
    fun unknownIdsChangeNothing() {
        val store = PhotoSelectionStore(photos(3))
        val before = store.photos

        assertFalse(store.update("missing", SelectionState.REJECT))

        assertSame(before, store.photos)
        assertFalse(store.undo())
    }

    @Test
    fun undoRestoresTheMostRecentChangeFirst() {
        val store = PhotoSelectionStore(photos(3))
        store.update("p0", SelectionState.ACCEPT)
        store.update("p0", SelectionState.REJECT)

        assertTrue(store.undo())
        assertEquals(SelectionState.ACCEPT, store.photos[0].selectionState)
        assertTrue(store.undo())
        assertEquals(SelectionState.PENDING, store.photos[0].selectionState)
        assertFalse(store.undo())
    }

    @Test
    fun undoHistoryIsBoundedSoLongSessionsDoNotLeak() {
        val store = PhotoSelectionStore(photos(10), maxUndoDepth = 5)
        repeat(50) { store.update("p${it % 10}", SelectionState.ACCEPT) }

        var undone = 0
        while (store.undo()) undone++

        assertEquals(5, undone)
    }

    @Test
    fun replaceResetsHistoryAndTheIndex() {
        val store = PhotoSelectionStore(photos(3))
        store.update("p0", SelectionState.ACCEPT)

        store.replace(List(2) { PhotoItem(id = "n$it", baseName = "N$it") })

        assertFalse(store.undo())
        assertFalse(store.update("p0", SelectionState.ACCEPT))
        assertTrue(store.update("n1", SelectionState.REJECT))
        assertEquals(2, store.photos.size)
    }

    @Test
    fun aSwipeCostsMicrosecondsEvenForHugeSessions() {
        val size = 50_000
        val store = PhotoSelectionStore(photos(size))
        repeat(200) { store.update("p${it * 7}", SelectionState.ACCEPT) } // warm up

        val swipes = 2_000
        val startedAt = System.nanoTime()
        repeat(swipes) { store.update("p${(it * 13) % size}", SelectionState.REJECT) }
        val perSwipeMicros = (System.nanoTime() - startedAt) / swipes / 1_000.0

        // The old path rebuilt a JSON document of every photo on the UI thread (tens of ms).
        assertTrue("one swipe on 50k photos took ${perSwipeMicros}us", perSwipeMicros < 1_500.0)
    }
}
