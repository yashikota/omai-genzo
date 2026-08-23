package com.yashikota.omaigenzo.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.yashikota.omaigenzo.FolderImportManager
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.PhotoType
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class LocalPhotoRepository(
    private val context: Context,
    private val zeroCopyScanner: ZeroCopyFolderScanner = ZeroCopyFolderScanner(context),
    private val importManager: FolderImportManager = FolderImportManager(context),
) : PhotoRepository {

    private val preferences = context.getSharedPreferences("selection_session", Context.MODE_PRIVATE)
    private val photosState = MutableStateFlow(loadPhotos())
    override val photos: StateFlow<List<PhotoItem>> = photosState.asStateFlow()

    private val lastChangeState = MutableStateFlow(System.currentTimeMillis())
    override val lastStateChangeTime: StateFlow<Long> = lastChangeState.asStateFlow()

    private val historyStack = ArrayDeque<Pair<String, SelectionState>>()

    override suspend fun importSession(folderUri: Uri): Result<Unit> = try {
        val scanned = zeroCopyScanner.scanTreeUri(folderUri)
        photosState.value = scanned
        historyStack.clear()
        savePhotos(scanned)
        lastChangeState.value = System.currentTimeMillis()
        Result.success(Unit)
    } catch (e: Exception) {
        PerfLogger.event("import_error", "\"error\":\"${PerfLogger.escape(e.stackTraceToString())}\"")
        Result.failure(e)
    }

    override fun updateSelection(photoId: String, state: SelectionState) {
        val currentList = photosState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == photoId }
        if (index != -1) {
            val oldItem = currentList[index]
            historyStack.push(Pair(oldItem.id, oldItem.selectionState))
            currentList[index] = oldItem.copy(selectionState = state)
            photosState.value = currentList
            savePhotos(currentList)
            lastChangeState.value = System.currentTimeMillis()
        }
    }

    override fun undoLastSelection(): Boolean {
        if (historyStack.isEmpty()) return false
        val (photoId, previousState) = historyStack.pop()
        val currentList = photosState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == photoId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(selectionState = previousState)
            photosState.value = currentList
            savePhotos(currentList)
            lastChangeState.value = System.currentTimeMillis()
            return true
        }
        return false
    }

    override suspend fun exportAcceptedPhotos(outputUri: Uri): Result<Int> = try {
        val count = importManager.exportAcceptPhotosToUri(outputUri, photosState.value)
        if (photosState.value.any { it.selectionState == SelectionState.ACCEPT } && count == 0) {
            Result.failure(IllegalStateException("写真を1件も保存できませんでした"))
        } else {
            Result.success(count)
        }
    } catch (e: Exception) {
        PerfLogger.event("export_error", "\"error\":\"${PerfLogger.escape(e.stackTraceToString())}\"")
        Result.failure(e)
    }

    override fun clearSession() {
        photosState.value = emptyList()
        preferences.edit { clear() }
        historyStack.clear()
        lastChangeState.value = System.currentTimeMillis()
    }

    private fun savePhotos(photos: List<PhotoItem>) {
        val array = JSONArray()
        photos.forEach { photo ->
            array.put(
                JSONObject().apply {
                    put("id", photo.id)
                    put("baseName", photo.baseName)
                    put("rawPath", photo.rawPath)
                    put("jpgPath", photo.jpgPath)
                    put("rawUri", photo.rawUriString)
                    put("jpgUri", photo.jpgUriString)
                    put("rawExtension", photo.rawExtension)
                    put("jpgExtension", photo.jpgExtension)
                    put("fileSize", photo.fileSize)
                    put("modifiedAt", photo.modifiedAt)
                    put("fileType", photo.fileType.name)
                    put("selectionState", photo.selectionState.name)
                },
            )
        }
        preferences.edit { putString("photos", array.toString()) }
    }

    private fun loadPhotos(): List<PhotoItem> = try {
        val value = preferences.getString("photos", null) ?: return emptyList()
        val array = JSONArray(value)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            PhotoItem(
                id = item.getString("id"),
                baseName = item.getString("baseName"),
                rawPath = item.optNullableString("rawPath"),
                jpgPath = item.optNullableString("jpgPath"),
                rawUriString = item.optNullableString("rawUri"),
                jpgUriString = item.optNullableString("jpgUri"),
                rawExtension = item.optString("rawExtension"),
                jpgExtension = item.optString("jpgExtension"),
                fileSize = item.optLong("fileSize"),
                modifiedAt = item.optLong("modifiedAt"),
                fileType = PhotoType.valueOf(item.optString("fileType", PhotoType.STANDARD.name)),
                selectionState = SelectionState.valueOf(item.optString("selectionState", SelectionState.PENDING.name)),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
}
