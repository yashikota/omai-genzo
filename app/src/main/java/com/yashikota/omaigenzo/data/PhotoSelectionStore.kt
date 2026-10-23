package com.yashikota.omaigenzo.data

import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import java.util.ArrayDeque

/**
 * In-memory selection state: O(1) lookup by id, copy-on-write lists that are safe to share with
 * other threads, and a bounded undo history. Persistence lives elsewhere.
 */
class PhotoSelectionStore(
    initial: List<PhotoItem> = emptyList(),
    private val maxUndoDepth: Int = 1_024,
) {
    var photos: List<PhotoItem> = initial
        private set

    private var indexById: Map<String, Int> = buildIndex(initial)

    // One entry per user operation. A bulk change is a single entry, so one undo reverts all of it.
    // The previous items are shared, never copied, so an entry costs one reference per photo.
    private val history = ArrayDeque<Array<PhotoItem>>()

    fun replace(newPhotos: List<PhotoItem>) {
        photos = newPhotos
        indexById = buildIndex(newPhotos)
        history.clear()
    }

    fun clear() = replace(emptyList())

    /** Returns false when [photoId] is unknown. */
    fun update(photoId: String, state: SelectionState): Boolean = updateMany(listOf(photoId), state) > 0

    /** Returns false when [photoId] is unknown. [rating] is clamped to 0..[PhotoItem.MAX_RATING]. */
    fun updateRating(photoId: String, rating: Int): Boolean {
        val index = indexById[photoId] ?: return false
        val old = photos[index]
        val clamped = rating.coerceIn(0, PhotoItem.MAX_RATING)
        if (old.rating == clamped) return false
        record(arrayOf(old))
        val next = ArrayList(photos)
        next[index] = old.copy(rating = clamped)
        photos = next
        return true
    }

    /**
     * Sets [state] on every known id in one copy of the list and one undo entry. Unknown ids and
     * photos already in [state] are ignored. Returns how many photos changed.
     */
    fun updateMany(photoIds: Collection<String>, state: SelectionState): Int {
        var next: ArrayList<PhotoItem>? = null
        val previous = ArrayList<PhotoItem>(minOf(photoIds.size, 64))
        for (id in photoIds) {
            val index = indexById[id] ?: continue
            val list = next ?: ArrayList(photos).also { next = it }
            val old = list[index]
            // A single explicit swipe is always recorded, even when it repeats the current state,
            // so undo stays in step with what the user did.
            if (old.selectionState == state && photoIds.size > 1) continue
            previous += photos[index]
            list[index] = old.copy(selectionState = state)
        }
        val changed = next ?: return 0
        if (previous.isEmpty()) return 0
        record(previous.toTypedArray())
        photos = changed
        return previous.size
    }

    fun undo(): Boolean {
        val entry = history.pollFirst() ?: return false
        // A fresh list per change: readers on other threads never see a half-updated one.
        val next = ArrayList(photos)
        var restored = false
        for (old in entry) {
            val index = indexById[old.id] ?: continue
            next[index] = old
            restored = true
        }
        if (restored) photos = next
        return restored
    }

    private fun record(previous: Array<PhotoItem>) {
        history.push(previous)
        if (history.size > maxUndoDepth) history.removeLast()
    }

    private fun buildIndex(list: List<PhotoItem>): Map<String, Int> {
        val map = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { index, photo -> map[photo.id] = index }
        return map
    }
}
