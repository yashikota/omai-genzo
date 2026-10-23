package com.yashikota.omaigenzo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import com.yashikota.omaigenzo.DecodePriority
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.data.PrefetchCoordinator
import com.yashikota.omaigenzo.ui.components.EmptyState
import com.yashikota.omaigenzo.ui.components.PhotoBadge
import com.yashikota.omaigenzo.ui.components.icon
import com.yashikota.omaigenzo.ui.components.label
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.Spacing
import com.yashikota.omaigenzo.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val ZOOM_ENGAGED = 1.05f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 4f

private enum class SwipeDirection { RIGHT, LEFT, UP, DOWN }

/**
 * Drag, pinch and double-tap state for the top card. Only graphicsLayer / draw lambdas read the
 * offsets, so a drag frame never recomposes the screen.
 */
@Stable
private class SwipeCardState {
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    private var settleJob: Job? = null

    val zoomed: Boolean get() = zoom > ZOOM_ENGAGED

    fun reset() {
        settleJob?.cancel()
        offsetX = 0f
        offsetY = 0f
        zoom = 1f
        pan = Offset.Zero
    }

    fun cancelSettle() {
        settleJob?.cancel()
    }

    /** Springs the card back to rest when a drag ends short of a threshold. */
    fun settle(scope: CoroutineScope) {
        val startX = offsetX
        val startY = offsetY
        val startZoom = zoom
        val startPan = pan
        val resetZoom = startZoom <= ZOOM_ENGAGED
        if (startX == 0f && startY == 0f && (!resetZoom || startZoom == 1f)) return
        settleJob = scope.launch {
            animate(1f, 0f, animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { f, _ ->
                offsetX = startX * f
                offsetY = startY * f
                if (resetZoom) {
                    zoom = 1f + (startZoom - 1f) * f.coerceAtLeast(0f)
                    pan = startPan * f.coerceAtLeast(0f)
                }
            }
        }
    }

    /** Zooms in around the tapped point, or back out to fit. */
    fun toggleZoom(scope: CoroutineScope, tap: Offset, center: Offset) {
        settleJob?.cancel()
        val fromZoom = zoom
        val fromPan = pan
        val toZoom = if (zoomed) 1f else DOUBLE_TAP_ZOOM
        // graphicsLayer scales around the centre: keep the tapped point under the finger.
        val toPan = if (toZoom == 1f) Offset.Zero else (center - tap) * (toZoom - 1f)
        settleJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) { f, _ ->
                zoom = fromZoom + (toZoom - fromZoom) * f
                pan = fromPan + (toPan - fromPan) * f
            }
        }
    }

    /** 0 at rest, 1 at the commit threshold. */
    fun dragProgress(thresholdX: Float, thresholdY: Float): Float = if (zoomed) {
        0f
    } else {
        max(abs(offsetX) / thresholdX, abs(offsetY) / thresholdY).coerceIn(0f, 1f)
    }
}

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
    val cardState = remember { SwipeCardState() }
    var reversedDirections by rememberSaveable { mutableStateOf(false) }

    val currentPhoto = photos.getOrNull(currentIndex)

    // Recomputed when the list changes (once per swipe), never per drag frame.
    val counts = remember(photos) { SelectionCounts.of(photos) }
    val totalCount = photos.size

    LaunchedEffect(currentIndex, photos.size) {
        if (photos.isNotEmpty() && currentIndex >= photos.size) {
            onFinishSelection()
        }
    }

    // Reset offsets when photo changes
    LaunchedEffect(currentIndex) {
        cardState.reset()
    }

    // Keyed on the index and list size only: `photos` is a new list after every selection, and
    // restarting prefetch for that would cancel the very work the next card is waiting for.
    val latestPhotos by rememberUpdatedState(photos)
    val prefetch = remember(libRawBridge) { PrefetchCoordinator(Dispatchers.Default, libRawBridge.maxPrefetchEntries()) }
    DisposableEffect(prefetch) {
        prefetch.onPrefetchRequested = { request ->
            latestPhotos.getOrNull(request.index)?.let { photo ->
                libRawBridge.loadPhotoBitmap(
                    filePath = photo.fastDisplayPath,
                    isRaw = photo.shouldUseRawRenderer(),
                    fastMode = true,
                    targetMaxDimension = LibRawBridge.DEFAULT_PREVIEW_TARGET,
                    cacheVersion = photo.modifiedAt,
                    priority = DecodePriority.PREFETCH,
                )
            }
        }
        onDispose {
            prefetch.cancelAll()
            prefetch.onPrefetchRequested = null
        }
    }
    LaunchedEffect(currentIndex, photos.size) {
        prefetch.updateCurrentIndex(currentIndex, photos.size)
    }

    val decide: (SwipeDirection, PhotoItem) -> Unit = { direction, photo ->
        when (direction) {
            SwipeDirection.RIGHT -> if (reversedDirections) onSwipeReject(photo) else onSwipeAccept(photo)
            SwipeDirection.LEFT -> if (reversedDirections) onSwipeAccept(photo) else onSwipeReject(photo)
            SwipeDirection.UP -> onSwipeUndo()
            SwipeDirection.DOWN -> onSwipeSkip(photo)
        }
    }
    val rightState = if (reversedDirections) SelectionState.REJECT else SelectionState.ACCEPT
    val leftState = if (reversedDirections) SelectionState.ACCEPT else SelectionState.REJECT

    Scaffold(
        topBar = {
            SwipeTopBar(
                photo = currentPhoto,
                position = (currentIndex + 1).coerceAtMost(totalCount),
                total = totalCount,
                reversedDirections = reversedDirections,
                onToggleDirections = { reversedDirections = !reversedDirections },
                onOpenGallery = onOpenGallery,
                onFinishSelection = onFinishSelection,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            if (currentPhoto == null) {
                AllSeen(onFinishSelection)
                return@BoxWithConstraints
            }
            val stage: @Composable (Modifier) -> Unit = { modifier ->
                CardStage(
                    photos = photos,
                    currentIndex = currentIndex,
                    currentPhoto = currentPhoto,
                    libRawBridge = libRawBridge,
                    cardState = cardState,
                    rightState = rightState,
                    leftState = leftState,
                    onDecision = { decide(it, currentPhoto) },
                    modifier = modifier,
                )
            }
            val actions: @Composable (Boolean) -> Unit = { vertical ->
                ActionBar(
                    leftState = leftState,
                    rightState = rightState,
                    vertical = vertical,
                    onUndo = onSwipeUndo,
                    onLeft = { decide(SwipeDirection.LEFT, currentPhoto) },
                    onRight = { decide(SwipeDirection.RIGHT, currentPhoto) },
                    onPending = { onSwipeSkip(currentPhoto) },
                )
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(start = Spacing.md, end = Spacing.sm, bottom = Spacing.sm)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        ProgressHeader(currentIndex, totalCount, counts, reversedDirections, compact = true)
                        stage(Modifier.weight(1f))
                    }
                    Box(Modifier.fillMaxHeight().widthIn(min = 96.dp), contentAlignment = Alignment.Center) {
                        actions(true)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    ProgressHeader(currentIndex, totalCount, counts, reversedDirections)
                    stage(Modifier.weight(1f).padding(horizontal = Spacing.md))
                    AnimatedVisibility(visible = currentIndex == 0 && counts.decided == 0) {
                        Text(
                            text = stringResource(R.string.swipe_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.md),
                            textAlign = TextAlign.Center,
                        )
                    }
                    actions(false)
                }
            }
        }
    }
}

