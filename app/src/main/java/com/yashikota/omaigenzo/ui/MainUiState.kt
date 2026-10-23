package com.yashikota.omaigenzo.ui

import android.net.Uri
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.data.ExportOptions
import com.yashikota.omaigenzo.data.ExportProgress

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
    /** Non-null while an export runs. */
    val exportProgress: ExportProgress? = null,
    /** The most recent gallery bulk change, offered for undo until the next one or until dismissed. */
    val bulkChange: BulkChange? = null,
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

data class BulkChange(
    val count: Int,
    val state: SelectionState,
    /** Each changed photo's state before the change. */
    val previous: Map<String, SelectionState>,
)

sealed interface MainUiAction {
    data class SelectFolder(val folderUri: Uri) : MainUiAction
    data class SwipeAccept(val photo: PhotoItem) : MainUiAction
    data class SwipeReject(val photo: PhotoItem) : MainUiAction
    data class SwipeSkip(val photo: PhotoItem) : MainUiAction
    data object SwipeUndo : MainUiAction
    data object ResumePending : MainUiAction
    data class NavigateTo(val screen: ScreenState) : MainUiAction
    data class ExportAcceptedPhotos(val outputUri: Uri, val options: ExportOptions = ExportOptions()) : MainUiAction
    data object CancelExport : MainUiAction
    data class ChangePhotoSelection(val photoId: String, val newState: SelectionState) : MainUiAction
    data class ChangeSelectionForMany(val photoIds: Set<String>, val newState: SelectionState) : MainUiAction
    data object UndoBulkChange : MainUiAction
    data object DismissBulkChange : MainUiAction
    data class SetRating(val photoId: String, val rating: Int) : MainUiAction
    data class StartSelectionAt(val photoId: String) : MainUiAction
    data object ResetSession : MainUiAction
    data object ClearError : MainUiAction
    data class ShowError(val message: String) : MainUiAction
    data object ClearExportMessage : MainUiAction
}
