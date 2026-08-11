package com.yashikota.omaigenzo

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yashikota.omaigenzo.ui.*
import com.yashikota.omaigenzo.ui.theme.OmaiGenzoTheme
import kotlinx.coroutines.launch

enum class CurrentScreen {
    FOLDER_SELECT,
    SWIPE_SELECTION,
    GALLERY,
    COMPLETION,
}

class MainActivity : ComponentActivity() {

    private lateinit var libRawBridge: LibRawBridge
    private lateinit var importManager: FolderImportManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        libRawBridge = LibRawBridge()
        importManager = FolderImportManager(this)

        setContent {
            OmaiGenzoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppMainContent(libRawBridge = libRawBridge, importManager = importManager)
                }
            }
        }
    }
}

@Composable
fun AppMainContent(
    libRawBridge: LibRawBridge,
    importManager: FolderImportManager,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(CurrentScreen.FOLDER_SELECT) }
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentSessionId by remember { mutableStateOf("") }

    val historyStack = remember { mutableStateListOf<Int>() }

    val importProgress by importManager.importProgress.collectAsStateWithLifecycle()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val (sessionId, importedPhotos) = importManager.importFolderFromUri(uri)
                if (importedPhotos.isNotEmpty()) {
                    currentSessionId = sessionId
                    photos = importedPhotos
                    currentIndex = 0
                    historyStack.clear()
                    currentScreen = CurrentScreen.SWIPE_SELECTION
                } else {
                    Toast.makeText(
                        context,
                        "選択したフォルダに対象画像がありませんでした",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val count = importManager.exportAcceptPhotosToUri(uri, photos)
                Toast.makeText(
                    context,
                    "ACCEPT 写真 $count 枚を正常に保存・書き出しました！",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun saveCurrentState() {
        if (currentSessionId.isNotEmpty()) {
            coroutineScope.launch {
                importManager.saveSessionMetadata(currentSessionId, photos)
            }
        }
    }

    when (currentScreen) {
        CurrentScreen.FOLDER_SELECT -> {
            FolderSelectScreen(
                importProgress = importProgress,
                onSelectFolderClick = {
                    folderPickerLauncher.launch(null)
                },
            )
        }

        CurrentScreen.SWIPE_SELECTION -> {
            SwipeSelectionScreen(
                photos = photos,
                currentIndex = currentIndex,
                libRawBridge = libRawBridge,
                onSwipeAccept = { photo ->
                    photo.selectionState = SelectionState.ACCEPT
                    historyStack.add(currentIndex)
                    currentIndex++
                    saveCurrentState()
                },
                onSwipeReject = { photo ->
                    photo.selectionState = SelectionState.REJECT
                    historyStack.add(currentIndex)
                    currentIndex++
                    saveCurrentState()
                },
                onSwipeUndo = {
                    if (historyStack.isNotEmpty()) {
                        val previousIndex = historyStack.removeAt(historyStack.size - 1)
                        if (previousIndex in photos.indices) {
                            photos[previousIndex].selectionState = SelectionState.PENDING
                            currentIndex = previousIndex
                            saveCurrentState()
                        }
                    }
                },
                onSwipeSkip = { photo ->
                    historyStack.add(currentIndex)
                    currentIndex++
                },
                onOpenGallery = {
                    currentScreen = CurrentScreen.GALLERY
                },
                onFinishSelection = {
                    currentScreen = CurrentScreen.COMPLETION
                },
            )
        }

        CurrentScreen.COMPLETION -> {
            CompletionScreen(
                photos = photos,
                onRestartSelection = {
                    photos.forEach { it.selectionState = SelectionState.PENDING }
                    currentIndex = 0
                    historyStack.clear()
                    saveCurrentState()
                    currentScreen = CurrentScreen.SWIPE_SELECTION
                },
                onOpenGallery = {
                    currentScreen = CurrentScreen.GALLERY
                },
                onExportAcceptPhotos = {
                    exportFolderLauncher.launch(null)
                },
            )
        }

        CurrentScreen.GALLERY -> {
            GalleryScreen(
                photos = photos,
                libRawBridge = libRawBridge,
                onBackToSwipe = {
                    currentScreen = CurrentScreen.SWIPE_SELECTION
                },
                onPhotoSelectionChanged = { photo, newState ->
                    photo.selectionState = newState
                    saveCurrentState()
                },
                onExportAcceptPhotos = {
                    exportFolderLauncher.launch(null)
                },
            )
        }
    }
}
