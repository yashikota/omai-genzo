package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.ScannedFileEntry
import com.yashikota.omaigenzo.data.ZeroCopyFolderScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of turning one folder listing into photos, beyond [ZeroCopyScannerTest]. */
class FolderGroupingTest {

    private val scanner = ZeroCopyFolderScanner(context = null)

    private fun group(vararg entries: ScannedFileEntry) = scanner.groupAndCreatePhotoItems(entries.toList())

    private fun file(name: String, size: Long = 0L, modifiedAt: Long = 0L) = ScannedFileEntry(fullName = name, uriString = "content://p/$name", size = size, modifiedAt = modifiedAt)

    @Test
    fun anEmptyFolderHasNoPhotos() {
        assertTrue(scanner.groupAndCreatePhotoItems(emptyList()).isEmpty())
    }

    @Test
    fun filesWithoutAnExtensionOrWithAnUnsupportedOneAreIgnored() {
        val photos = group(file("README"), file("DSC0001.xmp"), file("DSC0001.mov"), file("archive.tar.gz"), file("DSC0001.ARW"))

        assertEquals(1, photos.size)
        assertEquals("DSC0001", photos[0].baseName)
    }

    @Test
    fun everySupportedRawExtensionIsRecognisedInAnyCase() {
        for (extension in ZeroCopyFolderScanner.RAW_EXTENSIONS) {
            for (name in listOf("A.$extension", "A.${extension.uppercase()}")) {
                val photo = group(file(name)).single()
                assertEquals(name, PhotoType.RAW, photo.fileType)
                assertEquals(extension, photo.rawExtension)
                assertEquals("content://p/$name", photo.rawUriString)
                assertNull(photo.jpgUriString)
            }
        }
    }

    @Test
    fun everySupportedStandardExtensionIsAStandardPhoto() {
        for (extension in ZeroCopyFolderScanner.JPG_EXTENSIONS) {
            val photo = group(file("B.${extension.uppercase()}")).single()
            assertEquals(PhotoType.STANDARD, photo.fileType)
            assertEquals(extension, photo.jpgExtension)
            assertEquals("", photo.rawExtension)
        }
    }

    @Test
    fun rawAndStandardExtensionSetsDoNotOverlap() {
        assertTrue(ZeroCopyFolderScanner.RAW_EXTENSIONS.intersect(ZeroCopyFolderScanner.JPG_EXTENSIONS).isEmpty())
    }

    @Test
    fun pairingIgnoresTheCaseOfTheBaseName() {
        val photo = group(file("dsc0001.ARW"), file("DSC0001.jpg")).single()

        assertEquals(PhotoType.RAW_AND_JPEG, photo.fileType)
        assertEquals("the RAW's own spelling is shown", "dsc0001", photo.baseName)
    }

    @Test
    fun theListingOrderDoesNotChangeTheResult() {
        val forward = group(file("A.ARW", 10, 1), file("A.JPG", 5, 2), file("B.NEF", 7, 3))
        val reversed = group(file("B.NEF", 7, 3), file("A.JPG", 5, 2), file("A.ARW", 10, 1))

        assertEquals(forward, reversed)
    }

    @Test
    fun photosAreSortedByNameIgnoringCase() {
        val photos = group(file("c.jpg"), file("B.jpg"), file("a.jpg"), file("D.ARW"))
        assertEquals(listOf("a", "B", "c", "D"), photos.map { it.baseName })
    }

    @Test
    fun onlyTheLastDotSeparatesTheExtension() {
        val photo = group(file("trip.day1.0001.NEF"), file("trip.day1.0001.jpg")).single()

        assertEquals("trip.day1.0001", photo.baseName)
        assertEquals(PhotoType.RAW_AND_JPEG, photo.fileType)
        assertEquals("trip.day1.0001.nef", photo.displayFileName)
    }

    @Test
    fun theIdIsTheRawUriWhenThereIsOne() {
        val photo = group(file("A.JPG"), file("A.ARW")).single()
        assertEquals("content://p/A.ARW", photo.id)
    }

    @Test
    fun theIdFallsBackToTheJpegUriThenToLocalPathsThenToTheName() {
        assertEquals("content://p/A.JPG", group(file("A.JPG")).single().id)
        assertEquals("/x/A.ARW", group(ScannedFileEntry("A.ARW", localPath = "/x/A.ARW")).single().id)
        assertEquals("/x/A.JPG", group(ScannedFileEntry("A.JPG", localPath = "/x/A.JPG")).single().id)
        assertEquals("A", group(ScannedFileEntry("A.JPG")).single().id)
    }

    @Test
    fun aRawUriWinsOverALocalRawPathForTheId() {
        val entry = ScannedFileEntry("A.ARW", uriString = "content://p/raw", localPath = "/x/A.ARW")
        assertEquals("content://p/raw", group(entry).single().id)
    }

    @Test
    fun idsAreUniqueAcrossALargeFolder() {
        val entries = List(2_000) { i -> file("DSC_${i / 2}.${if (i % 2 == 0) "ARW" else "JPG"}") }
        val photos = scanner.groupAndCreatePhotoItems(entries)

        assertEquals(1_000, photos.size)
        assertEquals(photos.size, photos.map { it.id }.toSet().size)
    }

    @Test
    fun sizesAddUpAndTheNewestTimestampWins() {
        val photo = group(file("A.ARW", size = 25_000_000, modifiedAt = 100), file("A.JPG", size = 5_000_000, modifiedAt = 200)).single()

        assertEquals(30_000_000L, photo.fileSize)
        assertEquals(200L, photo.modifiedAt)
    }

    @Test
    fun aFreshScanIsAlwaysPending() {
        assertTrue(group(file("A.ARW"), file("B.JPG")).all { it.selectionState == SelectionState.PENDING })
    }

    @Test
    fun aJpegSidecarMeansTheRawRendererIsNeverUsed() {
        val pair = group(file("A.ARW"), file("A.JPG")).single()
        val rawOnly = group(file("B.ARW")).single()

        assertEquals(false, pair.shouldUseRawRenderer())
        assertEquals("content://p/A.JPG", pair.fastDisplayPath)
        assertEquals(true, rawOnly.shouldUseRawRenderer())
    }

    @Test
    fun twoStandardFilesWithTheSameNameCollapseIntoOnePhoto() {
        // A.JPG and A.jpeg share a base name: one photo, so a decision is never applied twice.
        val photos = group(file("A.JPG"), file("A.jpeg"))
        assertEquals(1, photos.size)
        assertEquals(PhotoType.STANDARD, photos[0].fileType)
    }
}
