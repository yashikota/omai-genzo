package com.yashikota.omaigenzo.ui

import com.yashikota.omaigenzo.ExifInfo
import com.yashikota.omaigenzo.PhotoItem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display formatting for photo metadata. Pure functions so they are unit-tested on the JVM. */
object PhotoFormatting {

    /** "ARW+JPG" for a pair, otherwise the single extension. */
    fun formatBadge(photo: PhotoItem): String {
        val raw = photo.rawExtension.uppercase(Locale.ROOT)
        val jpg = photo.jpgExtension.uppercase(Locale.ROOT)
        return when {
            raw.isNotEmpty() && jpg.isNotEmpty() -> "$raw+$jpg"
            raw.isNotEmpty() -> raw
            else -> jpg
        }
    }

    fun camera(exif: ExifInfo): String {
        val make = exif.make.trim()
        val model = exif.model.trim()
        // Many bodies repeat the maker in the model ("Canon" / "Canon EOS R5").
        return if (make.isNotEmpty() && model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
    }

    fun aperture(value: Float): String? {
        if (value <= 0f) return null
        val rounded = (value * 10f).roundToInt() / 10f
        return if (rounded == rounded.toInt().toFloat()) "f/${rounded.toInt()}" else "f/$rounded"
    }

    fun shutter(seconds: Float): String? {
        if (seconds <= 0f) return null
        if (seconds < 0.3f) return "1/${(1f / seconds).roundToInt()}s"
        val rounded = (seconds * 10f).roundToInt() / 10f
        if (abs(rounded - rounded.toInt()) < 0.05f) return "${rounded.toInt()}s"
        return "${rounded}s"
    }

    fun iso(value: Float): String? = if (value > 0f) "ISO ${value.roundToInt()}" else null

    fun focalLength(mm: Float): String? = if (mm > 0f) "${mm.roundToInt()}mm" else null

    /** Exposure triangle plus focal length, in the order photographers read them. */
    fun exposureParts(exif: ExifInfo): List<String> = listOfNotNull(
        focalLength(exif.focal),
        aperture(exif.aperture),
        shutter(exif.shutter),
        iso(exif.iso),
    )

    fun percent(part: Int, total: Int): Int = if (total > 0) ((part * 100f) / total).roundToInt() else 0
}
