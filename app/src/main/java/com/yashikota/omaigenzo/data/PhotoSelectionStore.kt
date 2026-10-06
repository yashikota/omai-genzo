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
    private val history = ArrayDeque<Pair<String, SelectionState>>()

    fun replace(newPhotos: List<PhotoItem>) {
        photos = newPhotos
        indexById = buildIndex(newPhotos)
        history.clear()
    }

    fun clear() = replace(emptyList())

    /** Returns false when [photoId] is unknown. */
    fun update(photoId: String, state: SelectionState): Boolean {
        val index = indexById[photoId] ?: return false
        val old = photos[index]
        history.push(old.id to old.selectionState)
        if (history.size > maxUndoDepth) history.removeLast()
        publish(index, old.copy(selectionState = state))
        return true
    }

    fun undo(): Boolean {
        val (photoId, previous) = history.pollFirst() ?: return false
        val index = indexById[photoId] ?: return false
        publish(index, photos[index].copy(selectionState = previous))
        return true
    }

    private fun publish(index: Int, updated: PhotoItem) {
        // A fresh list per change: readers on other threads never see a half-updated one.
        val next = ArrayList(photos)
        next[index] = updated
        photos = next
    }

    private fun buildIndex(list: List<PhotoItem>): Map<String, Int> {
        val map = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { index, photo -> map[photo.id] = index }
        return map
    }
}
