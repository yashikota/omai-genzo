package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.PhotoSelectionStore
import com.yashikota.omaigenzo.data.RatingCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingAndBulkSelectionTest {

    private fun photos(count: Int) = List(count) { PhotoItem(id = "p$it", baseName = "IMG_$it") }

    @Test
    fun ratingIsClampedAndUndoable() {
        val store = PhotoSelectionStore(photos(2))

        assertTrue(store.updateRating("p0", 9))
        assertEquals(PhotoItem.MAX_RATING, store.photos[0].rating)
        assertTrue(store.updateRating("p0", -3))
        assertEquals(0, store.photos[0].rating)

        assertTrue(store.undo())
        assertEquals(PhotoItem.MAX_RATING, store.photos[0].rating)
        assertTrue(store.undo())
        assertEquals(0, store.photos[0].rating)
    }

    @Test
    fun ratingTheSameValueAgainIsNotAChange() {
        val store = PhotoSelectionStore(photos(1))
        store.updateRating("p0", 3)
        val before = store.photos

        assertFalse(store.updateRating("p0", 3))
        assertSame(before, store.photos)
        assertFalse(store.updateRating("missing", 2))
    }

    @Test
    fun ratingKeepsTheSelectionDecision() {
        val store = PhotoSelectionStore(photos(1))
        store.update("p0", SelectionState.ACCEPT)
        store.updateRating("p0", 4)

        assertEquals(SelectionState.ACCEPT, store.photos[0].selectionState)
        assertEquals(4, store.photos[0].rating)
    }

    @Test
    fun bulkUpdateChangesEveryKnownPhotoInOneUndoStep() {
        val store = PhotoSelectionStore(photos(5))
        store.update("p1", SelectionState.REJECT)

        val changed = store.updateMany(listOf("p0", "p1", "p2", "missing"), SelectionState.ACCEPT)

        assertEquals(3, changed)
        assertEquals(
            listOf(SelectionState.ACCEPT, SelectionState.ACCEPT, SelectionState.ACCEPT, SelectionState.PENDING, SelectionState.PENDING),
            store.photos.map { it.selectionState },
        )

        assertTrue(store.undo())
        assertEquals(
            listOf(SelectionState.PENDING, SelectionState.REJECT, SelectionState.PENDING, SelectionState.PENDING, SelectionState.PENDING),
            store.photos.map { it.selectionState },
        )
    }

    @Test
    fun bulkUpdateSkipsPhotosAlreadyInTheTargetState() {
        val store = PhotoSelectionStore(photos(3))
        store.update("p0", SelectionState.REJECT)
        val before = store.photos

        // A single id is an explicit swipe and is always recorded, matching the old update().
        assertEquals(1, store.updateMany(listOf("p0"), SelectionState.REJECT))
        assertEquals(1, store.updateMany(listOf("p0", "p2"), SelectionState.REJECT))
        assertEquals(SelectionState.REJECT, store.photos[2].selectionState)
        assertEquals(0, store.updateMany(listOf("p0", "p2"), SelectionState.REJECT))
        assertEquals(SelectionState.REJECT, before[0].selectionState)
    }

    @Test
    fun bulkUpdateWithNothingToChangeLeavesTheListAndHistoryAlone() {
        val store = PhotoSelectionStore(photos(2))
        val before = store.photos

        assertEquals(0, store.updateMany(emptyList(), SelectionState.ACCEPT))
        assertEquals(0, store.updateMany(listOf("x", "y"), SelectionState.ACCEPT))

        assertSame(before, store.photos)
        assertFalse(store.undo())
    }

    @Test
    fun ratingCodecRoundTrips() {
        val ratings = listOf(0, 1, 2, 3, 4, 5, 0)
        val encoded = RatingCodec.encode(ratings)

        assertEquals("0123450", encoded)
        assertArrayEquals(ratings.toIntArray(), RatingCodec.decode(encoded, ratings.size))
    }

    @Test
    fun ratingCodecClampsOnWriteAndRejectsBadSnapshots() {
        assertEquals("05", RatingCodec.encode(listOf(-1, 42)))
        assertNull(RatingCodec.decode("012", 4))
        assertNull(RatingCodec.decode("0A2", 3))
        assertNull(RatingCodec.decode("069", 3))
    }
}
