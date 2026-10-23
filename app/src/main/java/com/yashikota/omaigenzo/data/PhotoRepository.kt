package com.yashikota.omaigenzo.data

import android.net.Uri
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.flow.StateFlow

interface PhotoRepository {
    val photos: StateFlow<List<PhotoItem>>
    val lastStateChangeTime: StateFlow<Long>

    suspend fun importSession(folderUri: Uri): Result<Unit>
    fun updateSelection(photoId: String, state: SelectionState)
    fun undoLastSelection(): Boolean
    suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int>
    fun clearSession()

    /** Sets the star rating (0..[PhotoItem.MAX_RATING]) of one photo. */
    fun updateRating(photoId: String, rating: Int) = Unit

    /** Sets [state] on many photos as one change. Returns how many photos changed. */
    fun updateSelections(photoIds: Collection<String>, state: SelectionState): Int {
        photoIds.forEach { updateSelection(it, state) }
        return photoIds.size
    }

    /**
     * Exports kept photos with [options]. Progress is reported from a background thread; cancel the
     * calling coroutine to stop.
     */
    suspend fun exportAcceptedPhotos(
        outputUri: Uri,
        options: ExportOptions,
        onProgress: (ExportProgress) -> Unit,
    ): Result<ExportReport> = exportAcceptedPhotos(outputUri).map {
        ExportReport(exportedPhotos = it)
    }
}
