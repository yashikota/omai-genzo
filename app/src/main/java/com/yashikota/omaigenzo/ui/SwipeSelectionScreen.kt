package com.yashikota.omaigenzo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.theme.*
import java.text.DateFormat
import java.util.Date
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
    onFinishSelection: () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var imagePan by remember { mutableStateOf(Offset.Zero) }
    var reversedDirections by rememberSaveable { mutableStateOf(false) }

    val density = LocalDensity.current
    val thresholdX = with(density) { 120.dp.toPx() }
    val thresholdY = with(density) { 110.dp.toPx() }

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
        imagePan = Offset.Zero
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
                            fontFamily = FontFamily.SansSerif,
                        )
                        Text(
                            text = if (totalCount > 0) "${currentIndex + 1} / $totalCount" else "",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reversedDirections = !reversedDirections }) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = if (reversedDirections) "左右判定を標準に戻す" else "左右判定を入れ替える",
                            tint = if (reversedDirections) SkipYellow else TextSecondary,
                        )
                    }
                    IconButton(onClick = onOpenGallery) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "ギャラリー表示",
                            tint = PrimaryNeon,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                ),
            )
        },
        containerColor = DarkBackground,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Progress Dashboard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AcceptGreen),
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
                            .background(RejectRed),
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
                contentAlignment = Alignment.Center,
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
                                },
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
                                                if (reversedDirections) onSwipeReject(currentPhoto) else onSwipeAccept(currentPhoto)
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                            offsetX < -thresholdX -> {
                                                if (reversedDirections) onSwipeAccept(currentPhoto) else onSwipeReject(currentPhoto)
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
                                    } else {
                                        imagePan += pan
                                    }
                                }
                            },
                    ) {
                        if (currentPhoto.shouldUseRawRenderer()) {
                            FastRawGpuViewer(
                                photo = currentPhoto,
                                zoomScale = zoomScale,
                                panX = imagePan.x / 1000f,
                                panY = imagePan.y / 1000f,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            PhotoCardView(
                                photoItem = currentPhoto,
                                libRawBridge = libRawBridge,
                                scale = zoomScale,
                                panX = imagePan.x,
                                panY = imagePan.y,
                            )
                        }

                        // Overlay Indicators
                        if (offsetX > 40f && zoomScale <= 1.05f) {
                            val alphaValue = (offsetX / thresholdX).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(28.dp)
                                    .rotate(-10f)
                                    .alpha(alphaValue),
                            ) {
                                Text(
                                    text = "キープ",
                                    color = AcceptGreen,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    letterSpacing = 2.sp,
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
                                    .alpha(alphaValue),
                            ) {
                                Text(
                                    text = "破棄",
                                    color = RejectRed,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }

                        if (offsetY < -40f && zoomScale <= 1.05f && kotlin.math.abs(offsetY) > kotlin.math.abs(offsetX)) {
                            val alphaValue = (-offsetY / thresholdY).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 60.dp)
                                    .alpha(alphaValue),
                            ) {
                                Text(
                                    text = "戻る",
                                    color = UndoPurple,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }

                        if (offsetY > 40f && zoomScale <= 1.05f && kotlin.math.abs(offsetY) > kotlin.math.abs(offsetX)) {
                            val alphaValue = (offsetY / thresholdY).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 60.dp)
                                    .alpha(alphaValue),
                            ) {
                                Text(
                                    text = "保留",
                                    color = SkipYellow,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(currentPhoto.displayFileName, color = TextPrimary, fontSize = 12.sp)
                        if (currentPhoto.modifiedAt > 0L) {
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(currentPhoto.modifiedAt)),
                                color = TextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            if (reversedDirections) "← キープ ・ 破棄 →" else "← 破棄 ・ キープ →",
                            color = TextSecondary,
                            fontSize = 10.sp,
                        )
                    }

                    if (zoomScale > 1.05f) {
                        TextButton(
                            onClick = {
                                zoomScale = 1f
                                imagePan = Offset.Zero
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        ) { Text("100%に戻す", color = PrimaryNeon) }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.9f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurface)
                            .border(1.dp, BorderColor, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AcceptGreen,
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "選別が完了しました！",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onFinishSelection,
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                            ) {
                                Text(text = "結果を確認する", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (currentPhoto != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    FilledTonalIconButton(onClick = onSwipeUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "前の操作に戻る", tint = UndoPurple)
                    }
                    FilledTonalButton(onClick = { onSwipeReject(currentPhoto) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("破棄")
                    }
                    FilledTonalButton(onClick = { onSwipeAccept(currentPhoto) }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("キープ")
                    }
                    FilledTonalIconButton(onClick = { onSwipeSkip(currentPhoto) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "保留して次へ", tint = SkipYellow)
                    }
                }
            }
        }
    }
}
