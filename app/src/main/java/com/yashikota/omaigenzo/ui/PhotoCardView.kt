package com.yashikota.omaigenzo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.ui.theme.*

@Composable
fun PhotoCardView(
    photoItem: PhotoItem,
    libRawBridge: LibRawBridge,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    showExifOverlay: Boolean = true
) {
    var bitmap by remember(photoItem.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(photoItem.id) { mutableStateOf(true) }

    LaunchedEffect(photoItem.id) {
        isLoading = true
        val loadedBitmap = libRawBridge.loadPhotoBitmap(
            filePath = photoItem.fastDisplayPath,
            isRaw = photoItem.rawPath != null && photoItem.jpgPath == null,
            fastMode = true
        )
        bitmap = loadedBitmap
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PrimaryNeon, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (photoItem.isRawFile()) "LibRaw 現像中..." else "画像をロード中...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "画像を読み込めませんでした",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Bottom EXIF Info Overlay
        if (showExifOverlay && photoItem.exifInfo.make.isNotEmpty() && scale <= 1.05f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = PrimaryNeon,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${photoItem.exifInfo.make} ${photoItem.exifInfo.model}".trim(),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (photoItem.exifInfo.iso > 0) {
                            ExifChip(label = "ISO", value = "${photoItem.exifInfo.iso.toInt()}")
                        }
                        if (photoItem.exifInfo.aperture > 0) {
                            ExifChip(label = "F", value = "f/${photoItem.exifInfo.aperture}")
                        }
                        if (photoItem.exifInfo.shutter > 0) {
                            val ssStr = if (photoItem.exifInfo.shutter < 1f) {
                                "1/${(1f / photoItem.exifInfo.shutter).toInt()}s"
                            } else {
                                "${photoItem.exifInfo.shutter}s"
                            }
                            ExifChip(label = "SS", value = ssStr)
                        }
                        if (photoItem.exifInfo.focal > 0) {
                            ExifChip(label = "Focal", value = "${photoItem.exifInfo.focal.toInt()}mm")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExifChip(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = "$label ", color = TextSecondary, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
