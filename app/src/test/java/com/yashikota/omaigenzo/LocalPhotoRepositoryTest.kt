package com.yashikota.omaigenzo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.yashikota.omaigenzo.data.LocalPhotoRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Session persistence and SAF scanning of [LocalPhotoRepository] against Robolectric's
 * SharedPreferences and a fake documents provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPhotoRepositoryTest {

    private companion object {
        const val AUTHORITY = "com.yashikota.omaigenzo.test.documents"
        const val PREFS = "selection_session"
    }

    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeDocumentsProvider.rows = emptyList()
        FakeDocumentsProvider.failure = null
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY)
    }

    @After
    fun tearDown() {
        prefs.edit(commit = true) { clear() }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun photoJson(id: String, state: SelectionState = SelectionState.PENDING, configure: JSONObject.() -> Unit = {}) = JSONObject().apply {
        put("id", id)
        put("baseName", "IMG_$id")
        put("fileType", PhotoType.STANDARD.name)
        put("selectionState", state.name)
        configure()
    }

    private fun seed(vararg photos: JSONObject, selection: String? = null) {
        prefs.edit(commit = true) {
            putString("photos", JSONArray(photos.toList()).toString())
            if (selection != null) putString("selection", selection)
        }
    }

    /** Waits until every selection write queued so far has reached SharedPreferences. */
    private fun LocalPhotoRepository.awaitPersisted() {
        val field = LocalPhotoRepository::class.java.getDeclaredField("persistExecutor").apply { isAccessible = true }
        (field.get(this) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun LocalPhotoRepository.states() = photos.value.map { it.selectionState }

    private fun row(id: String, name: String, mime: String = "image/jpeg", size: Long? = 1_000L, modified: Long? = 0L) = arrayOf<Any?>(id, name, mime, size, modified)

    // --- restoring a session ---------------------------------------------------------------------

    @Test
    fun aFreshInstallStartsWithNoPhotos() {
        val repository = LocalPhotoRepository(context)
        assertTrue(repository.photos.value.isEmpty())
    }

    @Test
    fun aSavedSessionIsRestoredWithEveryField() {
        seed(
            photoJson("a", SelectionState.ACCEPT) {
                put("rawUri", "content://p/a.arw")
                put("jpgUri", "content://p/a.jpg")
                put("rawExtension", "arw")
                put("jpgExtension", "jpg")
                put("fileSize", 123L)
                put("modifiedAt", 456L)
                put("fileType", PhotoType.RAW_AND_JPEG.name)
            },
        )

        val photo = LocalPhotoRepository(context).photos.value.single()

        assertEquals(
            PhotoItem(
                id = "a",
                baseName = "IMG_a",
                rawUriString = "content://p/a.arw",
                jpgUriString = "content://p/a.jpg",
                rawExtension = "arw",
                jpgExtension = "jpg",
                fileSize = 123L,
                modifiedAt = 456L,
                fileType = PhotoType.RAW_AND_JPEG,
                selectionState = SelectionState.ACCEPT,
            ),
            photo,
        )
    }

    @Test
    fun missingAndEmptyOptionalFieldsBecomeNull() {
        seed(photoJson("a") { put("rawPath", "") })

        val photo = LocalPhotoRepository(context).photos.value.single()

        assertNull(photo.rawPath)
        assertNull(photo.jpgPath)
        assertNull(photo.rawUriString)
        assertEquals("", photo.rawExtension)
        assertEquals(0L, photo.fileSize)
    }

    @Test
    fun theCompactSnapshotOverridesPerPhotoStates() {
        seed(photoJson("a"), photoJson("b"), photoJson("c"), selection = "ARP")

        assertEquals(
            listOf(SelectionState.ACCEPT, SelectionState.REJECT, SelectionState.PENDING),
            LocalPhotoRepository(context).states(),
        )
    }

    @Test
    fun sessionsSavedBeforeTheSnapshotKeepTheirPerPhotoStates() {
        seed(photoJson("a", SelectionState.REJECT), photoJson("b", SelectionState.ACCEPT))

        assertEquals(listOf(SelectionState.REJECT, SelectionState.ACCEPT), LocalPhotoRepository(context).states())
    }

    @Test
    fun aSnapshotForADifferentPhotoCountIsIgnored() {
        seed(photoJson("a", SelectionState.REJECT), photoJson("b"), selection = "AAA")

        assertEquals(listOf(SelectionState.REJECT, SelectionState.PENDING), LocalPhotoRepository(context).states())
    }

    @Test
    fun corruptStorageNeverCrashesTheApp() {
        prefs.edit(commit = true) { putString("photos", "{not json") }
        assertTrue(LocalPhotoRepository(context).photos.value.isEmpty())

        seed(photoJson("a") { put("fileType", "HOLOGRAM") })
        assertTrue(LocalPhotoRepository(context).photos.value.isEmpty())
    }

    // --- selection -------------------------------------------------------------------------------

    @Test
    fun aSelectionIsPublishedAndSurvivesARestart() {
        seed(photoJson("a"), photoJson("b"))
        val repository = LocalPhotoRepository(context)

        repository.updateSelection("b", SelectionState.ACCEPT)

        assertEquals(listOf(SelectionState.PENDING, SelectionState.ACCEPT), repository.states())
        repository.awaitPersisted()
        assertEquals("PA", prefs.getString("selection", null))
        assertEquals(listOf(SelectionState.PENDING, SelectionState.ACCEPT), LocalPhotoRepository(context).states())
    }

    @Test
    fun aBurstOfSwipesPersistsTheFinalState() {
        seed(*Array(20) { photoJson("p$it") })
        val repository = LocalPhotoRepository(context)

        repeat(20) { repository.updateSelection("p$it", if (it % 2 == 0) SelectionState.ACCEPT else SelectionState.REJECT) }
        repository.awaitPersisted()

        assertEquals("AR".repeat(10), prefs.getString("selection", null))
    }

    @Test
    fun anUnknownPhotoIdChangesNothing() {
        seed(photoJson("a"))
        val repository = LocalPhotoRepository(context)
        val before = repository.photos.value
        val changedAt = repository.lastStateChangeTime.value

        repository.updateSelection("missing", SelectionState.ACCEPT)

        assertSame(before, repository.photos.value)
        assertEquals(changedAt, repository.lastStateChangeTime.value)
    }

    @Test
    fun undoRevertsAndPersistsTheLastSelection() {
        seed(photoJson("a"))
        val repository = LocalPhotoRepository(context)
        repository.updateSelection("a", SelectionState.REJECT)

        assertTrue(repository.undoLastSelection())

        assertEquals(listOf(SelectionState.PENDING), repository.states())
        repository.awaitPersisted()
        assertEquals("P", prefs.getString("selection", null))
        assertFalse(repository.undoLastSelection())
    }

    @Test
    fun undoHistoryDoesNotSurviveARestart() {
        seed(photoJson("a"))
        LocalPhotoRepository(context).apply {
            updateSelection("a", SelectionState.ACCEPT)
            awaitPersisted()
        }

        assertFalse(LocalPhotoRepository(context).undoLastSelection())
    }

    @Test
    fun selectionsBumpTheLastChangeTime() {
        seed(photoJson("a"))
        val repository = LocalPhotoRepository(context)
        val before = repository.lastStateChangeTime.value
        Thread.sleep(2)

        repository.updateSelection("a", SelectionState.ACCEPT)

        assertTrue(repository.lastStateChangeTime.value > before)
    }

    // --- clearing --------------------------------------------------------------------------------

    @Test
    fun clearingWipesMemoryAndStorage() {
        seed(photoJson("a"), selection = "A")
        val repository = LocalPhotoRepository(context)

        repository.clearSession()

        assertTrue(repository.photos.value.isEmpty())
        assertTrue(prefs.all.isEmpty())
        assertTrue(LocalPhotoRepository(context).photos.value.isEmpty())
    }

    @Test
    fun aWriteQueuedBeforeAClearNeverResurrectsTheOldSession() {
        seed(photoJson("a"), photoJson("b"))
        val repository = LocalPhotoRepository(context)

        repository.updateSelection("a", SelectionState.ACCEPT)
        repository.clearSession()
        repository.awaitPersisted()

        assertNull(prefs.getString("selection", null))
        assertNull(prefs.getString("photos", null))
    }

    // --- importing -------------------------------------------------------------------------------

    @Test
    fun importingScansTheFolderPairsFilesAndSkipsDirectories() = runBlocking {
        FakeDocumentsProvider.rows = listOf(
            row("1", "DSC0001.ARW", "image/x-sony-arw", size = 25_000_000L, modified = 10L),
            row("2", "DSC0001.JPG", size = 5_000_000L, modified = 20L),
            row("3", "DSC0002.NEF", "image/x-nikon-nef", size = null, modified = null),
            row("4", "Sub.JPG", DocumentsContract.Document.MIME_TYPE_DIR),
            row("5", "notes.txt", "text/plain"),
        )
        val repository = LocalPhotoRepository(context)

        assertTrue(repository.importSession(treeUri).isSuccess)

        val photos = repository.photos.value
        assertEquals(listOf("DSC0001", "DSC0002"), photos.map { it.baseName })
        assertEquals(PhotoType.RAW_AND_JPEG, photos[0].fileType)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "1").toString(), photos[0].rawUriString)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "2").toString(), photos[0].jpgUriString)
        assertEquals(photos[0].rawUriString, photos[0].id)
        assertEquals(30_000_000L, photos[0].fileSize)
        assertEquals(20L, photos[0].modifiedAt)
        assertEquals(PhotoType.RAW, photos[1].fileType)
        assertEquals("unknown sizes count as zero", 0L, photos[1].fileSize)
        assertTrue(photos.all { it.rawPath == null && it.jpgPath == null })
    }

    @Test
    fun anImportedSessionIsRestoredAfterARestart() = runBlocking {
        FakeDocumentsProvider.rows = listOf(row("1", "A.JPG"), row("2", "B.JPG"))
        val first = LocalPhotoRepository(context)
        first.importSession(treeUri)

        val restored = LocalPhotoRepository(context).photos.value

        assertEquals(first.photos.value, restored)
        assertEquals("PP", prefs.getString("selection", null))
    }

    @Test
    fun anImportReplacesTheOldSessionAndItsUndoHistory() = runBlocking {
        seed(photoJson("old", SelectionState.ACCEPT))
        val repository = LocalPhotoRepository(context)
        repository.updateSelection("old", SelectionState.REJECT)
        FakeDocumentsProvider.rows = listOf(row("1", "NEW.JPG"))

        repository.importSession(treeUri)

        assertEquals(listOf("NEW"), repository.photos.value.map { it.baseName })
        assertEquals(SelectionState.PENDING, repository.photos.value.single().selectionState)
        assertFalse(repository.undoLastSelection())
        repository.awaitPersisted()
        assertEquals("a stale swipe snapshot must not overwrite the new session", "P", prefs.getString("selection", null))
    }

    @Test
    fun anEmptyFolderImportsAsAnEmptySession() = runBlocking {
        seed(photoJson("old"))
        val repository = LocalPhotoRepository(context)

        assertTrue(repository.importSession(treeUri).isSuccess)

        assertTrue(repository.photos.value.isEmpty())
        assertTrue(LocalPhotoRepository(context).photos.value.isEmpty())
    }

    @Test
    fun aFailingProviderIsReportedAndKeepsTheCurrentSession() = runBlocking {
        seed(photoJson("keep"))
        val repository = LocalPhotoRepository(context)
        FakeDocumentsProvider.failure = SecurityException("permission revoked")

        val result = repository.importSession(treeUri)

        assertTrue(result.isFailure)
        assertEquals("permission revoked", result.exceptionOrNull()?.message)
        assertEquals(listOf("keep"), repository.photos.value.map { it.id })
        assertEquals(listOf("keep"), LocalPhotoRepository(context).photos.value.map { it.id })
    }

    @Test
    fun aUriThatIsNotADocumentTreeIsReportedAsAFailure() = runBlocking {
        val result = LocalPhotoRepository(context).importSession(Uri.parse("content://$AUTHORITY/not-a-tree"))
        assertTrue(result.isFailure)
    }

    // --- exporting -------------------------------------------------------------------------------

    @Test
    fun exportingWithNothingKeptSucceedsWithZero() = runBlocking {
        seed(photoJson("a", SelectionState.REJECT), photoJson("b"))

        val result = LocalPhotoRepository(context).exportAcceptedPhotos(treeUri)

        assertEquals(0, result.getOrNull())
    }

    @Test
    fun exportingKeptPhotosThatCannotBeWrittenIsAFailure() = runBlocking {
        seed(
            photoJson("a", SelectionState.ACCEPT) {
                put("jpgUri", "content://$AUTHORITY/missing")
                put("jpgExtension", "jpg")
            },
        )

        val result = LocalPhotoRepository(context).exportAcceptedPhotos(treeUri)

        assertTrue(result.isFailure)
    }

    /** Answers every query with [rows] in the column order the scanner asks for. */
    class FakeDocumentsProvider : ContentProvider() {
        companion object {
            @Volatile var rows: List<Array<Any?>> = emptyList()

            @Volatile var failure: RuntimeException? = null
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            failure?.let { throw it }
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val order = listOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            return MatrixCursor(columns).apply {
                rows.forEach { row -> addRow(columns.map { row[order.indexOf(it)] }) }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
