package com.yashikota.omaigenzo.ui

import android.net.Uri
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState

enum class ScreenState {
    FOLDER_SELECT,
    SWIPE_SELECTION,
    GALLERY,
    COMPLETION,
}

data class MainUiState(
    val currentScreen: ScreenState = ScreenState.FOLDER_SELECT,
    val photos: List<PhotoItem> = emptyList(),
    val currentIndex: Int = 0,
    val isImporting: Boolean = false,
    val isExporting: Boolean = false,
    val errorMessage: String? = null,
    val exportSuccessMessage: String? = null,
) {
    val currentPhoto: PhotoItem?
        get() = photos.getOrNull(currentIndex)

    val acceptCount: Int
        get() = photos.count { it.selectionState == SelectionState.ACCEPT }

    val rejectCount: Int
        get() = photos.count { it.selectionState == SelectionState.REJECT }

    val pendingCount: Int
        get() = photos.count { it.selectionState == SelectionState.PENDING }

    val totalCount: Int
        get() = photos.size
}

sealed interface MainUiAction {
    data class SelectFolder(val folderUri: Uri) : MainUiAction
    data class SwipeAccept(val photo: PhotoItem) : MainUiAction
    data class SwipeReject(val photo: PhotoItem) : MainUiAction
    data class SwipeSkip(val photo: PhotoItem) : MainUiAction
    data object SwipeUndo : MainUiAction
    data class NavigateTo(val screen: ScreenState) : MainUiAction
    data class ExportAcceptedPhotos(val outputUri: Uri) : MainUiAction
    data class ChangePhotoSelection(val photoId: String, val newState: SelectionState) : MainUiAction
    data object ResetSession : MainUiAction
    data object ClearError : MainUiAction
    data object ClearExportMessage : MainUiAction
}
