package com.yashikota.omaigenzo.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.yashikota.omaigenzo.FolderImportManager
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.PhotoType
import com.yashikota.omaigenzo.SelectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class LocalPhotoRepository(
    private val context: Context,
    private val zeroCopyScanner: ZeroCopyFolderScanner = ZeroCopyFolderScanner(context),
    private val importManager: FolderImportManager = FolderImportManager(context),
) : PhotoRepository {

    private companion object {
        const val KEY_PHOTOS = "photos"
        const val KEY_SELECTION = "selection"
        const val MAX_UNDO_DEPTH = 1_024
    }

    private val preferences = context.getSharedPreferences("selection_session", Context.MODE_PRIVATE)
    private val store = PhotoSelectionStore(loadPhotos(), MAX_UNDO_DEPTH)
    private val photosState = MutableStateFlow(store.photos)
    override val photos: StateFlow<List<PhotoItem>> = photosState.asStateFlow()

    private val lastChangeState = MutableStateFlow(System.currentTimeMillis())
    override val lastStateChangeTime: StateFlow<Long> = lastChangeState.asStateFlow()

    // Selection writes never touch the UI thread: one tiny string, coalesced, on a single worker.
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "omai-selection-writer").apply { isDaemon = true }
    }
    private val prefsLock = Any()

    @Volatile private var sessionGeneration = 0
    private val selectionWriter = CoalescingWriter<PendingSelection>(persistExecutor) { pending ->
        synchronized(prefsLock) {
            // A snapshot taken before an import or reset must not resurrect the old session.
            if (pending.generation == sessionGeneration) {
                val encoded = SelectionCodec.encode(pending.photos.map { it.selectionState })
                preferences.edit { putString(KEY_SELECTION, encoded) }
            }
        }
    }

    /** Published lists are never mutated, so the writer thread can encode one without copying it. */
    private class PendingSelection(val generation: Int, val photos: List<PhotoItem>)

    override suspend fun importSession(folderUri: Uri): Result<Unit> = try {
        val scanned = zeroCopyScanner.scanTreeUri(folderUri)
        withContext(Dispatchers.IO) {
            synchronized(prefsLock) {
                sessionGeneration++
                savePhotos(scanned)
            }
        }
        store.replace(scanned)
        photosState.value = store.photos
        lastChangeState.value = System.currentTimeMillis()
        Result.success(Unit)
    } catch (e: Exception) {
        PerfLogger.event("import_error", "\"error\":\"${PerfLogger.escape(e.stackTraceToString())}\"")
        Result.failure(e)
    }

    override fun updateSelection(photoId: String, state: SelectionState) {
        if (store.update(photoId, state)) publishSelection()
    }

    override fun undoLastSelection(): Boolean {
        if (!store.undo()) return false
        publishSelection()
        return true
    }

    private fun publishSelection() {
        val next = store.photos
        photosState.value = next
        selectionWriter.submit(PendingSelection(sessionGeneration, next))
        lastChangeState.value = System.currentTimeMillis()
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
        store.clear()
        photosState.value = store.photos
        // apply() only updates memory synchronously, so this is cheap and stays ordered with imports.
        synchronized(prefsLock) {
            sessionGeneration++
            preferences.edit { clear() }
        }
        lastChangeState.value = System.currentTimeMillis()
    }

    /** Written once per import. The per-swipe state lives in [KEY_SELECTION]. */
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
        preferences.edit {
            putString(KEY_PHOTOS, array.toString())
            putString(KEY_SELECTION, SelectionCodec.encode(photos.map { it.selectionState }))
        }
    }

    private fun loadPhotos(): List<PhotoItem> = try {
        val value = preferences.getString(KEY_PHOTOS, null) ?: return emptyList()
        val array = JSONArray(value)
        val items = List(array.length()) { index ->
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
        // Sessions saved before the compact snapshot existed keep their per-item state.
        val snapshot = preferences.getString(KEY_SELECTION, null)?.let { SelectionCodec.decode(it, items.size) }
        if (snapshot == null) items else items.mapIndexed { i, item -> item.copy(selectionState = snapshot[i]) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
}