/** Counts per state, computed once per selection change. */
private data class SelectionCounts(val keep: Int, val reject: Int, val pending: Int) {
    val decided: Int get() = keep + reject

    companion object {
        fun of(photos: List<PhotoItem>): SelectionCounts {
            var keep = 0
            var reject = 0
            var pending = 0
            for (photo in photos) {
                when (photo.selectionState) {
                    SelectionState.ACCEPT -> keep++
                    SelectionState.REJECT -> reject++
                    SelectionState.PENDING -> pending++
                }
            }
            return SelectionCounts(keep, reject, pending)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTopBar(
    photo: PhotoItem?,
    position: Int,
    total: Int,
    reversedDirections: Boolean,
    onToggleDirections: () -> Unit,
    onOpenGallery: () -> Unit,
    onFinishSelection: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dateText = remember(photo?.modifiedAt) {
        photo?.modifiedAt?.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
    }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = photo?.displayFileName ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (total > 0) {
                    val positionText = stringResource(R.string.swipe_position, position, total)
                    Text(
                        text = if (dateText != null) "$positionText · $dateText" else positionText,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenGallery) {
                Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.swipe_open_gallery))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.swipe_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.swipe_swap_directions)) },
                        leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                        trailingIcon = {
                            if (reversedDirections) Icon(Icons.Rounded.Check, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onToggleDirections()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.swipe_finish)) },
                        leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onFinishSelection()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun ProgressHeader(
    currentIndex: Int,
    total: Int,
    counts: SelectionCounts,
    reversedDirections: Boolean,
    compact: Boolean = false,
) {
    val target = if (total > 0) currentIndex.toFloat() / total else 0f
    val progress by animateFloatAsState(target, animationSpec = tween(durationMillis = 180), label = "swipe-progress")
    val description = stringResource(
        R.string.swipe_progress_description,
        total,
        (currentIndex + 1).coerceAtMost(total),
        counts.keep,
        counts.reject,
        counts.pending,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.md)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val bar: @Composable (Modifier) -> Unit = { modifier ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = modifier.height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        // Landscape has little height to spare, so the bar and the counts share one line.
        if (!compact) {
            bar(Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.sm))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountLabel(SelectionState.ACCEPT, counts.keep)
            CountLabel(SelectionState.REJECT, counts.reject)
            CountLabel(SelectionState.PENDING, counts.pending)
            if (compact) bar(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            if (reversedDirections) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        tint = OmaiTheme.colors.pending,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.swipe_swap_directions_on),
                        style = MaterialTheme.typography.labelSmall,
                        color = OmaiTheme.colors.pending,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountLabel(state: SelectionState, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(OmaiTheme.colors.forState(state)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = state.label(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CardStage(
    photos: List<PhotoItem>,
    currentIndex: Int,
    currentPhoto: PhotoItem,
    libRawBridge: LibRawBridge,
    cardState: SwipeCardState,
    rightState: SelectionState,
    leftState: SelectionState,
    onDecision: (SwipeDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thresholdX = with(density) { 120.dp.toPx() }
    val thresholdY = with(density) { 110.dp.toPx() }
    val labelStart = with(density) { 16.dp.toPx() }
    val cornerRadius = with(density) { 20.dp.toPx() }
    val strokeWidth = with(density) { 3.dp.toPx() }
    val scope = rememberCoroutineScope()
    val colors = OmaiTheme.colors
    val zoomed by remember { derivedStateOf { cardState.zoomed } }

    val photoDescription = stringResource(R.string.swipe_photo_description, currentPhoto.displayFileName)
    val rightLabel = rightState.label()
    val leftLabel = leftState.label()
    val pendingLabel = SelectionState.PENDING.label()
    val undoLabel = stringResource(R.string.action_undo)
    val latestDecision by rememberUpdatedState(onDecision)

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // The next photo waits underneath and grows into place as the top card is dragged away.
        photos.getOrNull(currentIndex + 1)?.let { nextPhoto ->
            PhotoCardView(
                photoItem = nextPhoto,
                libRawBridge = libRawBridge,
                priority = DecodePriority.PREFETCH,
                showExifOverlay = false,
                modifier = Modifier.graphicsLayer {
                    val p = cardState.dragProgress(thresholdX, thresholdY)
                    val s = 0.92f + 0.08f * p
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f + 0.65f * p
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(cardState.offsetX.roundToInt(), cardState.offsetY.roundToInt()) }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationZ = if (cardState.zoomed) 0f else (cardState.offsetX / 28f).coerceIn(-12f, 12f)
                }
                .semantics {
                    contentDescription = photoDescription
                    customActions = listOf(
                        CustomAccessibilityAction(rightLabel) {
                            latestDecision(SwipeDirection.RIGHT)
                            true
                        },
                        CustomAccessibilityAction(leftLabel) {
                            latestDecision(SwipeDirection.LEFT)
                            true
                        },
                        CustomAccessibilityAction(pendingLabel) {
                            latestDecision(SwipeDirection.DOWN)
                            true
                        },
                        CustomAccessibilityAction(undoLabel) {
                            latestDecision(SwipeDirection.UP)
                            true
                        },
                    )
                }
                .pointerInput(currentPhoto.id) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            cardState.toggleZoom(scope, tap, Offset(size.width / 2f, size.height / 2f))
                        },
                    )
                }
                .pointerInput(currentPhoto.id) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        cardState.cancelSettle()
                        var committed = false
                        var pastSlop = false
                        var slopZoom = 1f
                        var slopPan = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            if (!committed) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!pastSlop) {
                                    slopZoom *= zoomChange
                                    slopPan += panChange
                                    val zoomMotion = abs(1f - slopZoom) * event.calculateCentroidSize(useCurrent = false)
                                    pastSlop = zoomMotion > slop || slopPan.getDistance() > slop
                                }
                                if (pastSlop) {
                                    cardState.zoom = (cardState.zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                    if (!cardState.zoomed) {
                                        cardState.offsetX += panChange.x
                                        cardState.offsetY += panChange.y
                                        val direction = when {
                                            cardState.offsetX > thresholdX -> SwipeDirection.RIGHT
                                            cardState.offsetX < -thresholdX -> SwipeDirection.LEFT
                                            cardState.offsetY < -thresholdY -> SwipeDirection.UP
                                            cardState.offsetY > thresholdY -> SwipeDirection.DOWN
                                            else -> null
                                        }
                                        if (direction != null) {
                                            committed = true
                                            cardState.offsetX = 0f
                                            cardState.offsetY = 0f
                                            latestDecision(direction)
                                        }
                                    } else {
                                        cardState.pan += panChange
                                    }
                                    event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.fastAny { it.pressed })
                        if (!committed) cardState.settle(scope)
                    }
                }
                .drawWithContent {
                    drawContent()
                    // Tint the card edge with the colour of the decision it is heading towards.
                    if (cardState.zoomed) return@drawWithContent
                    val x = cardState.offsetX
                    val y = cardState.offsetY
                    val horizontal = abs(x) >= abs(y)
                    val (tint, amount) = when {
                        horizontal && x > 0f -> colors.forState(rightState) to x / thresholdX
                        horizontal && x < 0f -> colors.forState(leftState) to -x / thresholdX
                        !horizontal && y > 0f -> colors.pending to y / thresholdY
                        !horizontal && y < 0f -> colors.undo to -y / thresholdY
                        else -> Color.Transparent to 0f
                    }
                    val a = amount.coerceIn(0f, 1f)
                    if (a > 0f) {
                        drawRoundRect(tint.copy(alpha = 0.16f * a), cornerRadius = CornerRadius(cornerRadius))
                        drawRoundRect(
                            color = tint.copy(alpha = a),
                            cornerRadius = CornerRadius(cornerRadius),
                            style = Stroke(width = strokeWidth),
                        )
                    }
                },
        ) {
            PhotoCardView(
                photoItem = currentPhoto,
                libRawBridge = libRawBridge,
                scale = { cardState.zoom },
                panX = { cardState.pan.x },
                panY = { cardState.pan.y },
            )

            PhotoBadge(
                text = PhotoFormatting.formatBadge(currentPhoto),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.md)
                    .graphicsLayer { alpha = 1f - cardState.dragProgress(thresholdX, thresholdY) },
            )

            // Overlay stamps stay composed and only change alpha inside graphicsLayer,
            // so dragging does not recompose the screen.
            SwipeStamp(
                text = rightLabel,
                icon = rightState.icon(),
                color = colors.forState(rightState),
                rotation = -12f,
                modifier = Modifier.align(Alignment.TopStart).padding(Spacing.xl),
                alpha = {
                    val x = cardState.offsetX
                    if (x > labelStart && !cardState.zoomed) (x / thresholdX).coerceIn(0f, 1f) else 0f
                },
            )
            SwipeStamp(
                text = leftLabel,
                icon = leftState.icon(),
                color = colors.forState(leftState),
                rotation = 12f,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xl),
                alpha = {
                    val x = cardState.offsetX
                    if (x < -labelStart && !cardState.zoomed) (-x / thresholdX).coerceIn(0f, 1f) else 0f
                },
            )
            SwipeStamp(
                text = undoLabel,
                icon = Icons.AutoMirrored.Rounded.Undo,
                color = colors.undo,
                rotation = 0f,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                alpha = {
                    val y = cardState.offsetY
                    if (y < -labelStart && !cardState.zoomed && abs(y) > abs(cardState.offsetX)) {
                        (-y / thresholdY).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
            )
            SwipeStamp(
                text = pendingLabel,
                icon = SelectionState.PENDING.icon(),
                color = colors.pending,
                rotation = 0f,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                alpha = {
                    val y = cardState.offsetY
                    if (y > labelStart && !cardState.zoomed && abs(y) > abs(cardState.offsetX)) {
                        (y / thresholdY).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = zoomed,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = Spacing.md),
        ) {
            ZoomChip(zoom = { cardState.zoom }, onClick = { cardState.toggleZoom(scope, Offset.Zero, Offset.Zero) })
        }
    }
}

@Composable
private fun ZoomChip(zoom: () -> Float, onClick: () -> Unit) {
    // Rounded to one decimal so a pinch recomposes this chip only when the label actually changes.
    val label by remember { derivedStateOf { ((zoom() * 10f).roundToInt() / 10f).toString() } }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm - 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ZoomOutMap, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.swipe_zoom_reset, label), style = MaterialTheme.typography.labelMedium.tabular())
        }
    }
}

