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
}
