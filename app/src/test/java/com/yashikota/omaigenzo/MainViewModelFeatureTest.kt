package com.yashikota.omaigenzo

import android.net.Uri
import com.yashikota.omaigenzo.data.ExportProgress
import com.yashikota.omaigenzo.data.ExportReport
import com.yashikota.omaigenzo.data.PhotoRepository
import com.yashikota.omaigenzo.ui.MainUiAction
import com.yashikota.omaigenzo.ui.MainViewModel
import com.yashikota.omaigenzo.ui.ScreenState
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelFeatureTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingRepository
    private lateinit var viewModel: MainViewModel

    private val photos = listOf(
        PhotoItem("1", "one", selectionState = SelectionState.ACCEPT),
        PhotoItem("2", "two"),
        PhotoItem("3", "three", selectionState = SelectionState.REJECT),
        PhotoItem("4", "four"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = RecordingRepository()
        viewModel = MainViewModel(repository)
        repository.photosState.value = photos
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun states() = repository.photos.value.map { it.selectionState }

    @Test
    fun bulkChangeAppliesToChangedPhotosAndCanBeUndone() = runTest {
        viewModel.onAction(MainUiAction.ChangeSelectionForMany(setOf("1", "2", "3"), SelectionState.REJECT))

        assertEquals(listOf(SelectionState.REJECT, SelectionState.REJECT, SelectionState.REJECT, SelectionState.PENDING), states())
        val change = viewModel.uiState.value.bulkChange
        assertNotNull(change)
        // "3" was already rejected, so it is neither counted nor touched by undo.
        assertEquals(2, change!!.count)
        assertEquals(setOf("1", "2"), change.previous.keys)

        viewModel.onAction(MainUiAction.UndoBulkChange)

        assertEquals(photos.map { it.selectionState }, states())
        assertNull(viewModel.uiState.value.bulkChange)
    }

    @Test
    fun bulkChangeThatChangesNothingOffersNoUndo() = runTest {
        viewModel.onAction(MainUiAction.ChangeSelectionForMany(setOf("3"), SelectionState.REJECT))
        viewModel.onAction(MainUiAction.ChangeSelectionForMany(emptySet(), SelectionState.ACCEPT))

        assertNull(viewModel.uiState.value.bulkChange)
        assertEquals(0, repository.bulkCalls)
    }

    @Test
    fun dismissingTheBulkChangeKeepsIt() = runTest {
        viewModel.onAction(MainUiAction.ChangeSelectionForMany(setOf("2", "4"), SelectionState.ACCEPT))
        viewModel.onAction(MainUiAction.DismissBulkChange)
        viewModel.onAction(MainUiAction.UndoBulkChange)

        assertEquals(listOf(SelectionState.ACCEPT, SelectionState.ACCEPT, SelectionState.REJECT, SelectionState.ACCEPT), states())
    }

    @Test
    fun ratingIsForwardedToTheRepository() = runTest {
        viewModel.onAction(MainUiAction.SetRating("2", 4))

        assertEquals(4, repository.photos.value[1].rating)
    }

    @Test
    fun startSelectionAtJumpsToThatPhoto() = runTest {
        viewModel.onAction(MainUiAction.NavigateTo(ScreenState.GALLERY))
        viewModel.onAction(MainUiAction.StartSelectionAt("3"))

        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(ScreenState.SWIPE_SELECTION, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun startSelectionAtAnUnknownPhotoDoesNothing() = runTest {
        viewModel.onAction(MainUiAction.NavigateTo(ScreenState.GALLERY))
        viewModel.onAction(MainUiAction.StartSelectionAt("missing"))

        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(ScreenState.GALLERY, viewModel.uiState.value.currentScreen)
    }

    private val release = CompletableDeferred<Unit>()

    private fun startExport(result: Result<ExportReport>) = viewModel.runExport { onProgress ->
        onProgress(ExportProgress(1, 3, "one.jpg"))
        release.await()
        result
    }

    @Test
    fun exportPublishesProgressThenASummary() = runTest {
        startExport(Result.success(ExportReport(exportedPhotos = 3, writtenFiles = 5, skippedFiles = 2)))
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isExporting)
        assertEquals(ExportProgress(1, 3, "one.jpg"), viewModel.uiState.value.exportProgress)

        release.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isExporting)
        assertNull(state.exportProgress)
        assertEquals("3 枚のキープ写真を書き出しました（2 件は同名ファイルがあるためスキップ）", state.exportSuccessMessage)
    }

    @Test
    fun aSecondExportWhileOneRunsIsIgnored() = runTest {
        startExport(Result.success(ExportReport(exportedPhotos = 1)))
        dispatcher.scheduler.runCurrent()
        var secondStarted = false
        viewModel.runExport {
            secondStarted = true
            Result.success(ExportReport(exportedPhotos = 9))
        }
        release.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(secondStarted)
        assertEquals("1 枚のキープ写真を書き出しました", viewModel.uiState.value.exportSuccessMessage)
    }

    @Test
    fun cancellingAnExportReportsHowFarItGot() = runTest {
        startExport(Result.success(ExportReport(exportedPhotos = 3)))
        dispatcher.scheduler.runCurrent()

        viewModel.onAction(MainUiAction.CancelExport)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isExporting)
        assertNull(state.exportProgress)
        assertEquals("書き出しを中止しました（1 枚は保存済み）", state.exportSuccessMessage)
    }

    @Test
    fun failedExportShowsAnError() = runTest {
        release.complete(Unit)
        startExport(Result.failure(IllegalStateException("容量不足")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("書き出しに失敗しました: 容量不足", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isExporting)
    }

    @Test
    fun exportMessageMentionsFailures() {
        assertEquals(
            "2 枚のキープ写真を書き出しました（1 件は同名ファイルがあるためスキップ、3 件は失敗）",
            MainViewModel.exportMessage(ExportReport(exportedPhotos = 2, skippedFiles = 1, failedFiles = 3)),
        )
        assertEquals("0 枚のキープ写真を書き出しました", MainViewModel.exportMessage(ExportReport(exportedPhotos = 0)))
    }

    @Test
    fun showErrorSurfacesTheMessage() = runTest {
        viewModel.onAction(MainUiAction.ShowError("共有できません"))
        assertEquals("共有できません", viewModel.uiState.value.errorMessage)
    }

    private class RecordingRepository : PhotoRepository {
        val photosState = MutableStateFlow<List<PhotoItem>>(emptyList())
        override val photos: StateFlow<List<PhotoItem>> = photosState
        override val lastStateChangeTime: StateFlow<Long> = MutableStateFlow(0L)

        var bulkCalls = 0
        override suspend fun importSession(folderUri: Uri): Result<Unit> = Result.success(Unit)

        override fun updateSelection(photoId: String, state: SelectionState) {
            photosState.value = photosState.value.map { if (it.id == photoId) it.copy(selectionState = state) else it }
        }

        override fun updateSelections(photoIds: Collection<String>, state: SelectionState): Int {
            bulkCalls++
            photosState.value = photosState.value.map { if (it.id in photoIds) it.copy(selectionState = state) else it }
            return photoIds.size
        }

        override fun updateRating(photoId: String, rating: Int) {
            photosState.value = photosState.value.map { if (it.id == photoId) it.copy(rating = rating) else it }
        }

        override fun undoLastSelection(): Boolean = false
        override suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int> = Result.success(0)

        override fun clearSession() {
            photosState.value = emptyList()
        }
    }
}