@Composable
private fun SwipeStamp(
    text: String,
    icon: ImageVector,
    color: Color,
    rotation: Float,
    modifier: Modifier,
    alpha: () -> Float,
) {
    Row(
        modifier = modifier
            .graphicsLayer {
                val a = alpha()
                this.alpha = a
                rotationZ = rotation
                val s = 0.85f + 0.15f * a
                scaleX = s
                scaleY = s
            }
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(BorderStroke(2.5.dp, color), MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

@Composable
private fun ActionBar(
    leftState: SelectionState,
    rightState: SelectionState,
    vertical: Boolean,
    onUndo: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onPending: () -> Unit,
) {
    val colors = OmaiTheme.colors
    val buttons: List<@Composable () -> Unit> = listOf(
        {
            ActionButton(
                icon = Icons.AutoMirrored.Rounded.Undo,
                label = stringResource(R.string.action_undo),
                tint = colors.undo,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 48.dp,
                onClick = onUndo,
            )
        },
        {
            ActionButton(
                icon = leftState.icon(),
                label = leftState.label(),
                tint = colors.forState(leftState),
                container = colors.containerForState(leftState),
                size = 64.dp,
                onClick = onLeft,
            )
        },
        {
            ActionButton(
                icon = rightState.icon(),
                label = rightState.label(),
                tint = colors.forState(rightState),
                container = colors.containerForState(rightState),
                size = 64.dp,
                onClick = onRight,
            )
        },
        {
            ActionButton(
                icon = SelectionState.PENDING.icon(),
                label = SelectionState.PENDING.label(),
                tint = colors.pending,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 48.dp,
                onClick = onPending,
            )
        },
    )
    if (vertical) {
        // Reading order top-to-bottom mirrors the portrait bar's left-to-right, with the big
        // decisions in the middle where the thumb rests.
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            buttons.forEach { it() }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            buttons.forEach { it() }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    container: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = tint,
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.46f))
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun AllSeen(onFinishSelection: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Rounded.CheckCircle,
            title = stringResource(R.string.swipe_done_title),
            body = stringResource(R.string.swipe_done_body),
            tint = OmaiTheme.colors.keep,
            action = {
                Button(onClick = onFinishSelection) { Text(stringResource(R.string.swipe_done_cta)) }
            },
        )
    }
}
