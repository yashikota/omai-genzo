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
        assertTrue(item.hasJpegPreview())
        assertEquals(false, item.shouldUseRawRenderer())
    }

    @Test
    fun testRawUriWithoutJpegUsesRawRenderer() {
        val item = PhotoItem(
            id = "raw-uri",
            baseName = "DSC0001",
            rawUriString = "content://photos/1",
            rawExtension = "arw",
            fileType = PhotoType.RAW,
        )

        assertTrue(item.isRawFile())
        assertTrue(item.shouldUseRawRenderer())
        assertEquals("content://photos/1", item.fastDisplayPath)
    }

    @Test
    fun testJpegUriCompanionWinsForFastDisplay() {
        val item = PhotoItem(
            id = "pair-uri",
            baseName = "DSC0002",
            rawUriString = "content://photos/raw",
            jpgUriString = "content://photos/jpeg",
            rawExtension = "nef",
            jpgExtension = "jpg",
            fileType = PhotoType.RAW_AND_JPEG,
        )

        assertEquals("content://photos/jpeg", item.fastDisplayPath)
        assertEquals(false, item.shouldUseRawRenderer())
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
