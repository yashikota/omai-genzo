package com.yashikota.omaigenzo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.FilterCategory
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    photos: List<PhotoItem>,
    libRawBridge: LibRawBridge,
    onBackToSwipe: () -> Unit,
    onSelectionChanged: (String, SelectionState) -> Unit = { _, _ -> },
    onPhotoSelectionChanged: (PhotoItem, SelectionState) -> Unit = { photo, state -> onSelectionChanged(photo.id, state) },
    onExportClick: () -> Unit = {},
    onExportAcceptPhotos: () -> Unit = onExportClick,
) {
    var selectedFilter by remember { mutableStateOf(FilterCategory.ALL) }
    var selectedPhotoForDetail by remember { mutableStateOf<PhotoItem?>(null) }

    val filteredPhotos = remember(photos, selectedFilter) {
        when (selectedFilter) {
            FilterCategory.ALL -> photos
            FilterCategory.ACCEPT -> photos.filter { it.selectionState == SelectionState.ACCEPT }
            FilterCategory.REJECT -> photos.filter { it.selectionState == SelectionState.REJECT }
            FilterCategory.PENDING -> photos.filter { it.selectionState == SelectionState.PENDING }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "選別結果ギャラリー",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackToSwipe) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る", tint = PrimaryNeon)
                    }
                },
                actions = {
                    IconButton(onClick = onExportAcceptPhotos) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = "書き出し", tint = AcceptGreen)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground),
            )
        },
        containerColor = DarkBackground,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
        ) {
            // Filter Tab Bar
            ScrollableTabRow(
                selectedTabIndex = selectedFilter.ordinal,
                containerColor = DarkBackground,
                contentColor = PrimaryNeon,
                edgePadding = 0.dp,
                divider = {},
            ) {
                FilterCategory.values().forEach { category ->
                    val count = when (category) {
                        FilterCategory.ALL -> photos.size
                        FilterCategory.ACCEPT -> photos.count { it.selectionState == SelectionState.ACCEPT }
                        FilterCategory.REJECT -> photos.count { it.selectionState == SelectionState.REJECT }
                        FilterCategory.PENDING -> photos.count { it.selectionState == SelectionState.PENDING }
                    }
                    Tab(
                        selected = selectedFilter == category,
                        onClick = { selectedFilter = category },
                        text = {
                            Text(
                                text = "${category.name} ($count)",
                                fontWeight = if (selectedFilter == category) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Photo Grid
            if (filteredPhotos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "該当する写真がありません", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredPhotos, key = { it.id }) { photo ->
                        GalleryItemCard(
                            photoItem = photo,
                            libRawBridge = libRawBridge,
                            onClick = { selectedPhotoForDetail = photo },
                        )
                    }
                }
            }
        }

        // Full Detail Modal Lightbox
        if (selectedPhotoForDetail != null) {
            val photo = selectedPhotoForDetail!!
            AlertDialog(
                onDismissRequest = { selectedPhotoForDetail = null },
                confirmButton = {
                    TextButton(onClick = { selectedPhotoForDetail = null }) {
                        Text("閉じる", color = PrimaryNeon)
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = photo.displayFileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = photo.displayBadge, fontSize = 12.sp, color = PrimaryNeon, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurfaceVariant),
                        ) {
                            PhotoCardView(
                                photoItem = photo,
                                libRawBridge = libRawBridge,
                                showExifOverlay = true,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Button(
                                onClick = {
                                    onPhotoSelectionChanged(photo, SelectionState.ACCEPT)
                                    selectedPhotoForDetail = photo.copy(selectionState = SelectionState.ACCEPT)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (photo.selectionState == SelectionState.ACCEPT) AcceptGreen else DarkSurface,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "ACCEPT",
                                    color = if (photo.selectionState == SelectionState.ACCEPT) DarkBackground else AcceptGreen,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Button(
                                onClick = {
                                    onPhotoSelectionChanged(photo, SelectionState.REJECT)
                                    selectedPhotoForDetail = photo.copy(selectionState = SelectionState.REJECT)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (photo.selectionState == SelectionState.REJECT) RejectRed else DarkSurface,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "REJECT",
                                    color = if (photo.selectionState == SelectionState.REJECT) TextPrimary else RejectRed,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun GalleryItemCard(
    photoItem: PhotoItem,
    libRawBridge: LibRawBridge,
    onClick: () -> Unit,
) {
    var thumbnail by remember(photoItem.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photoItem.id) {
        thumbnail = libRawBridge.loadPhotoBitmap(
            filePath = photoItem.fastDisplayPath,
            isRaw = photoItem.rawPath != null && photoItem.jpgPath == null,
            fastMode = true,
        )
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .border(
                width = 1.5.dp,
                color = when (photoItem.selectionState) {
                    SelectionState.ACCEPT -> AcceptGreen
                    SelectionState.REJECT -> RejectRed
                    SelectionState.PENDING -> BorderColor
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() },
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = photoItem.displayFileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PrimaryNeon)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(CircleShape)
                .background(
                    when (photoItem.selectionState) {
                        SelectionState.ACCEPT -> AcceptGreen
                        SelectionState.REJECT -> RejectRed
                        SelectionState.PENDING -> Color.Gray
                    },
                )
                .padding(4.dp),
        ) {
            Icon(
                imageVector = when (photoItem.selectionState) {
                    SelectionState.ACCEPT -> Icons.Default.Check
                    SelectionState.REJECT -> Icons.Default.Close
                    SelectionState.PENDING -> Icons.Default.QuestionMark
                },
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(12.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = photoItem.displayBadge,
                color = if (photoItem.isRawFile()) PrimaryNeon else AcceptGreen,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
