package com.yashikota.omaigenzo.data

import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState

/** Which files of a kept photo are written. A photo that only has the other kind still exports it. */
enum class ExportContent {
    RAW_AND_JPEG,
    RAW_ONLY,
    JPEG_ONLY,
}

data class ExportOptions(
    val content: ExportContent = ExportContent.RAW_AND_JPEG,
    /** Lightroom / Capture One style `<name>.xmp` next to each exported RAW, carrying the rating. */
    val writeXmpSidecar: Boolean = true,
    /** Only kept photos with at least this many stars. 0 exports every kept photo. */
    val minRating: Int = 0,
    /** Leave files that already exist in the destination alone, so an export can be re-run safely. */
    val skipExisting: Boolean = true,
)

data class ExportProgress(
    val completedPhotos: Int,
    val totalPhotos: Int,
    val currentFileName: String = "",
) {
    val fraction: Float
        get() = if (totalPhotos > 0) completedPhotos.toFloat() / totalPhotos else 0f
}

data class ExportReport(
    val exportedPhotos: Int,
    val writtenFiles: Int = 0,
    val skippedFiles: Int = 0,
    val failedFiles: Int = 0,
    val cancelled: Boolean = false,
)

sealed interface ExportSource {
    data class LocalFile(val path: String) : ExportSource
    data class ContentUri(val uriString: String) : ExportSource
    class Inline(val bytes: ByteArray) : ExportSource
}

data class ExportFile(val fileName: String, val mimeType: String, val source: ExportSource)

data class ExportEntry(val photoId: String, val displayName: String, val files: List<ExportFile>)

object ExportPlanner {

    fun eligible(photos: List<PhotoItem>, minRating: Int): List<PhotoItem> = photos.filter {
        it.selectionState == SelectionState.ACCEPT && it.rating >= minRating
    }

    fun plan(photos: List<PhotoItem>, options: ExportOptions): List<ExportEntry> = eligible(photos, options.minRating).mapNotNull { photo ->
        val raw = source(photo.rawPath, photo.rawUriString)
        val jpeg = source(photo.jpgPath, photo.jpgUriString)
        val wantRaw = raw != null && (options.content != ExportContent.JPEG_ONLY || jpeg == null)
        val wantJpeg = jpeg != null && (options.content != ExportContent.RAW_ONLY || raw == null)

        val files = ArrayList<ExportFile>(3)
        if (wantRaw) files += ExportFile("${photo.baseName}.${photo.rawExtension}", rawMimeType(photo.rawExtension), raw!!)
        if (wantJpeg) files += ExportFile("${photo.baseName}.${photo.jpgExtension}", standardMimeType(photo.jpgExtension), jpeg!!)
        // Lightroom reads sidecars for RAW files only; a JPEG carries its XMP inside the file.
        if (wantRaw && options.writeXmpSidecar) {
            files += ExportFile("${photo.baseName}.xmp", XMP_MIME_TYPE, ExportSource.Inline(XmpSidecar.build(photo.rating).toByteArray()))
        }
        if (files.isEmpty()) null else ExportEntry(photo.id, photo.displayFileName, files)
    }

    /** Approximate bytes an export will copy, for the size shown before it starts. */
    fun estimatedBytes(photos: List<PhotoItem>, options: ExportOptions): Long = eligible(photos, options.minRating).sumOf { it.fileSize }

    private fun source(path: String?, uriString: String?): ExportSource? = when {
        path != null -> ExportSource.LocalFile(path)
        uriString != null -> ExportSource.ContentUri(uriString)
        else -> null
    }

    // Octet-stream keeps providers from appending an extension of their own to "name.xmp".
    const val XMP_MIME_TYPE = "application/octet-stream"

    fun rawMimeType(extension: String): String = when (extension.lowercase()) {
        "dng" -> "image/x-adobe-dng"
        "cr2", "cr3" -> "image/x-canon-cr2"
        "nef" -> "image/x-nikon-nef"
        "arw" -> "image/x-sony-arw"
        else -> "application/octet-stream"
    }

    fun standardMimeType(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}

/** The minimal XMP packet Lightroom, Capture One, darktable and Bridge all read a rating from. */
object XmpSidecar {
    private const val BOM = "\uFEFF"

    fun build(rating: Int): String {
        val clamped = rating.coerceIn(0, PhotoItem.MAX_RATING)
        return """
            |<?xpacket begin="$BOM" id="W5M0MpCehiHzreSzNTczkc9d"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Omai Genzo">
            | <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |  <rdf:Description rdf:about=""
            |    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
            |    xmp:CreatorTool="Omai Genzo"
            |    xmp:Rating="$clamped"/>
            | </rdf:RDF>
            |</x:xmpmeta>
            |<?xpacket end="w"?>
            |
        """.trimMargin()
    }
}

/** Plain text a user can paste into a filter, a chat, or a note: one file name per line. */
object SelectionShareText {
    fun build(photos: List<PhotoItem>, state: SelectionState = SelectionState.ACCEPT): String = buildString {
        for (photo in photos) {
            if (photo.selectionState != state) continue
            if (isNotEmpty()) append('\n')
            append(photo.displayFileName)
        }
    }
}
