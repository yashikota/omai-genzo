package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.ScannedFileEntry
import com.yashikota.omaigenzo.data.ZeroCopyFolderScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ZeroCopyScannerTest {

    private val scanner = ZeroCopyFolderScanner(context = null)

    @Test
    fun testPairingRawAndJpgCompanion() {
        val entries = listOf(
            ScannedFileEntry(fullName = "DSC0001.ARW", localPath = "/photos/DSC0001.ARW", size = 25000000L),
            ScannedFileEntry(fullName = "DSC0001.JPG", localPath = "/photos/DSC0001.JPG", size = 5000000L),
            ScannedFileEntry(fullName = "DSC0002.CR3", localPath = "/photos/DSC0002.CR3", size = 30000000L),
            ScannedFileEntry(fullName = "DSC0003.JPEG", localPath = "/photos/DSC0003.JPEG", size = 4000000L),
            ScannedFileEntry(fullName = "notes.txt", localPath = "/photos/notes.txt", size = 100L),
            ScannedFileEntry(fullName = "video.mp4", localPath = "/photos/video.mp4", size = 100000000L),
        )

        val photoItems = scanner.groupAndCreatePhotoItems(entries)

        // Only 3 valid photo items (DSC0001, DSC0002, DSC0003)
        assertEquals(3, photoItems.size)

        // DSC0001: RAW + JPEG
        val item1 = photoItems[0]
        assertEquals("DSC0001", item1.baseName)
        assertEquals(PhotoType.RAW_AND_JPEG, item1.fileType)
        assertEquals("arw", item1.rawExtension)
        assertEquals("jpg", item1.jpgExtension)
        assertEquals(30000000L, item1.fileSize)
        assertNotNull(item1.rawPath)
        assertNotNull(item1.jpgPath)

        // DSC0002: RAW only
        val item2 = photoItems[1]
        assertEquals("DSC0002", item2.baseName)
        assertEquals(PhotoType.RAW, item2.fileType)
        assertEquals("cr3", item2.rawExtension)
        assertEquals(30000000L, item2.fileSize)
        assertNotNull(item2.rawPath)
        assertNull(item2.jpgPath)

        // DSC0003: Standard JPEG only
        val item3 = photoItems[2]
        assertEquals("DSC0003", item3.baseName)
        assertEquals(PhotoType.STANDARD, item3.fileType)
        assertEquals("jpeg", item3.jpgExtension)
        assertEquals(4000000L, item3.fileSize)
        assertNull(item3.rawPath)
        assertNotNull(item3.jpgPath)
    }

    @Test
    fun testCaseInsensitiveExtensionHandling() {
        val entries = listOf(
            ScannedFileEntry(fullName = "IMG_9999.nef", localPath = "/tmp/IMG_9999.nef", size = 20000000L),
            ScannedFileEntry(fullName = "IMG_9999.JPG", localPath = "/tmp/IMG_9999.JPG", size = 3000000L),
        )

        val result = scanner.groupAndCreatePhotoItems(entries)
        assertEquals(1, result.size)
        assertEquals(PhotoType.RAW_AND_JPEG, result[0].fileType)
        assertEquals("nef", result[0].rawExtension)
        assertEquals("jpg", result[0].jpgExtension)
    }
}
