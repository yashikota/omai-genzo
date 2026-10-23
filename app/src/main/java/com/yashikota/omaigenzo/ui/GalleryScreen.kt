package com.yashikota.omaigenzo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yashikota.omaigenzo.FilterCategory
import com.yashikota.omaigenzo.LibRawBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.components.ButtonContent
import com.yashikota.omaigenzo.ui.components.EmptyState
import com.yashikota.omaigenzo.ui.components.SelectionStateBadge
import com.yashikota.omaigenzo.ui.components.icon
import com.yashikota.omaigenzo.ui.components.label
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.Spacing
import com.yashikota.omaigenzo.ui.theme.tabular

private const val GALLERY_THUMBNAIL_TARGET = 512

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
    isExporting: Boolean = false,
    onSelectionChangedForMany: (Set<String>, SelectionState) -> Unit = { ids, state -> ids.forEach { onSelectionChanged(it, state) } },
    onRatePhoto: (String, Int) -> Unit = { _, _ -> },
    onStartSelectionAt: (String) -> Unit = {},
    onShareKept: () -> Unit = {},
) {
    var selectedFilter by rememberSaveable { mutableStateOf(FilterCategory.ALL) }
    // Long-press starts multi-select; while anything is selected, taps toggle instead of opening.
    var multiSelection by remember { mutableStateOf(emptySet<String>()) }
    val inSelectionMode = multiSelection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    BackHandler(enabled = inSelectionMode) { multiSelection = emptySet() }
    // The detail viewer pages through the list as it was when opened, so changing a photo's state
    // there does not yank it out from under the viewer.
    var detailIds by remember { mutableStateOf<List<String>?>(null) }
    var detailIndex by remember { mutableStateOf(0) }

    val counts = remember(photos) {
        val byState = photos.groupingBy { it.selectionState }.eachCount()
        mapOf(
            FilterCategory.ALL to photos.size,
            FilterCategory.ACCEPT to (byState[SelectionState.ACCEPT] ?: 0),
            FilterCategory.REJECT to (byState[SelectionState.REJECT] ?: 0),
            FilterCategory.PENDING to (byState[SelectionState.PENDING] ?: 0),
        )
    }
    val filteredPhotos = remember(photos, selectedFilter) {
        when (selectedFilter) {
            FilterCategory.ALL -> photos
            FilterCategory.ACCEPT -> photos.filter { it.selectionState == SelectionState.ACCEPT }
            FilterCategory.REJECT -> photos.filter { it.selectionState == SelectionState.REJECT }
            FilterCategory.PENDING -> photos.filter { it.selectionState == SelectionState.PENDING }
        }
    }
    val keepCount = counts[FilterCategory.ACCEPT] ?: 0

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                SelectionTopBar(
                    count = multiSelection.size,
                    onClear = { multiSelection = emptySet() },
                    onSelectAll = { multiSelection = filteredPhotos.mapTo(HashSet()) { it.id } },
                    onApply = { state ->
                        onSelectionChangedForMany(multiSelection, state)
                        multiSelection = emptySet()
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.count_photos, photos.size),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackToSwipe) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onShareKept, enabled = keepCount > 0) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share_kept))
                        }
                        FilledTonalButton(
                            onClick = { if (!isExporting) onExportAcceptPhotos() },
                            enabled = keepCount > 0,
                            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                            modifier = Modifier.padding(end = Spacing.sm),
                        ) {
                            ButtonContent(
                                text = stringResource(if (isExporting) R.string.gallery_exporting else R.string.gallery_export),
                                icon = Icons.Rounded.Download,
                                loading = isExporting,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterCategory.entries.forEach { category ->
                    FilterTab(
                        category = category,
                        count = counts[category] ?: 0,
                        selected = selectedFilter == category,
                        onClick = { selectedFilter = category },
                    )
                }
            }

            if (filteredPhotos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    contentAlignment = Alignment.Center,
                ) {
                    GalleryEmpty(selectedFilter)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        top = Spacing.sm,
                        bottom = Spacing.xl + innerPadding.calculateBottomPadding(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredPhotos, key = { it.id }) { photo ->
                        val isSelected = photo.id in multiSelection
                        GalleryItemCard(
                            photoItem = photo,
                            libRawBridge = libRawBridge,
                            isSelected = isSelected,
                            onClick = {
                                if (inSelectionMode) {
                                    multiSelection = if (isSelected) multiSelection - photo.id else multiSelection + photo.id
                                } else {
                                    detailIds = filteredPhotos.map { it.id }
                                    detailIndex = filteredPhotos.indexOf(photo).coerceAtLeast(0)
                                }
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                multiSelection = if (isSelected) multiSelection - photo.id else multiSelection + photo.id
                            },
                        )
                    }
                }
            }
        }

        val ids = detailIds
        if (ids != null) {
            val photo = photos.firstOrNull { it.id == ids.getOrNull(detailIndex) }
            if (photo == null) {
                LaunchedEffect(Unit) { detailIds = null }
            } else {
                PhotoDetailDialog(
                    photo = photo,
                    position = detailIndex + 1,
                    total = ids.size,
                    libRawBridge = libRawBridge,
                    onDismiss = { detailIds = null },
                    onPrevious = { detailIndex = (detailIndex - 1).coerceAtLeast(0) },
                    onNext = { detailIndex = (detailIndex + 1).coerceAtMost(ids.lastIndex) },
                    onStateChange = { onPhotoSelectionChanged(photo, it) },
                    onRatingChange = { onRatePhoto(photo.id, it) },
                    onStartSelection = {
                        detailIds = null
                        onStartSelectionAt(photo.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterTab(
    category: FilterCategory,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val state = category.toState()
    val dotColor = state?.let { OmaiTheme.colors.forState(it) }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = (state?.label() ?: stringResource(R.string.gallery_filter_all)) + " " + count,
                style = MaterialTheme.typography.labelLarge.tabular(),
            )
        },
        leadingIcon = if (dotColor != null) {
            {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        } else {
            null
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

private fun FilterCategory.toState(): SelectionState? = when (this) {
    FilterCategory.ALL -> null
    FilterCategory.ACCEPT -> SelectionState.ACCEPT
    FilterCategory.REJECT -> SelectionState.REJECT
    FilterCategory.PENDING -> SelectionState.PENDING
}

@Composable
private fun GalleryEmpty(filter: FilterCategory) {
    val (title, body) = when (filter) {
        FilterCategory.ALL -> R.string.gallery_empty_all_title to R.string.gallery_empty_all_body
        FilterCategory.ACCEPT -> R.string.gallery_empty_keep_title to R.string.gallery_empty_keep_body
        FilterCategory.REJECT -> R.string.gallery_empty_reject_title to R.string.gallery_empty_reject_body
        FilterCategory.PENDING -> R.string.gallery_empty_pending_title to R.string.gallery_empty_pending_body
    }
    val state = filter.toState()
    EmptyState(
        icon = state?.icon() ?: Icons.Rounded.PhotoLibrary,
        title = stringResource(title),
        body = stringResource(body),
        tint = state?.let { OmaiTheme.colors.forState(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onApply: (SelectionState) -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.gallery_selected_count, count),
                style = MaterialTheme.typography.titleMedium.tabular(),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.gallery_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.gallery_select_all))
            }
            for (state in listOf(SelectionState.ACCEPT, SelectionState.PENDING, SelectionState.REJECT)) {
                val description = stringResource(R.string.gallery_apply_to_selection, state.label())
                IconButton(onClick = { onApply(state) }) {
                    Icon(state.icon(), contentDescription = description, tint = OmaiTheme.colors.forState(state))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryItemCard(
    photoItem: PhotoItem,
    libRawBridge: LibRawBridge,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    var thumbnail by remember(photoItem.id, photoItem.modifiedAt) {
        mutableStateOf(
            libRawBridge.peekCached(
                filePath = photoItem.fastDisplayPath,
                isRaw = photoItem.shouldUseRawRenderer(),
                targetMaxDimension = GALLERY_THUMBNAIL_TARGET,
                cacheVersion = photoItem.modifiedAt,
            ),
        )
    }

    LaunchedEffect(photoItem.id, photoItem.modifiedAt) {
        if (thumbnail != null) return@LaunchedEffect
        thumbnail = libRawBridge.loadPhotoBitmap(
            filePath = photoItem.fastDisplayPath,
            isRaw = photoItem.shouldUseRawRenderer(),
            fastMode = true,
            targetMaxDimension = GALLERY_THUMBNAIL_TARGET,
            cacheVersion = photoItem.modifiedAt,
        )
    }

    val state = photoItem.selectionState
    val description = stringResource(R.string.photo_state_description, photoItem.displayFileName, state.label())
    val longPressLabel = stringResource(R.string.gallery_multi_select)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longPressLabel,
            )
            .semantics {
                contentDescription = description
                selected = isSelected
            },
    ) {
        val shown = thumbnail
        if (shown != null) {
            Image(
                bitmap = remember(shown) { shown.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // Rejected photos recede so the keepers stand out at a glance.
                    .graphicsLayer { alpha = if (state == SelectionState.REJECT) 0.42f else 1f },
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = OmaiTheme.colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp),
            )
        }

        // Undecided photos carry no badge, so decided ones are what the eye picks up.
        if (state != SelectionState.PENDING) {
            SelectionStateBadge(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }

        RatingBadge(
            rating = photoItem.rating,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )

        if (isSelected) {
            val accent = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(accent.copy(alpha = 0.24f))
                    .border(3.dp, accent, MaterialTheme.shapes.small),
            )
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetailDialog(
    photo: PhotoItem,
    position: Int,
    total: Int,
    libRawBridge: LibRawBridge,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStateChange: (SelectionState) -> Unit,
    onRatingChange: (Int) -> Unit,
    onStartSelection: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = photo.displayFileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.gallery_detail_position, position, total) +
                                " · " + PhotoFormatting.formatBadge(photo),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onStartSelection) {
                        Icon(Icons.Rounded.Swipe, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.gallery_start_here))
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                ) {
                    PhotoCardView(
                        photoItem = photo,
                        libRawBridge = libRawBridge,
                        showExifOverlay = true,
                        targetMaxDimension = 2048,
                    )
                    PagerButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        description = stringResource(R.string.gallery_previous),
                        enabled = position > 1,
                        onClick = onPrevious,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    PagerButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        description = stringResource(R.string.gallery_next),
                        enabled = position < total,
                        onClick = onNext,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                RatingBar(
                    rating = photo.rating,
                    onRatingChange = onRatingChange,
                    starSize = 28.dp,
                    inactiveColor = OmaiTheme.colors.textTertiary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = Spacing.sm),
                )

                val options = listOf(SelectionState.ACCEPT, SelectionState.PENDING, SelectionState.REJECT)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg, top = Spacing.sm),
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = photo.selectionState == option,
                            onClick = { onStateChange(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = OmaiTheme.colors.containerForState(option),
                                activeContentColor = OmaiTheme.colors.onContainerForState(option),
                                activeBorderColor = OmaiTheme.colors.forState(option),
                                inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                            icon = {
                                Icon(option.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text(option.label(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
        modifier = modifier
            .padding(Spacing.sm)
            .size(44.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
    }
}
