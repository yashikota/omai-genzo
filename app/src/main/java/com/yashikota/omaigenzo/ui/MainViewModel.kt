package com.yashikota.omaigenzo.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.data.PhotoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: PhotoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.photos.collect { photoList ->
                _uiState.update { state ->
                    val newIndex = state.currentIndex.coerceAtMost((photoList.size - 1).coerceAtLeast(0))
                    state.copy(photos = photoList, currentIndex = newIndex)
                }
            }
        }
    }

    fun onAction(action: MainUiAction) {
        when (action) {
            is MainUiAction.SelectFolder -> importFolder(action.folderUri)
            is MainUiAction.SwipeAccept -> handleSwipe(action.photo, SelectionState.ACCEPT)
            is MainUiAction.SwipeReject -> handleSwipe(action.photo, SelectionState.REJECT)
            is MainUiAction.SwipeSkip -> handleSkip()
            is MainUiAction.SwipeUndo -> handleUndo()
            is MainUiAction.NavigateTo -> navigateTo(action.screen)
            is MainUiAction.ExportAcceptedPhotos -> exportAcceptedPhotos(action.outputUri)
            is MainUiAction.ChangePhotoSelection -> updateSelection(action.photoId, action.newState)
            is MainUiAction.ResetSession -> resetSession()
            is MainUiAction.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is MainUiAction.ClearExportMessage -> _uiState.update { it.copy(exportSuccessMessage = null) }
        }
    }

    private fun importFolder(folderUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            val result = repository.importSession(folderUri)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        currentIndex = 0,
                        currentScreen = ScreenState.SWIPE_SELECTION,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "フォルダの取り込みに失敗しました: ${error.message}",
                    )
                }
            }
        }
    }

    private fun handleSwipe(photo: PhotoItem, state: SelectionState) {
        repository.updateSelection(photo.id, state)
        advanceToNextPhoto()
    }

    private fun handleSkip() {
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
        val restored = repository.undoLastSelection()
        if (restored) {
            _uiState.update { state ->
                val prevIndex = (state.currentIndex - 1).coerceAtLeast(0)
                state.copy(currentIndex = prevIndex)
            }
        } else {
            _uiState.update { state ->
                val prevIndex = (state.currentIndex - 1).coerceAtLeast(0)
                state.copy(currentIndex = prevIndex)
            }
        }
    }

    private fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    private fun updateSelection(photoId: String, newState: SelectionState) {
        repository.updateSelection(photoId, newState)
    }

    private fun exportAcceptedPhotos(outputUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val result = repository.exportAcceptedPhotos(outputUri)
            result.onSuccess { count ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportSuccessMessage = "$count 件のキープ写真を保存しました！",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "書き出しに失敗しました: ${error.message}",
                    )
                }
            }
        }
    }

    private fun resetSession() {
        repository.clearSession()
        _uiState.update {
            MainUiState(
                currentScreen = ScreenState.SWIPE_SELECTION,
                photos = repository.photos.value,
                currentIndex = 0,
            )
        }
    }
}
