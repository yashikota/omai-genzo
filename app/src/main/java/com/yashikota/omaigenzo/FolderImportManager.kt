package com.yashikota.omaigenzo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ImportProgress(
    val isImporting: Boolean = false,
    val currentCount: Int = 0,
    val totalCount: Int = 0,
    val currentFileName: String = "",
    val message: String = "",
)

private data class FilePairEntry(
    val baseName: String,
    var rawDoc: DocumentFile? = null,
    var jpgDoc: DocumentFile? = null,
    var rawExt: String = "",
    var jpgExt: String = "",
)

class FolderImportManager(val context: Context) {

    companion object {
        private const val TAG = "FolderImportManager"
        private val RAW_EXTENSIONS = setOf("arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "pef", "raf")
        private val JPG_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val libRawBridge = LibRawBridge()

    val sessionsDir: File
        get() = File(context.filesDir, "imported_sessions").apply { if (!exists()) mkdirs() }

    suspend fun importFolderFromUri(treeUri: Uri): Pair<String, List<PhotoItem>> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext "" to emptyList()

        val sessionId = "session_${System.currentTimeMillis()}"
        val sessionFolder = File(sessionsDir, sessionId).apply { mkdirs() }

        val groupedEntries = mutableMapOf<String, FilePairEntry>()

        rootDoc.listFiles().forEach { doc ->
            if (!doc.isDirectory && doc.name != null) {
                val fullName = doc.name!!
                val ext = fullName.substringAfterLast('.', "").lowercase()
                val baseName = fullName.substringBeforeLast('.', "")

                if (RAW_EXTENSIONS.contains(ext) || JPG_EXTENSIONS.contains(ext)) {
                    val key = baseName.lowercase()
                    val entry = groupedEntries.getOrPut(key) { FilePairEntry(baseName = baseName) }

                    if (RAW_EXTENSIONS.contains(ext)) {
                        entry.rawDoc = doc
                        entry.rawExt = ext
                    } else if (JPG_EXTENSIONS.contains(ext)) {
                        entry.jpgDoc = doc
                        entry.jpgExt = ext
                    }
                }
            }
        }

        val entriesList = groupedEntries.values.toList()
        val totalCount = entriesList.size
        _importProgress.value = ImportProgress(
            isImporting = true,
            currentCount = 0,
            totalCount = totalCount,
            message = "対象写真のロード準備中...",
        )

        val photoItems = mutableListOf<PhotoItem>()

        entriesList.forEachIndexed { index, entry ->
            val displayIndex = index + 1
            _importProgress.value = ImportProgress(
                isImporting = true,
                currentCount = displayIndex,
                totalCount = totalCount,
                currentFileName = entry.baseName,
                message = "ローカルフォルダへコピー中 ($displayIndex/$totalCount): ${entry.baseName}",
            )

            var localRawPath: String? = null
            var localJpgPath: String? = null
            var totalSize = 0L

            entry.rawDoc?.let { rawDoc ->
                val rawFileName = "${entry.baseName}.${entry.rawExt}"
                val destRawFile = File(sessionFolder, rawFileName)
                try {
                    context.contentResolver.openInputStream(rawDoc.uri)?.use { input ->
                        FileOutputStream(destRawFile).use { output -> input.copyTo(output) }
                    }
                    localRawPath = destRawFile.absolutePath
                    totalSize += destRawFile.length()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy RAW file: ${e.message}")
                }
            }

            entry.jpgDoc?.let { jpgDoc ->
                val jpgFileName = "${entry.baseName}.${entry.jpgExt}"
                val destJpgFile = File(sessionFolder, jpgFileName)
                try {
                    context.contentResolver.openInputStream(jpgDoc.uri)?.use { input ->
                        FileOutputStream(destJpgFile).use { output -> input.copyTo(output) }
                    }
                    localJpgPath = destJpgFile.absolutePath
                    totalSize += destJpgFile.length()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy JPG file: ${e.message}")
                }
            }

            val photoType = when {
                localRawPath != null && localJpgPath != null -> PhotoType.RAW_AND_JPEG
                localRawPath != null -> PhotoType.RAW
                else -> PhotoType.STANDARD
            }

            val photoItem = PhotoItem(
                id = UUID.randomUUID().toString(),
                baseName = entry.baseName,
                rawPath = localRawPath,
                jpgPath = localJpgPath,
                rawExtension = entry.rawExt,
                jpgExtension = entry.jpgExt,
                fileSize = totalSize,
                fileType = photoType,
                selectionState = SelectionState.PENDING,
            )

            localRawPath?.let { rawPath ->
                photoItem.exifInfo = libRawBridge.parseExif(rawPath)
            }

            photoItems.add(photoItem)
        }

        saveSessionMetadata(sessionId, photoItems)

        _importProgress.value = ImportProgress(isImporting = false, currentCount = totalCount, totalCount = totalCount)
        return@withContext sessionId to photoItems
    }

    suspend fun saveSessionMetadata(sessionId: String, photoItems: List<PhotoItem>) = withContext(Dispatchers.IO) {
        val sessionFolder = File(sessionsDir, sessionId)
        if (!sessionFolder.exists()) sessionFolder.mkdirs()

        val metaFile = File(sessionFolder, "session_meta.json")
        val jsonArray = JSONArray()

        photoItems.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("baseName", item.baseName)
                put("rawPath", item.rawPath ?: "")
                put("jpgPath", item.jpgPath ?: "")
                put("rawExtension", item.rawExtension)
                put("jpgExtension", item.jpgExtension)
                put("fileSize", item.fileSize)
                put("fileType", item.fileType.name)
                put("selectionState", item.selectionState.name)
            }
            jsonArray.put(obj)
        }

        try {
            FileOutputStream(metaFile).use { fos ->
                fos.write(jsonArray.toString(2).toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session metadata: ${e.message}")
        }
    }

    suspend fun exportAcceptPhotosToUri(targetTreeUri: Uri, photoItems: List<PhotoItem>): Int = withContext(Dispatchers.IO) {
        val targetDoc = DocumentFile.fromTreeUri(context, targetTreeUri) ?: return@withContext 0
        val acceptItems = photoItems.filter { it.selectionState == SelectionState.ACCEPT }
        var exportedCount = 0

        acceptItems.forEach { item ->
            item.rawPath?.let { rawPath ->
                val sourceFile = File(rawPath)
                if (sourceFile.exists()) {
                    try {
                        val fileName = "${item.baseName}.${item.rawExtension}"
                        val newDoc = targetDoc.createFile("image/x-adobe-dng", fileName)
                        if (newDoc != null) {
                            context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                                sourceFile.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to export RAW file $rawPath: ${e.message}")
                    }
                }
            }

            item.jpgPath?.let { jpgPath ->
                val sourceFile = File(jpgPath)
                if (sourceFile.exists()) {
                    try {
                        val fileName = "${item.baseName}.${item.jpgExtension}"
                        val newDoc = targetDoc.createFile("image/jpeg", fileName)
                        if (newDoc != null) {
                            context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                                sourceFile.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to export JPG file $jpgPath: ${e.message}")
                    }
                }
            }

            exportedCount++
        }
        return@withContext exportedCount
    }
}
