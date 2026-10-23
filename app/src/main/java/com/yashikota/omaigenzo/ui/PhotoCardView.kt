package com.yashikota.omaigenzo.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.DecodePriority
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.data.PerfLogger
import com.yashikota.omaigenzo.data.PreviewBucket
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.OnPhoto
import com.yashikota.omaigenzo.ui.theme.OnPhotoMuted
import com.yashikota.omaigenzo.ui.theme.Spacing

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
            .clip(MaterialTheme.shapes.large)
            .background(OmaiTheme.colors.photoCanvas),
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
                PhotoLoading(isRaw = photoItem.shouldUseRawRenderer())
            }
        } else {
            PhotoFailed(fileName = photoItem.displayFileName)
        }

        if (showExifOverlay && showExif && (imageBitmap != null || placeholder != null)) {
            ExifOverlay(photoItem, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun PhotoLoading(isRaw: Boolean) {
    // Only the icon's alpha animates, in the draw phase, so a slow decode costs no recompositions.
    val pulse = rememberInfiniteTransition(label = "photo-loading")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Reverse),
        label = "photo-loading-alpha",
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer { this.alpha = alpha },
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(if (isRaw) R.string.photo_loading_raw else R.string.photo_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhotoFailed(fileName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.photo_failed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExifOverlay(photoItem: PhotoItem, modifier: Modifier = Modifier) {
    val exif = photoItem.exifInfo
    val camera = PhotoFormatting.camera(exif)
    val exposure = PhotoFormatting.exposureParts(exif)
    if (camera.isEmpty() && exposure.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xxl, bottom = Spacing.md),
    ) {
        if (camera.isNotEmpty()) {
            Text(
                text = camera,
                style = MaterialTheme.typography.labelLarge,
                color = OnPhoto,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (exposure.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xxs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                exposure.forEach { part ->
                    Text(text = part, style = MaterialTheme.typography.labelMedium, color = OnPhotoMuted, maxLines = 1)
                }
            }
        }
    }
}
