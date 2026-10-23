package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.data.ExportContent
import com.yashikota.omaigenzo.data.ExportOptions
import com.yashikota.omaigenzo.data.ExportPlanner
import com.yashikota.omaigenzo.data.ExportProgress
import com.yashikota.omaigenzo.data.ExportSource
import com.yashikota.omaigenzo.data.SelectionShareText
import com.yashikota.omaigenzo.data.XmpSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {

    private val pair = PhotoItem(
        id = "pair",
        baseName = "DSC0001",
        rawUriString = "content://raw/1",
        jpgUriString = "content://jpg/1",
        rawExtension = "ARW",
        jpgExtension = "JPG",
        fileSize = 30L,
        fileType = PhotoType.RAW_AND_JPEG,
        selectionState = SelectionState.ACCEPT,
        rating = 4,
    )
    private val rawOnly = PhotoItem(
        id = "raw",
        baseName = "DSC0002",
        rawPath = "/photos/DSC0002.dng",
        rawExtension = "dng",
        fileSize = 20L,
        fileType = PhotoType.RAW,
        selectionState = SelectionState.ACCEPT,
        rating = 1,
    )
    private val jpegOnly = PhotoItem(
        id = "jpeg",
        baseName = "IMG_0003",
        jpgUriString = "content://jpg/3",
        jpgExtension = "jpg",
        fileSize = 5L,
        selectionState = SelectionState.ACCEPT,
    )
    private val rejected = PhotoItem(
        id = "rejected",
        baseName = "DSC0004",
        rawUriString = "content://raw/4",
        rawExtension = "arw",
        selectionState = SelectionState.REJECT,
        rating = 5,
    )
    private val all = listOf(pair, rawOnly, jpegOnly, rejected)

    private fun names(content: ExportContent, xmp: Boolean = false) = ExportPlanner.plan(all, ExportOptions(content = content, writeXmpSidecar = xmp))
        .associate { entry -> entry.photoId to entry.files.map { it.fileName } }

    @Test
    fun rawAndJpegWritesEveryFileOfKeptPhotosOnly() {
        assertEquals(
            mapOf(
                "pair" to listOf("DSC0001.ARW", "DSC0001.JPG"),
                "raw" to listOf("DSC0002.dng"),
                "jpeg" to listOf("IMG_0003.jpg"),
            ),
            names(ExportContent.RAW_AND_JPEG),
        )
    }

    @Test
    fun rawOnlyFallsBackToTheJpegWhenAPhotoHasNoRaw() {
        assertEquals(
            mapOf(
                "pair" to listOf("DSC0001.ARW"),
                "raw" to listOf("DSC0002.dng"),
                "jpeg" to listOf("IMG_0003.jpg"),
            ),
            names(ExportContent.RAW_ONLY),
        )
    }

    @Test
    fun jpegOnlyFallsBackToTheRawWhenAPhotoHasNoJpeg() {
        assertEquals(
            mapOf(
                "pair" to listOf("DSC0001.JPG"),
                "raw" to listOf("DSC0002.dng"),
                "jpeg" to listOf("IMG_0003.jpg"),
            ),
            names(ExportContent.JPEG_ONLY),
        )
    }

    @Test
    fun xmpSidecarsAccompanyExportedRawFilesOnly() {
        assertEquals(
            mapOf(
                "pair" to listOf("DSC0001.ARW", "DSC0001.JPG", "DSC0001.xmp"),
                "raw" to listOf("DSC0002.dng", "DSC0002.xmp"),
                "jpeg" to listOf("IMG_0003.jpg"),
            ),
            names(ExportContent.RAW_AND_JPEG, xmp = true),
        )
        assertEquals(listOf("DSC0001.JPG"), names(ExportContent.JPEG_ONLY, xmp = true)["pair"])
    }

    @Test
    fun sidecarCarriesThePhotosRating() {
        val sidecar = ExportPlanner.plan(listOf(pair), ExportOptions(writeXmpSidecar = true))
            .single().files.single { it.fileName.endsWith(".xmp") }
        val text = String((sidecar.source as ExportSource.Inline).bytes)

        assertTrue(text, text.contains("xmp:Rating=\"4\""))
    }

    @Test
    fun sourcesPreferLocalPathsAndKeepContentUris() {
        val plan = ExportPlanner.plan(listOf(pair, rawOnly), ExportOptions(writeXmpSidecar = false))

        assertEquals(ExportSource.ContentUri("content://raw/1"), plan[0].files[0].source)
        assertEquals("image/x-sony-arw", plan[0].files[0].mimeType)
        assertEquals("image/jpeg", plan[0].files[1].mimeType)
        assertEquals(ExportSource.LocalFile("/photos/DSC0002.dng"), plan[1].files[0].source)
        assertEquals("image/x-adobe-dng", plan[1].files[0].mimeType)
    }

    @Test
    fun minimumRatingFiltersKeptPhotos() {
        val plan = ExportPlanner.plan(all, ExportOptions(minRating = 2))

        assertEquals(listOf("pair"), plan.map { it.photoId })
        assertEquals(30L, ExportPlanner.estimatedBytes(all, ExportOptions(minRating = 2)))
        assertEquals(55L, ExportPlanner.estimatedBytes(all, ExportOptions()))
    }

    @Test
    fun xmpPacketIsWellFormedAndClampsTheRating() {
        val xmp = XmpSidecar.build(7)

        assertTrue(xmp.contains("xmp:Rating=\"5\""))
        assertTrue(xmp.contains("xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\""))
        assertTrue(xmp.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
        assertTrue(XmpSidecar.build(-2).contains("xmp:Rating=\"0\""))
    }

    @Test
    fun shareTextListsKeptFileNamesInOrder() {
        assertEquals("DSC0001.arw\nDSC0002.dng\nIMG_0003.jpg", SelectionShareText.build(all))
        assertEquals("DSC0004.arw", SelectionShareText.build(all, SelectionState.REJECT))
        assertEquals("", SelectionShareText.build(emptyList()))
    }

    @Test
    fun progressFractionIsSafeForAnEmptyExport() {
        assertEquals(0f, ExportProgress(0, 0).fraction)
        assertEquals(0.5f, ExportProgress(2, 4).fraction)
    }
}
