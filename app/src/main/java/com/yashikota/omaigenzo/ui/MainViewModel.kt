package com.yashikota.omaigenzo.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.data.ExportOptions
import com.yashikota.omaigenzo.data.ExportProgress
import com.yashikota.omaigenzo.data.ExportReport
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: PhotoRepository,
) : ViewModel() {

    private data class SwipeHistory(
        val index: Int,
        val photoId: String,
        val previousState: SelectionState?,
    )

    private val swipeHistory = ArrayDeque<SwipeHistory>()
    private var exportJob: Job? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.photos.collect { photoList ->
                _uiState.update { state ->
                    val newIndex = state.currentIndex.coerceAtMost((photoList.size - 1).coerceAtLeast(0))
                    state.copy(
                        photos = photoList,
                        currentIndex = newIndex,
                        currentScreen = if (state.currentScreen == ScreenState.FOLDER_SELECT && photoList.isNotEmpty()) {
                            ScreenState.SWIPE_SELECTION
                        } else {
                            state.currentScreen
                        },
                    )
                }
            }
        }
    }

    fun onAction(action: MainUiAction) {
        PerfLogger.event("ui_action", "\"action\":\"${PerfLogger.escape(action.toString())}\"")
        when (action) {
            is MainUiAction.SelectFolder -> importFolder(action.folderUri)
            is MainUiAction.SwipeAccept -> handleSwipe(action.photo, SelectionState.ACCEPT)
            is MainUiAction.SwipeReject -> handleSwipe(action.photo, SelectionState.REJECT)
            is MainUiAction.SwipeSkip -> handleSkip()
            is MainUiAction.SwipeUndo -> handleUndo()
            is MainUiAction.ResumePending -> resumePending()
            is MainUiAction.NavigateTo -> navigateTo(action.screen)
            is MainUiAction.ExportAcceptedPhotos -> exportAcceptedPhotos(action.outputUri, action.options)
            is MainUiAction.CancelExport -> exportJob?.cancel()
            is MainUiAction.ChangePhotoSelection -> updateSelection(action.photoId, action.newState)
            is MainUiAction.ChangeSelectionForMany -> updateSelectionForMany(action.photoIds, action.newState)
            is MainUiAction.UndoBulkChange -> undoBulkChange()
            is MainUiAction.DismissBulkChange -> _uiState.update { it.copy(bulkChange = null) }
            is MainUiAction.SetRating -> repository.updateRating(action.photoId, action.rating)
            is MainUiAction.StartSelectionAt -> startSelectionAt(action.photoId)
            is MainUiAction.ResetSession -> resetSession()
            is MainUiAction.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is MainUiAction.ShowError -> _uiState.update { it.copy(errorMessage = action.message) }
            is MainUiAction.ClearExportMessage -> _uiState.update { it.copy(exportSuccessMessage = null) }
        }
    }

    private fun importFolder(folderUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            val result = repository.importSession(folderUri)
            result.onSuccess {
                swipeHistory.clear()
                _uiState.update { state ->
                    if (state.photos.isEmpty()) {
                        state.copy(
                            isImporting = false,
                            currentScreen = ScreenState.FOLDER_SELECT,
                            errorMessage = "このフォルダに対応する写真（RAW・JPEG）が見つかりませんでした",
                        )
                    } else {
                        state.copy(isImporting = false, currentIndex = 0, currentScreen = ScreenState.SWIPE_SELECTION)
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "フォルダを読み込めませんでした（${error.message}）",
                    )
                }
            }
        }
    }

    private fun handleSwipe(photo: PhotoItem, state: SelectionState) {
        swipeHistory.addLast(SwipeHistory(_uiState.value.currentIndex, photo.id, photo.selectionState))
        repository.updateSelection(photo.id, state)
        advanceToNextPhoto()
    }

    private fun handleSkip() {
        val state = _uiState.value
        state.currentPhoto?.let { swipeHistory.addLast(SwipeHistory(state.currentIndex, it.id, null)) }
        advanceToNextPhoto()
    }

    private fun advanceToNextPhoto() {
        _uiState.update { state ->
            val nextIndex = state.currentIndex + 1
            if (nextIndex >= state.photos.size) {
                state.copy(currentIndex = nextIndex, currentScreen = ScreenState.COMPLETION)
            } else {
                state.copy(currentIndex = nextIndex)
            }
        }
    }

    private fun handleUndo() {
        val operation = swipeHistory.removeLastOrNull() ?: return
        operation.previousState?.let { repository.updateSelection(operation.photoId, it) }
        _uiState.update { it.copy(currentIndex = operation.index, currentScreen = ScreenState.SWIPE_SELECTION) }
    }

    private fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    private fun resumePending() {
        _uiState.update { state ->
            val index = state.photos.indexOfFirst { it.selectionState == SelectionState.PENDING }
            state.copy(currentIndex = index.coerceAtLeast(0), currentScreen = ScreenState.SWIPE_SELECTION)
        }
    }

    private fun updateSelection(photoId: String, newState: SelectionState) {
        repository.updateSelection(photoId, newState)
    }

    private fun exportAcceptedPhotos(outputUri: Uri, options: ExportOptions) = runExport { onProgress ->
        repository.exportAcceptedPhotos(outputUri, options, onProgress)
    }

    /** Runs one export at a time, mirroring its progress into the UI state until it ends or is cancelled. */
    internal fun runExport(export: suspend (onProgress: (ExportProgress) -> Unit) -> Result<ExportReport>) {
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportProgress = ExportProgress(0, 0)) }
            val result = try {
                export { progress -> _uiState.update { it.copy(exportProgress = progress) } }
            } catch (e: CancellationException) {
                // withContext rethrows on cancellation, so the last progress is the best count we have.
                val done = _uiState.value.exportProgress?.completedPhotos ?: 0
                Result.success(ExportReport(exportedPhotos = done, cancelled = true))
            }
            result.onSuccess { report ->
                _uiState.update {
                    it.copy(isExporting = false, exportProgress = null, exportSuccessMessage = exportMessage(report))
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = null,
                        errorMessage = "書き出せませんでした（${error.message}）",
                    )
                }
            }
        }
    }

    private fun updateSelectionForMany(photoIds: Set<String>, newState: SelectionState) {
        if (photoIds.isEmpty()) return
        val previous = HashMap<String, SelectionState>(photoIds.size * 2)
        for (photo in _uiState.value.photos) {
            if (photo.id in photoIds && photo.selectionState != newState) previous[photo.id] = photo.selectionState
        }
        if (previous.isEmpty()) return
        repository.updateSelections(previous.keys, newState)
        _uiState.update { it.copy(bulkChange = BulkChange(previous.size, newState, previous)) }
    }

    private fun undoBulkChange() {
        val change = _uiState.value.bulkChange ?: return
        change.previous.entries.groupBy({ it.value }, { it.key }).forEach { (state, ids) ->
            repository.updateSelections(ids, state)
        }
        _uiState.update { it.copy(bulkChange = null) }
    }

    private fun startSelectionAt(photoId: String) {
        _uiState.update { state ->
            val index = state.photos.indexOfFirst { it.id == photoId }
            if (index < 0) state else state.copy(currentIndex = index, currentScreen = ScreenState.SWIPE_SELECTION)
        }
    }

    private fun resetSession() {
        exportJob?.cancel()
        repository.clearSession()
        swipeHistory.clear()
        _uiState.update {
            MainUiState(
                currentScreen = ScreenState.FOLDER_SELECT,
                photos = repository.photos.value,
                currentIndex = 0,
            )
        }
    }

    companion object {
        fun exportMessage(report: ExportReport): String = buildString {
            if (report.cancelled) {
                append("書き出しを中止しました（${report.exportedPhotos} 枚は保存済み）")
                return@buildString
            }
            append("キープした ${report.exportedPhotos} 枚を書き出しました")
            val notes = buildList {
                if (report.skippedFiles > 0) add("${report.skippedFiles} 件は同名ファイルがあるためスキップ")
                if (report.failedFiles > 0) add("${report.failedFiles} 件は失敗")
            }
            if (notes.isNotEmpty()) append("（").append(notes.joinToString("、")).append("）")
        }
    }
}
