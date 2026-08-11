package com.yashikota.omaigenzo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeSelectionScreen(
    photos: List<PhotoItem>,
    currentIndex: Int,
    libRawBridge: LibRawBridge,
    onSwipeAccept: (PhotoItem) -> Unit,
    onSwipeReject: (PhotoItem) -> Unit,
    onSwipeUndo: () -> Unit,
    onSwipeSkip: (PhotoItem) -> Unit,
    onOpenGallery: () -> Unit,
    onFinishSelection: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    val thresholdX = 250f
    val thresholdY = 220f

    val currentPhoto = photos.getOrNull(currentIndex)

    val acceptCount = photos.count { it.selectionState == SelectionState.ACCEPT }
    val rejectCount = photos.count { it.selectionState == SelectionState.REJECT }
    val totalCount = photos.size

    LaunchedEffect(currentIndex, photos.size) {
        if (photos.isNotEmpty() && currentIndex >= photos.size) {
            onFinishSelection()
        }
    }

    // Reset offsets when photo changes
    LaunchedEffect(currentIndex) {
        offsetX = 0f
        offsetY = 0f
        zoomScale = 1f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "おまいGENZO!",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = PrimaryNeon,
                            fontFamily = FontFamily.SansSerif
                        )
                        Text(
                            text = if (totalCount > 0) "${currentIndex + 1} / $totalCount" else "",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGallery) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "ギャラリー表示",
                            tint = PrimaryNeon
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Progress Dashboard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AcceptGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "$acceptCount", color = AcceptGreen, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }

                LinearProgressIndicator(
                    progress = { if (totalCount > 0) (currentIndex.toFloat() / totalCount.toFloat()) else 0f },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = PrimaryNeon,
                    trackColor = BorderColor,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(RejectRed)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "$rejectCount", color = RejectRed, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Card Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (currentPhoto != null) {
                    // Background Next Card Preview
                    val nextPhoto = photos.getOrNull(currentIndex + 1)
                    if (nextPhoto != null) {
                        PhotoCardView(
                            photoItem = nextPhoto,
                            libRawBridge = libRawBridge,
                            modifier = Modifier
                                .fillMaxSize(0.95f)
                                .graphicsLayer {
                                    scaleX = 0.96f
                                    scaleY = 0.96f
                                    alpha = 0.5f
                                }
                        )
                    }

                    val rotationZ = if (zoomScale <= 1.05f) (offsetX / 25f).coerceIn(-15f, 15f) else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                            .graphicsLayer {
                                this.rotationZ = rotationZ
                            }
                            .pointerInput(currentPhoto.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                                    if (zoomScale <= 1.05f) {
                                        // 1-finger swipe drag mode
                                        offsetX += pan.x
                                        offsetY += pan.y

                                        // Swipe triggers
                                        when {
                                            offsetX > thresholdX -> {
                                                onSwipeAccept(currentPhoto)
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                            offsetX < -thresholdX -> {
                                                onSwipeReject(currentPhoto)
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                            offsetY < -thresholdY -> {
                                                onSwipeUndo()
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                            offsetY > thresholdY -> {
                                                onSwipeSkip(currentPhoto)
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        PhotoCardView(
                            photoItem = currentPhoto,
                            libRawBridge = libRawBridge,
                            scale = zoomScale
                        )

                        // Overlay Indicators
                        if (offsetX > 40f && zoomScale <= 1.05f) {
                            val alphaValue = (offsetX / thresholdX).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(28.dp)
                                    .rotate(-10f)
                                    .alpha(alphaValue)
                            ) {
                                Text(
                                    text = "ACCEPT",
                                    color = AcceptGreen,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                        }

                        if (offsetX < -40f && zoomScale <= 1.05f) {
                            val alphaValue = (-offsetX / thresholdX).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(28.dp)
                                    .rotate(10f)
                                    .alpha(alphaValue)
                            ) {
                                Text(
                                    text = "REJECT",
                                    color = RejectRed,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                        }

                        if (offsetY < -40f && zoomScale <= 1.05f && kotlin.math.abs(offsetY) > kotlin.math.abs(offsetX)) {
                            val alphaValue = (-offsetY / thresholdY).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 60.dp)
                                    .alpha(alphaValue)
                            ) {
                                Text(
                                    text = "PREVIOUS",
                                    color = UndoPurple,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                        }

                        if (offsetY > 40f && zoomScale <= 1.05f && kotlin.math.abs(offsetY) > kotlin.math.abs(offsetX)) {
                            val alphaValue = (offsetY / thresholdY).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 60.dp)
                                    .alpha(alphaValue)
                            ) {
                                Text(
                                    text = "SKIP",
                                    color = SkipYellow,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.9f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurface)
                            .border(1.dp, BorderColor, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AcceptGreen,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "選別が完了しました！",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onFinishSelection,
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon)
                            ) {
                                Text(text = "結果を確認する", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
