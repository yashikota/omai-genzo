package com.yashikota.omaigenzo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yashikota.omaigenzo.data.LocalPhotoRepository
import com.yashikota.omaigenzo.ui.CompletionScreen
import com.yashikota.omaigenzo.ui.FolderSelectScreen
import com.yashikota.omaigenzo.ui.GalleryScreen
import com.yashikota.omaigenzo.ui.MainUiAction
import com.yashikota.omaigenzo.ui.MainViewModel
import com.yashikota.omaigenzo.ui.ScreenState
import com.yashikota.omaigenzo.ui.SwipeSelectionScreen
import com.yashikota.omaigenzo.ui.theme.OmaiGenzoTheme

class MainActivity : ComponentActivity() {

    private lateinit var libRawBridge: LibRawBridge
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libRawBridge = LibRawBridge()

        val repository = LocalPhotoRepository(applicationContext)
        viewModel = MainViewModel(repository)

        setContent {
            OmaiGenzoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OmaiGenzoApp(
                        viewModel = viewModel,
                        libRawBridge = libRawBridge,
                    )
                }
            }
        }
    }
}

@Composable
fun OmaiGenzoApp(
    viewModel: MainViewModel,
    libRawBridge: LibRawBridge,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            viewModel.onAction(MainUiAction.ClearError)
        }
    }

    LaunchedEffect(uiState.exportSuccessMessage) {
        uiState.exportSuccessMessage?.let {
            viewModel.onAction(MainUiAction.ClearExportMessage)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onAction(MainUiAction.SelectFolder(it))
        }
    }

    val exportPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onAction(MainUiAction.ExportAcceptedPhotos(it))
        }
    }

    when (uiState.currentScreen) {
        ScreenState.FOLDER_SELECT -> {
            FolderSelectScreen(
                onSelectFolderClick = { folderPickerLauncher.launch(null) },
                isImporting = uiState.isImporting,
            )
        }
        ScreenState.SWIPE_SELECTION -> {
            SwipeSelectionScreen(
                photos = uiState.photos,
                currentIndex = uiState.currentIndex,
                libRawBridge = libRawBridge,
                onSwipeAccept = { viewModel.onAction(MainUiAction.SwipeAccept(it)) },
                onSwipeReject = { viewModel.onAction(MainUiAction.SwipeReject(it)) },
                onSwipeUndo = { viewModel.onAction(MainUiAction.SwipeUndo) },
                onSwipeSkip = { viewModel.onAction(MainUiAction.SwipeSkip(it)) },
                onOpenGallery = { viewModel.onAction(MainUiAction.NavigateTo(ScreenState.GALLERY)) },
                onFinishSelection = { viewModel.onAction(MainUiAction.NavigateTo(ScreenState.COMPLETION)) },
            )
        }
        ScreenState.GALLERY -> {
            GalleryScreen(
                photos = uiState.photos,
                libRawBridge = libRawBridge,
                onBackToSwipe = { viewModel.onAction(MainUiAction.NavigateTo(ScreenState.SWIPE_SELECTION)) },
                onSelectionChanged = { photoId, newState ->
                    viewModel.onAction(MainUiAction.ChangePhotoSelection(photoId, newState))
                },
                onExportClick = { exportPickerLauncher.launch(null) },
            )
        }
        ScreenState.COMPLETION -> {
            CompletionScreen(
                photos = uiState.photos,
                onResetClick = { viewModel.onAction(MainUiAction.ResetSession) },
                onGalleryClick = { viewModel.onAction(MainUiAction.NavigateTo(ScreenState.GALLERY)) },
                onExportClick = { exportPickerLauncher.launch(null) },
            )
        }
    }
}
