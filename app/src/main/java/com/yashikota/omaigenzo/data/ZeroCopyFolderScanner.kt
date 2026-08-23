package com.yashikota.omaigenzo.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.PhotoType
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedFileEntry(
    val fullName: String,
    val uriString: String? = null,
    val localPath: String? = null,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
)

class ZeroCopyFolderScanner(private val context: Context? = null) {

    companion object {
        val RAW_EXTENSIONS = setOf("arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "pef", "raf")
        val JPG_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }

    suspend fun scanTreeUri(treeUri: Uri): List<PhotoItem> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        PerfLogger.event("scan_start", "\"uri\":\"${PerfLogger.escape(treeUri.toString())}\"")
        val ctx = context ?: return@withContext emptyList()
        val entries = mutableListOf<ScannedFileEntry>()
        val resolver = ctx.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameColumn) ?: continue
                entries += ScannedFileEntry(
                    fullName = name,
                    uriString = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn)).toString(),
                    size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                    modifiedAt = if (cursor.isNull(modifiedColumn)) 0L else cursor.getLong(modifiedColumn),
                )
            }
        }
        groupAndCreatePhotoItems(entries).also { photos ->
            PerfLogger.event(
                "scan_end",
                "\"uri\":\"${PerfLogger.escape(treeUri.toString())}\",\"files\":${entries.size}," +
                    "\"photos\":${photos.size},\"duration_ns\":${SystemClock.elapsedRealtimeNanos() - startedAt}",
            )
        }
    }

    fun groupAndCreatePhotoItems(entries: List<ScannedFileEntry>): List<PhotoItem> {
        val groupedMap = mutableMapOf<String, MutableList<ScannedFileEntry>>()

        entries.forEach { entry ->
            val ext = entry.fullName.substringAfterLast('.', "").lowercase()
            val baseName = entry.fullName.substringBeforeLast('.', "")

            if (RAW_EXTENSIONS.contains(ext) || JPG_EXTENSIONS.contains(ext)) {
                val key = baseName.lowercase()
                groupedMap.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }

        val photoItems = mutableListOf<PhotoItem>()

        groupedMap.toSortedMap().forEach { (_, entryList) ->
            var rawEntry: ScannedFileEntry? = null
            var jpgEntry: ScannedFileEntry? = null
            var rawExt = ""
            var jpgExt = ""

            entryList.forEach { entry ->
                val ext = entry.fullName.substringAfterLast('.', "").lowercase()
                if (RAW_EXTENSIONS.contains(ext)) {
                    rawEntry = entry
                    rawExt = ext
                } else if (JPG_EXTENSIONS.contains(ext)) {
                    jpgEntry = entry
                    jpgExt = ext
                }
            }

            val baseName = (rawEntry ?: jpgEntry)?.fullName?.substringBeforeLast('.', "") ?: ""
            val totalSize = (rawEntry?.size ?: 0L) + (jpgEntry?.size ?: 0L)

            val photoType = when {
                rawEntry != null && jpgEntry != null -> PhotoType.RAW_AND_JPEG
                rawEntry != null -> PhotoType.RAW
                else -> PhotoType.STANDARD
            }

            photoItems.add(
                PhotoItem(
                    id = rawEntry?.uriString ?: rawEntry?.localPath ?: jpgEntry?.uriString ?: jpgEntry?.localPath ?: baseName,
                    baseName = baseName,
                    rawPath = rawEntry?.localPath,
                    jpgPath = jpgEntry?.localPath,
                    rawUriString = rawEntry?.uriString,
                    jpgUriString = jpgEntry?.uriString,
                    rawExtension = rawExt,
                    jpgExtension = jpgExt,
                    fileSize = totalSize,
                    modifiedAt = maxOf(rawEntry?.modifiedAt ?: 0L, jpgEntry?.modifiedAt ?: 0L),
                    fileType = photoType,
                    selectionState = SelectionState.PENDING,
                ),
            )
        }

        return photoItems
    }
}
