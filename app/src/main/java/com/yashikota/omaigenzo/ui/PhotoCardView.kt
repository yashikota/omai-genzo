package com.yashikota.omaigenzo.ui

import android.os.SystemClock
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
import com.yashikota.omaigenzo.DecodePriority
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.ui.theme.*

private const val GALLERY_THUMBNAIL_TARGET = 512

@Composable
fun PhotoCardView(
    photoItem: PhotoItem,
    libRawBridge: LibRawBridge,
    modifier: Modifier = Modifier,
    // Providers, not values: they are read inside graphicsLayer, so pinch/pan never recomposes the card.
    scale: () -> Float = { 1f },
    panX: () -> Float = { 0f },
    panY: () -> Float = { 0f },
    showExifOverlay: Boolean = true,
    targetMaxDimension: Int = 2048,
    priority: DecodePriority = DecodePriority.VISIBLE,
) {
    // A cache hit is resolved while composing, so the photo paints in the very same frame instead
    // of showing a spinner first and swapping a frame later.
    val cached = remember(photoItem.id, photoItem.modifiedAt, targetMaxDimension) {
        libRawBridge.peekCached(
            filePath = photoItem.fastDisplayPath,
            isRaw = photoItem.shouldUseRawRenderer(),
            targetMaxDimension = targetMaxDimension,
            cacheVersion = photoItem.modifiedAt,
        )
    }
    var bitmap by remember(photoItem.id, photoItem.modifiedAt, targetMaxDimension) { mutableStateOf(cached) }
    var isLoading by remember(photoItem.id, photoItem.modifiedAt, targetMaxDimension) { mutableStateOf(cached == null) }

    // While the full preview decodes, show the gallery thumbnail if one is already cached.
    val placeholder = remember(photoItem.id, photoItem.modifiedAt, targetMaxDimension) {
        if (cached != null || PreviewBucket.forTarget(targetMaxDimension) == PreviewBucket.THUMBNAIL) {
            null
        } else {
            libRawBridge.peekCached(
                filePath = photoItem.fastDisplayPath,
                isRaw = photoItem.shouldUseRawRenderer(),
                targetMaxDimension = GALLERY_THUMBNAIL_TARGET,
                cacheVersion = photoItem.modifiedAt,
                record = false,
            )
        }
    }

    LaunchedEffect(photoItem.id, photoItem.modifiedAt, targetMaxDimension) {
        if (bitmap != null) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtimeNanos()
        PerfLogger.event(
            "photo_visible_request",
            "\"id\":\"${PerfLogger.escape(photoItem.id)}\",\"source\":\"${PerfLogger.escape(photoItem.fastDisplayPath)}\"," +
                "\"target\":$targetMaxDimension,\"priority\":\"$priority\"",
        )
        val loadedBitmap = libRawBridge.loadPhotoBitmap(
            filePath = photoItem.fastDisplayPath,
            isRaw = photoItem.shouldUseRawRenderer(),
            fastMode = true,
            targetMaxDimension = targetMaxDimension,
            cacheVersion = photoItem.modifiedAt,
            priority = priority,
        )
        bitmap = loadedBitmap
        isLoading = false
        PerfLogger.event(
            "photo_visible_result",
            "\"id\":\"${PerfLogger.escape(photoItem.id)}\",\"success\":${loadedBitmap != null}," +
                "\"width\":${loadedBitmap?.width ?: 0},\"height\":${loadedBitmap?.height ?: 0}," +
                "\"bytes\":${loadedBitmap?.allocationByteCount ?: 0},\"duration_ns\":${SystemClock.elapsedRealtimeNanos() - startedAt}",
        )
    }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    val showExif by remember { derivedStateOf { scale() <= 1.05f } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp)),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val zoom = scale()
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX()
                        translationY = panY()
                    },
            )
        } else if (isLoading) {
            if (placeholder != null) {
                Image(
                    bitmap = remember(placeholder) { placeholder.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PrimaryNeon, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (photoItem.isRawFile()) "LibRaw 現像中..." else "画像をロード中...",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "画像を読み込めませんでした",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // Bottom EXIF Info Overlay
        if (showExifOverlay && photoItem.exifInfo.make.isNotEmpty() && showExif) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = PrimaryNeon,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${photoItem.exifInfo.make} ${photoItem.exifInfo.model}".trim(),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "$label ", color = TextSecondary, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
