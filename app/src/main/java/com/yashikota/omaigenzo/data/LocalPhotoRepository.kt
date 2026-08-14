package com.yashikota.omaigenzo.data

import android.content.Context
import android.net.Uri
import com.yashikota.omaigenzo.FolderImportManager
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class LocalPhotoRepository(
    private val context: Context,
    private val zeroCopyScanner: ZeroCopyFolderScanner = ZeroCopyFolderScanner(context),
    private val importManager: FolderImportManager = FolderImportManager(context),
) : PhotoRepository {

    private val photosState = MutableStateFlow<List<PhotoItem>>(emptyList())
    override val photos: StateFlow<List<PhotoItem>> = photosState.asStateFlow()

    private val lastChangeState = MutableStateFlow(System.currentTimeMillis())
    override val lastStateChangeTime: StateFlow<Long> = lastChangeState.asStateFlow()

    private val historyStack = ArrayDeque<Pair<String, SelectionState>>()

    override suspend fun importSession(folderUri: Uri): Result<Unit> = try {
        val scanned = zeroCopyScanner.scanTreeUri(folderUri)
        photosState.value = scanned
        historyStack.clear()
        lastChangeState.value = System.currentTimeMillis()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun updateSelection(photoId: String, state: SelectionState) {
        val currentList = photosState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == photoId }
        if (index != -1) {
            val oldItem = currentList[index]
            historyStack.push(Pair(oldItem.id, oldItem.selectionState))
            currentList[index] = oldItem.copy(selectionState = state)
            photosState.value = currentList
            lastChangeState.value = System.currentTimeMillis()
        }
    }

    override fun undoLastSelection(): Boolean {
        if (historyStack.isEmpty()) return false
        val (photoId, previousState) = historyStack.pop()
        val currentList = photosState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == photoId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(selectionState = previousState)
            photosState.value = currentList
            lastChangeState.value = System.currentTimeMillis()
            return true
        }
        return false
    }

    override suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int> = try {
        val count = importManager.exportAcceptPhotosToUri(outputUri, photosState.value)
        Result.success(count)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun clearSession() {
        photosState.value = emptyList()
        historyStack.clear()
        lastChangeState.value = System.currentTimeMillis()
    }
}
