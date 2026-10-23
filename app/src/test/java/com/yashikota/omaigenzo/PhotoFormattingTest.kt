package com.yashikota.omaigenzo

import com.yashikota.omaigenzo.ui.PhotoFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoFormattingTest {

    @Test
    fun badgeNamesBothFilesOfAPair() {
        val pair = PhotoItem(id = "a", baseName = "DSC0001", rawExtension = "arw", jpgExtension = "jpg")
        assertEquals("ARW+JPG", PhotoFormatting.formatBadge(pair))
        assertEquals("CR3", PhotoFormatting.formatBadge(pair.copy(rawExtension = "cr3", jpgExtension = "")))
        assertEquals("HEIC", PhotoFormatting.formatBadge(pair.copy(rawExtension = "", jpgExtension = "heic")))
    }

    @Test
    fun cameraDropsARepeatedMaker() {
        assertEquals("Canon EOS R5", PhotoFormatting.camera(ExifInfo(make = "Canon", model = "Canon EOS R5")))
        assertEquals("SONY ILCE-7M4", PhotoFormatting.camera(ExifInfo(make = "SONY", model = "ILCE-7M4")))
        assertEquals("", PhotoFormatting.camera(ExifInfo()))
    }

    @Test
    fun apertureUsesOneDecimalOnlyWhenNeeded() {
        assertEquals("f/2.8", PhotoFormatting.aperture(2.8000002f))
        assertEquals("f/8", PhotoFormatting.aperture(8f))
        assertEquals("f/1.4", PhotoFormatting.aperture(1.4142135f))
        assertNull(PhotoFormatting.aperture(0f))
    }

    @Test
    fun shutterReadsAsAFractionBelowAThirdOfASecond() {
        assertEquals("1/250s", PhotoFormatting.shutter(0.004f))
        assertEquals("1/80s", PhotoFormatting.shutter(0.0125f))
        assertEquals("1/4s", PhotoFormatting.shutter(0.25f))
        assertEquals("0.5s", PhotoFormatting.shutter(0.5f))
        assertEquals("1s", PhotoFormatting.shutter(1f))
        assertEquals("30s", PhotoFormatting.shutter(30f))
        assertNull(PhotoFormatting.shutter(0f))
    }

    @Test
    fun exposurePartsSkipMissingValues() {
        val exif = ExifInfo(iso = 400f, shutter = 1 / 60f, aperture = 1.8f, focal = 85f)
        assertEquals(listOf("85mm", "f/1.8", "1/60s", "ISO 400"), PhotoFormatting.exposureParts(exif))
        assertEquals(listOf("ISO 100"), PhotoFormatting.exposureParts(ExifInfo(iso = 100f)))
    }

    @Test
    fun percentRoundsAndSurvivesAnEmptySession() {
        assertEquals(54, PhotoFormatting.percent(13, 24))
        assertEquals(0, PhotoFormatting.percent(0, 0))
    }
}
