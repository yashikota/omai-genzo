package com.yashikota.omaigenzo

import android.net.Uri
import com.yashikota.omaigenzo.data.PhotoRepository
import com.yashikota.omaigenzo.ui.MainUiAction
import com.yashikota.omaigenzo.ui.MainViewModel
import com.yashikota.omaigenzo.ui.ScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakePhotoRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakePhotoRepository()
        viewModel = MainViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testNavigationAction() = runTest {
        assertEquals(ScreenState.FOLDER_SELECT, viewModel.uiState.value.currentScreen)
        viewModel.onAction(MainUiAction.NavigateTo(ScreenState.GALLERY))
        assertEquals(ScreenState.GALLERY, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun testSwipeAcceptAdvancesIndex() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw"),
            PhotoItem("2", "DSC0002", rawPath = "/tmp/2.arw"),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.currentIndex)
        viewModel.onAction(MainUiAction.SwipeAccept(samplePhotos[0]))
        assertEquals(1, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun testSwipeSkipNavigatesToCompletionOnLastPhoto() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw"),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAction(MainUiAction.SwipeSkip(samplePhotos[0]))
        assertEquals(ScreenState.COMPLETION, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun testSwipeRejectAdvancesIndex() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw"),
            PhotoItem("2", "DSC0002", rawPath = "/tmp/2.arw"),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAction(MainUiAction.SwipeReject(samplePhotos[0]))
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(SelectionState.REJECT, fakeRepository.photos.value[0].selectionState)
    }

    @Test
    fun testSwipeUndoRestoresPreviousIndex() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw"),
            PhotoItem("2", "DSC0002", rawPath = "/tmp/2.arw"),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAction(MainUiAction.SwipeAccept(samplePhotos[0]))
        assertEquals(1, viewModel.uiState.value.currentIndex)

        viewModel.onAction(MainUiAction.SwipeUndo)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun testChangePhotoSelectionInGallery() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw", selectionState = SelectionState.PENDING),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAction(MainUiAction.ChangePhotoSelection("1", SelectionState.ACCEPT))
        assertEquals(SelectionState.ACCEPT, fakeRepository.photos.value[0].selectionState)
    }

    @Test
    fun testResetSession() = runTest {
        val samplePhotos = listOf(
            PhotoItem("1", "DSC0001", rawPath = "/tmp/1.arw"),
        )
        fakeRepository.setPhotos(samplePhotos)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAction(MainUiAction.ResetSession)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(0, viewModel.uiState.value.photos.size)
    }

    private class FakePhotoRepository : PhotoRepository {
        private val photosState = MutableStateFlow<List<PhotoItem>>(emptyList())
        override val photos: StateFlow<List<PhotoItem>> = photosState

        private val lastChangeState = MutableStateFlow(System.currentTimeMillis())
        override val lastStateChangeTime: StateFlow<Long> = lastChangeState

        fun setPhotos(list: List<PhotoItem>) {
            photosState.value = list
        }

        override suspend fun importSession(folderUri: Uri): Result<Unit> = Result.success(Unit)
        override fun updateSelection(photoId: String, state: SelectionState) {
            val list = photosState.value.toMutableList()
            val idx = list.indexOfFirst { it.id == photoId }
            if (idx != -1) {
                list[idx] = list[idx].copy(selectionState = state)
                photosState.value = list
            }
        }

        override fun undoLastSelection(): Boolean = true
        override suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int> = Result.success(1)
        override fun clearSession() {
            photosState.value = emptyList()
        }
    }
}
