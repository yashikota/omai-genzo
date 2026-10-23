package com.yashikota.omaigenzo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.yashikota.omaigenzo.data.ExportEntry
import com.yashikota.omaigenzo.data.ExportFile
import com.yashikota.omaigenzo.data.ExportOptions
import com.yashikota.omaigenzo.data.ExportPlanner
import com.yashikota.omaigenzo.data.ExportProgress
import com.yashikota.omaigenzo.data.ExportReport
import com.yashikota.omaigenzo.data.ExportSource
import com.yashikota.omaigenzo.data.FileCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
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

    private val libRawBridge = LibRawBridge(context)

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

    suspend fun exportAcceptPhotosToUri(targetTreeUri: Uri, photoItems: List<PhotoItem>): Int {
        val entries = ExportPlanner.plan(photoItems, ExportOptions(writeXmpSidecar = false, skipExisting = false))
        return exportToUri(targetTreeUri, entries, skipExisting = false).exportedPhotos
    }

    /**
     * Copies [entries] into [targetTreeUri]. Existing names are read with one listing up front instead
     * of one provider lookup per file. Cancellation is checked between files; a file whose copy fails
     * is removed, so the destination never keeps a truncated photo.
     */
    suspend fun exportToUri(
        targetTreeUri: Uri,
        entries: List<ExportEntry>,
        skipExisting: Boolean = true,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportReport = withContext(Dispatchers.IO) {
        val targetDoc = DocumentFile.fromTreeUri(context, targetTreeUri)
            ?: throw IllegalStateException("書き出し先フォルダを開けませんでした")
        val existing = HashSet<String>()
        if (skipExisting) targetDoc.listFiles().mapNotNullTo(existing) { it.name?.lowercase() }
        var exportedPhotos = 0
        var written = 0
        var skipped = 0
        var failed = 0

        for ((index, entry) in entries.withIndex()) {
            onProgress(ExportProgress(index, entries.size, entry.displayName))
            var photoDone = false
            for (file in entry.files) {
                if (!isActive) return@withContext ExportReport(exportedPhotos, written, skipped, failed, cancelled = true)
                val key = file.fileName.lowercase()
                if (skipExisting && key in existing) {
                    skipped++
                    photoDone = true
                    continue
                }
                if (writeFile(targetDoc, file)) {
                    written++
                    photoDone = true
                    existing += key
                } else {
                    failed++
                }
            }
            if (photoDone) exportedPhotos++
        }
        onProgress(ExportProgress(entries.size, entries.size))
        ExportReport(exportedPhotos, written, skipped, failed)
    }

    private fun writeFile(targetDoc: DocumentFile, file: ExportFile): Boolean {
        val newDoc = try {
            targetDoc.createFile(file.mimeType, file.fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ${file.fileName}: ${e.message}")
            null
        } ?: return false
        val written = try {
            // Descriptors rather than streams, so FileCopy can hand the copy to the kernel.
            val target = context.contentResolver.openFileDescriptor(newDoc.uri, "w")
            target != null &&
                ParcelFileDescriptor.AutoCloseOutputStream(target).use { destination ->
                    when (val source = file.source) {
                        is ExportSource.Inline -> {
                            destination.write(source.bytes)
                            true
                        }
                        is ExportSource.LocalFile -> File(source.path).takeIf(File::exists)?.let { input ->
                            FileInputStream(input).use { FileCopy.copy(it, destination) }
                        } != null
                        is ExportSource.ContentUri -> context.contentResolver.openFileDescriptor(source.uriString.toUri(), "r")?.let { input ->
                            ParcelFileDescriptor.AutoCloseInputStream(input).use { FileCopy.copy(it, destination) }
                        } != null
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export ${file.fileName}: ${e.message}")
            false
        }
        if (!written) runCatching { newDoc.delete() }
        return written
    }
}
