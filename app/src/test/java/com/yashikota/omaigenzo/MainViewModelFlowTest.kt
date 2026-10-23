package com.yashikota.omaigenzo

import android.net.Uri
import com.yashikota.omaigenzo.data.PhotoRepository
import com.yashikota.omaigenzo.ui.MainUiAction
import com.yashikota.omaigenzo.ui.MainViewModel
import com.yashikota.omaigenzo.ui.ScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Import, export and undo flows of [MainViewModel]. Robolectric provides a real [Uri], which the
 * plain JVM stubs cannot construct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainViewModelFlowTest {

    // Unconfined, like Dispatchers.Main.immediate in the app: the photo collector sees a repository
    // update before onAction returns.
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: ScriptedRepository
    private lateinit var viewModel: MainViewModel

    private val folder: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
    private val output: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APicked")

    private val state get() = viewModel.uiState.value

    private fun photos(vararg states: SelectionState) = states.mapIndexed { i, s -> PhotoItem(id = "p$i", baseName = "IMG_$i", selectionState = s) }

    private fun photos(count: Int) = photos(*Array(count) { SelectionState.PENDING })

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = ScriptedRepository()
        viewModel = MainViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun publish(list: List<PhotoItem>) {
        repository.photosState.value = list
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun act(action: MainUiAction) {
        viewModel.onAction(action)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // --- import ----------------------------------------------------------------------------------

    @Test
    fun aSuccessfulImportOpensTheFirstPhoto() = runTest(testDispatcher) {
        publish(photos(5))
        act(MainUiAction.SwipeSkip(state.photos[0]))
        act(MainUiAction.SwipeSkip(state.photos[1]))
        repository.nextImport = { photos(3) }

        act(MainUiAction.SelectFolder(folder))

        assertEquals(listOf(folder), repository.importedFolders)
        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
        assertEquals(0, state.currentIndex)
        assertEquals(3, state.totalCount)
        assertFalse(state.isImporting)
        assertNull(state.errorMessage)
    }

    @Test
    fun theFirstImportOfASessionOpensSwipingWithoutAnError() = runTest(testDispatcher) {
        repository.nextImport = { photos(2) }

        act(MainUiAction.SelectFolder(folder))

        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
        assertEquals(2, state.totalCount)
        assertNull(state.errorMessage)
    }

    @Test
    fun theImportSpinnerIsShownOnlyWhileTheScanRuns() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.nextImport = {
            gate.await()
            photos(1)
        }

        act(MainUiAction.SelectFolder(folder))
        assertTrue(state.isImporting)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(state.isImporting)
    }

    @Test
    fun startingAnImportClearsTheOldError() = runTest(testDispatcher) {
        repository.nextImport = { throw IllegalStateException("first") }
        act(MainUiAction.SelectFolder(folder))
        val gate = CompletableDeferred<Unit>()
        repository.nextImport = {
            gate.await()
            photos(1)
        }

        act(MainUiAction.SelectFolder(folder))

        assertNull(state.errorMessage)
        gate.complete(Unit)
    }

    @Test
    fun aFolderWithoutPhotosStaysOnThePickerWithAnExplanation() = runTest(testDispatcher) {
        repository.nextImport = { emptyList() }

        act(MainUiAction.SelectFolder(folder))

        assertEquals(ScreenState.FOLDER_SELECT, state.currentScreen)
        assertEquals("このフォルダに対応する写真（RAW・JPEG）が見つかりませんでした", state.errorMessage)
        assertFalse(state.isImporting)
    }

    @Test
    fun aFailedImportReportsTheCauseAndKeepsTheCurrentSession() = runTest(testDispatcher) {
        publish(photos(2))
        repository.nextImport = { throw SecurityException("permission revoked") }

        act(MainUiAction.SelectFolder(folder))

        assertEquals("フォルダを読み込めませんでした（permission revoked）", state.errorMessage)
        assertFalse(state.isImporting)
        assertEquals(2, state.totalCount)
        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
    }

    @Test
    fun anImportForgetsTheUndoHistoryOfTheOldSession() = runTest(testDispatcher) {
        publish(photos(3))
        act(MainUiAction.SwipeAccept(state.photos[0]))
        repository.nextImport = { photos(3) }
        act(MainUiAction.SelectFolder(folder))
        val updatesBeforeUndo = repository.updates.size

        act(MainUiAction.SwipeUndo)

        assertEquals(0, state.currentIndex)
        assertEquals(updatesBeforeUndo, repository.updates.size)
    }

    @Test
    fun clearErrorDismissesTheMessage() = runTest(testDispatcher) {
        repository.nextImport = { throw IllegalStateException("x") }
        act(MainUiAction.SelectFolder(folder))

        act(MainUiAction.ClearError)

        assertNull(state.errorMessage)
    }

    // --- export ----------------------------------------------------------------------------------

    @Test
    fun aSuccessfulExportReportsHowManyPhotosWereSaved() = runTest(testDispatcher) {
        repository.nextExport = { Result.success(3) }

        act(MainUiAction.ExportAcceptedPhotos(output))

        assertEquals(listOf(output), repository.exportTargets)
        assertEquals("キープした 3 枚を書き出しました", state.exportSuccessMessage)
        assertFalse(state.isExporting)
        assertNull(state.errorMessage)
    }

    @Test
    fun theExportSpinnerIsShownOnlyWhileCopying() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.nextExport = {
            gate.await()
            Result.success(1)
        }

        act(MainUiAction.ExportAcceptedPhotos(output))
        assertTrue(state.isExporting)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(state.isExporting)
    }

    @Test
    fun aFailedExportReportsTheCause() = runTest(testDispatcher) {
        repository.nextExport = { Result.failure(IllegalStateException("disk full")) }

        act(MainUiAction.ExportAcceptedPhotos(output))

        assertEquals("書き出せませんでした（disk full）", state.errorMessage)
        assertNull(state.exportSuccessMessage)
        assertFalse(state.isExporting)
    }

    @Test
    fun clearExportMessageDismissesTheConfirmation() = runTest(testDispatcher) {
        repository.nextExport = { Result.success(0) }
        act(MainUiAction.ExportAcceptedPhotos(output))

        act(MainUiAction.ClearExportMessage)

        assertNull(state.exportSuccessMessage)
    }

    // --- repository updates ----------------------------------------------------------------------

    @Test
    fun aRestoredSessionOpensTheSwipeScreen() = runTest(testDispatcher) {
        publish(photos(2))
        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
    }

    @Test
    fun repositoryUpdatesNeverPullTheUserOffAnotherScreen() = runTest(testDispatcher) {
        publish(photos(2))
        act(MainUiAction.NavigateTo(ScreenState.GALLERY))

        publish(photos(3))

        assertEquals(ScreenState.GALLERY, state.currentScreen)
    }

    @Test
    fun aShrinkingListClampsTheCurrentIndex() = runTest(testDispatcher) {
        publish(photos(5))
        repeat(4) { act(MainUiAction.SwipeSkip(state.photos[it])) }
        assertEquals(4, state.currentIndex)

        publish(photos(2))
        assertEquals(1, state.currentIndex)

        publish(emptyList())
        assertEquals(0, state.currentIndex)
    }

    // --- swiping and undo ------------------------------------------------------------------------

    @Test
    fun skippingLeavesTheDecisionUntouched() = runTest(testDispatcher) {
        publish(photos(2))

        act(MainUiAction.SwipeSkip(state.photos[0]))

        assertEquals(1, state.currentIndex)
        assertTrue(repository.updates.isEmpty())
        assertEquals(SelectionState.PENDING, state.photos[0].selectionState)
    }

    @Test
    fun undoWalksBackOneSwipeAtATime() = runTest(testDispatcher) {
        publish(photos(3))
        act(MainUiAction.SwipeAccept(state.photos[0]))
        act(MainUiAction.SwipeReject(state.photos[1]))

        act(MainUiAction.SwipeUndo)
        assertEquals(1, state.currentIndex)
        assertEquals(listOf(SelectionState.ACCEPT, SelectionState.PENDING, SelectionState.PENDING), state.photos.map { it.selectionState })

        act(MainUiAction.SwipeUndo)
        assertEquals(0, state.currentIndex)
        assertEquals(List(3) { SelectionState.PENDING }, state.photos.map { it.selectionState })
    }

    @Test
    fun undoRestoresAnEarlierDecisionRatherThanPending() = runTest(testDispatcher) {
        publish(photos(SelectionState.ACCEPT, SelectionState.PENDING))

        act(MainUiAction.SwipeReject(state.photos[0]))
        act(MainUiAction.SwipeUndo)

        assertEquals(SelectionState.ACCEPT, state.photos[0].selectionState)
    }

    @Test
    fun undoOnTheCompletionScreenReturnsToTheLastPhoto() = runTest(testDispatcher) {
        publish(photos(2))
        act(MainUiAction.SwipeAccept(state.photos[0]))
        act(MainUiAction.SwipeAccept(state.photos[1]))
        assertEquals(ScreenState.COMPLETION, state.currentScreen)
        assertEquals(2, state.currentIndex)

        act(MainUiAction.SwipeUndo)

        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
        assertEquals(1, state.currentIndex)
        assertEquals(SelectionState.PENDING, state.photos[1].selectionState)
    }

    @Test
    fun undoAfterVisitingTheGalleryReturnsToSwiping() = runTest(testDispatcher) {
        publish(photos(3))
        act(MainUiAction.SwipeAccept(state.photos[0]))
        act(MainUiAction.NavigateTo(ScreenState.GALLERY))

        act(MainUiAction.SwipeUndo)

        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun galleryChangesDoNotMoveTheSwipePosition() = runTest(testDispatcher) {
        publish(photos(3))
        act(MainUiAction.SwipeSkip(state.photos[0]))

        act(MainUiAction.ChangePhotoSelection("p2", SelectionState.REJECT))

        assertEquals(1, state.currentIndex)
        assertEquals(SelectionState.REJECT, state.photos[2].selectionState)
    }

    @Test
    fun galleryChangesAreNotPartOfTheSwipeUndoHistory() = runTest(testDispatcher) {
        publish(photos(3))
        act(MainUiAction.ChangePhotoSelection("p2", SelectionState.REJECT))

        act(MainUiAction.SwipeUndo)

        assertEquals(SelectionState.REJECT, state.photos[2].selectionState)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun resumeWithNothingPendingStartsFromTheBeginning() = runTest(testDispatcher) {
        publish(photos(SelectionState.ACCEPT, SelectionState.REJECT))
        act(MainUiAction.NavigateTo(ScreenState.COMPLETION))

        act(MainUiAction.ResumePending)

        assertEquals(0, state.currentIndex)
        assertEquals(ScreenState.SWIPE_SELECTION, state.currentScreen)
    }

    @Test
    fun resetForgetsTheUndoHistory() = runTest(testDispatcher) {
        publish(photos(2))
        act(MainUiAction.SwipeAccept(state.photos[0]))

        act(MainUiAction.ResetSession)
        assertEquals(ScreenState.FOLDER_SELECT, state.currentScreen)
        publish(photos(2))
        val updatesBeforeUndo = repository.updates.size
        act(MainUiAction.SwipeUndo)

        assertEquals(1, repository.clears)
        assertEquals(updatesBeforeUndo, repository.updates.size)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun resetAlsoDropsMessages() = runTest(testDispatcher) {
        repository.nextImport = { throw IllegalStateException("x") }
        act(MainUiAction.SelectFolder(folder))

        act(MainUiAction.ResetSession)

        assertNull(state.errorMessage)
        assertNull(state.exportSuccessMessage)
        assertFalse(state.isImporting)
    }

    /** A repository whose import and export outcomes each test scripts. */
    private class ScriptedRepository : PhotoRepository {
        val photosState = MutableStateFlow<List<PhotoItem>>(emptyList())
        override val photos: StateFlow<List<PhotoItem>> = photosState
        override val lastStateChangeTime: StateFlow<Long> = MutableStateFlow(0L)

        var nextImport: suspend () -> List<PhotoItem> = { emptyList() }
        var nextExport: suspend () -> Result<Int> = { Result.success(0) }
        val importedFolders = mutableListOf<Uri>()
        val exportTargets = mutableListOf<Uri>()
        val updates = mutableListOf<Pair<String, SelectionState>>()
        var clears = 0

        override suspend fun importSession(folderUri: Uri): Result<Unit> {
            importedFolders += folderUri
            return try {
                photosState.value = nextImport()
                // In the app the collector on Dispatchers.Main.immediate sees the new list before the
                // import returns; let the test dispatcher deliver it first as well.
                yield()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override fun updateSelection(photoId: String, state: SelectionState) {
            updates += photoId to state
            photosState.value = photosState.value.map { if (it.id == photoId) it.copy(selectionState = state) else it }
        }

        override fun undoLastSelection(): Boolean = false

        override suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int> {
            exportTargets += outputUri
            return nextExport()
        }

        override fun clearSession() {
            clears++
            photosState.value = emptyList()
        }
    }
}
