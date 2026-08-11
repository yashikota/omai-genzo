package com.yashikota.omaigenzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoModelTest {

    @Test
    fun testPhotoItemPropertiesForRawAndJpeg() {
        val item = PhotoItem(
            id = "test_1",
            baseName = "DSC06800",
            rawPath = "/path/to/DSC06800.ARW",
            jpgPath = "/path/to/DSC06800.JPG",
            rawExtension = "arw",
            jpgExtension = "jpg",
            fileType = PhotoType.RAW_AND_JPEG,
            selectionState = SelectionState.PENDING,
        )

        assertEquals("DSC06800.arw", item.displayFileName)
        assertEquals("ARW", item.displayBadge)
        assertTrue(item.isRawFile())
        assertEquals("/path/to/DSC06800.JPG", item.fastDisplayPath)
    }

    @Test
    fun testSelectionStateTransitions() {
        val item = PhotoItem(
            id = "test_2",
            baseName = "DSC06802",
            rawPath = "/path/to/DSC06802.ARW",
            rawExtension = "arw",
            fileType = PhotoType.RAW,
            selectionState = SelectionState.PENDING,
        )

        assertEquals(SelectionState.PENDING, item.selectionState)
        item.selectionState = SelectionState.ACCEPT
        assertEquals(SelectionState.ACCEPT, item.selectionState)
        item.selectionState = SelectionState.REJECT
        assertEquals(SelectionState.REJECT, item.selectionState)
    }

    @Test
    fun testSessionSummaryCalculations() {
        val summary = SessionSummary(
            totalCount = 10,
            acceptCount = 7,
            rejectCount = 3,
            pendingCount = 0,
        )

        assertEquals(70, summary.acceptPercentage)
        assertEquals(30, summary.rejectPercentage)
    }
}
