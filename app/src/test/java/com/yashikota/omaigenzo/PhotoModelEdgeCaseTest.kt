package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.ui.MainUiState
import com.yashikota.omaigenzo.ui.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoModelEdgeCaseTest {

    // --- PhotoItem -------------------------------------------------------------------------------

    @Test
    fun aJpegOnlyPhotoIsNeverRenderedAsRaw() {
        val item = PhotoItem(id = "j", baseName = "IMG_1", jpgPath = "/d/IMG_1.JPG", jpgExtension = "JPG")

        assertEquals("IMG_1.jpg", item.displayFileName)
        assertEquals("JPG", item.displayBadge)
        assertFalse(item.isRawFile())
        assertTrue(item.hasJpegPreview())
        assertFalse(item.shouldUseRawRenderer())
        assertEquals("/d/IMG_1.JPG", item.primaryPath)
    }

    @Test
    fun extensionsAreNormalisedForDisplay() {
        val item = PhotoItem(id = "r", baseName = "DSC", rawExtension = "ArW", jpgExtension = "JpG")

        assertEquals("DSC.arw", item.displayFileName)
        assertEquals("ARW", item.displayBadge)
    }

    @Test
    fun primaryPathPrefersLocalFilesThenRawUris() {
        val all = PhotoItem("id", "b", rawPath = "/raw", jpgPath = "/jpg", rawUriString = "content://raw", jpgUriString = "content://jpg")

        assertEquals("/raw", all.primaryPath)
        assertEquals("/jpg", all.copy(rawPath = null).primaryPath)
        assertEquals("content://raw", all.copy(rawPath = null, jpgPath = null).primaryPath)
        assertEquals("content://jpg", all.copy(rawPath = null, jpgPath = null, rawUriString = null).primaryPath)
        assertEquals("", PhotoItem("id", "b").primaryPath)
    }

    @Test
    fun fastDisplayPathPrefersJpegsBecauseTheyAreCheapestToDecode() {
        val all = PhotoItem("id", "b", rawPath = "/raw", jpgPath = "/jpg", rawUriString = "content://raw", jpgUriString = "content://jpg")

        assertEquals("/jpg", all.fastDisplayPath)
        assertEquals("/raw", all.copy(jpgPath = null).fastDisplayPath)
        assertEquals("content://jpg", all.copy(jpgPath = null, rawPath = null).fastDisplayPath)
        assertEquals("content://raw", all.copy(jpgPath = null, rawPath = null, jpgUriString = null).fastDisplayPath)
        assertEquals("", PhotoItem("id", "b").fastDisplayPath)
    }

    @Test
    fun aPhotoWithNoSourcesIsNeitherRawNorJpeg() {
        val empty = PhotoItem("id", "ghost")

        assertFalse(empty.isRawFile())
        assertFalse(empty.hasJpegPreview())
        assertFalse(empty.shouldUseRawRenderer())
    }

    @Test
    fun copyingWithANewStateLeavesTheOriginalAlone() {
        val original = PhotoItem("id", "b")
        val accepted = original.copy(selectionState = SelectionState.ACCEPT)

        assertEquals(SelectionState.PENDING, original.selectionState)
        assertEquals(SelectionState.ACCEPT, accepted.selectionState)
        assertEquals(original.copy(selectionState = SelectionState.ACCEPT), accepted)
    }

    @Test
    fun aNewPhotoStartsPendingWithEmptyExif() {
        val item = PhotoItem("id", "b")

        assertEquals(SelectionState.PENDING, item.selectionState)
        assertEquals(PhotoType.STANDARD, item.fileType)
        assertEquals(ExifInfo(), item.exifInfo)
    }

    // --- SessionSummary --------------------------------------------------------------------------

    @Test
    fun anEmptySessionHasZeroPercentages() {
        val summary = SessionSummary(totalCount = 0, acceptCount = 0, rejectCount = 0, pendingCount = 0)
        assertEquals(0, summary.acceptPercentage)
        assertEquals(0, summary.rejectPercentage)
    }

    @Test
    fun percentagesRoundDown() {
        val summary = SessionSummary(totalCount = 3, acceptCount = 1, rejectCount = 2, pendingCount = 0)
        assertEquals(33, summary.acceptPercentage)
        assertEquals(66, summary.rejectPercentage)
    }

    @Test
    fun pendingPhotosCountTowardsTheTotal() {
        val summary = SessionSummary(totalCount = 4, acceptCount = 1, rejectCount = 1, pendingCount = 2)
        assertEquals(25, summary.acceptPercentage)
        assertEquals(25, summary.rejectPercentage)
    }

    // --- MainUiState -----------------------------------------------------------------------------

    @Test
    fun theInitialStateIsTheFolderPicker() {
        val state = MainUiState()

        assertEquals(ScreenState.FOLDER_SELECT, state.currentScreen)
        assertNull(state.currentPhoto)
        assertEquals(0, state.totalCount)
        assertFalse(state.isImporting)
        assertFalse(state.isExporting)
        assertNull(state.errorMessage)
        assertNull(state.exportSuccessMessage)
    }

    @Test
    fun countsFollowTheSelectionStates() {
        val state = MainUiState(
            photos = listOf(
                PhotoItem("1", "a", selectionState = SelectionState.ACCEPT),
                PhotoItem("2", "b", selectionState = SelectionState.ACCEPT),
                PhotoItem("3", "c", selectionState = SelectionState.REJECT),
                PhotoItem("4", "d"),
            ),
        )

        assertEquals(2, state.acceptCount)
        assertEquals(1, state.rejectCount)
        assertEquals(1, state.pendingCount)
        assertEquals(4, state.totalCount)
        assertEquals(state.totalCount, state.acceptCount + state.rejectCount + state.pendingCount)
    }

    @Test
    fun theCurrentPhotoFollowsTheIndexAndIsNullPastTheEnd() {
        val photos = listOf(PhotoItem("1", "a"), PhotoItem("2", "b"))

        assertEquals("1", MainUiState(photos = photos, currentIndex = 0).currentPhoto?.id)
        assertEquals("2", MainUiState(photos = photos, currentIndex = 1).currentPhoto?.id)
        assertNull("the completion screen sits one past the last photo", MainUiState(photos = photos, currentIndex = 2).currentPhoto)
        assertNull(MainUiState(photos = photos, currentIndex = -1).currentPhoto)
    }
}
