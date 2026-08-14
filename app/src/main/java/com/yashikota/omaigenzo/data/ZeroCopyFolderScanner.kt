package com.yashikota.omaigenzo.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.PhotoType
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ScannedFileEntry(
    val fullName: String,
    val uriString: String? = null,
    val localPath: String? = null,
    val size: Long = 0L,
)

class ZeroCopyFolderScanner(private val context: Context? = null) {

    companion object {
        val RAW_EXTENSIONS = setOf("arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "pef", "raf")
        val JPG_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }

    suspend fun scanTreeUri(treeUri: Uri): List<PhotoItem> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext emptyList()
        val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri) ?: return@withContext emptyList()

        val entries = mutableListOf<ScannedFileEntry>()
        rootDoc.listFiles().forEach { doc ->
            if (!doc.isDirectory && doc.name != null) {
                entries.add(
                    ScannedFileEntry(
                        fullName = doc.name!!,
                        uriString = doc.uri.toString(),
                        size = doc.length(),
                    ),
                )
            }
        }
        groupAndCreatePhotoItems(entries)
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
                    id = UUID.randomUUID().toString(),
                    baseName = baseName,
                    rawPath = rawEntry?.localPath,
                    jpgPath = jpgEntry?.localPath,
                    rawUriString = rawEntry?.uriString,
                    jpgUriString = jpgEntry?.uriString,
                    rawExtension = rawExt,
                    jpgExtension = jpgExt,
                    fileSize = totalSize,
                    fileType = photoType,
                    selectionState = SelectionState.PENDING,
                ),
            )
        }

        return photoItems
    }
}
