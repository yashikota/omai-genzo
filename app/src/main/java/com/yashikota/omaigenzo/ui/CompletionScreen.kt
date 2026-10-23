package com.yashikota.omaigenzo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.SessionSummary
import com.yashikota.omaigenzo.ui.components.ButtonContent
import com.yashikota.omaigenzo.ui.components.SectionLabel
import com.yashikota.omaigenzo.ui.components.label
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.Spacing
import com.yashikota.omaigenzo.ui.theme.tabular

@Composable
fun CompletionScreen(
    photos: List<PhotoItem>,
    onResetClick: () -> Unit = {},
    onRestartSelection: () -> Unit = onResetClick,
    onGalleryClick: () -> Unit = {},
    onOpenGallery: () -> Unit = onGalleryClick,
    onExportClick: () -> Unit = {},
    onExportAcceptPhotos: () -> Unit = onExportClick,
    onResumePending: () -> Unit = {},
    isExporting: Boolean = false,
    onShareKept: () -> Unit = {},
) {
    val summary = remember(photos) { SessionSummary.of(photos) }
    val totalCount = summary.totalCount
    val acceptCount = summary.acceptCount
    val rejectCount = summary.rejectCount
    val pendingCount = summary.pendingCount
    val complete = pendingCount == 0
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = Spacing.contentMaxWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val heroColor = if (complete) OmaiTheme.colors.keep else OmaiTheme.colors.pending
                    val heroContainer = if (complete) OmaiTheme.colors.keepContainer else OmaiTheme.colors.pendingContainer
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(heroContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (complete) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = heroColor,
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        text = stringResource(if (complete) R.string.done_title_complete else R.string.done_title_partial),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = if (complete) {
                            stringResource(R.string.done_body_complete, totalCount)
                        } else {
                            stringResource(R.string.done_body_partial, pendingCount)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(Spacing.xxl))
                    Breakdown(
                        total = totalCount,
                        keep = acceptCount,
                        reject = rejectCount,
                        pending = pendingCount,
                    )

                    Spacer(Modifier.height(Spacing.xxl))

                    val exportText = when {
                        isExporting -> stringResource(R.string.done_exporting)
                        acceptCount == 0 -> stringResource(R.string.done_export_none)
                        else -> stringResource(R.string.done_export, acceptCount)
                    }
                    if (!complete) {
                        Button(
                            onClick = onResumePending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            ButtonContent(stringResource(R.string.done_review_pending, pendingCount), Icons.Rounded.Schedule)
                        }
                        Spacer(Modifier.height(Spacing.md))
                        FilledTonalButton(
                            onClick = { if (!isExporting) onExportAcceptPhotos() },
                            enabled = acceptCount > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            ButtonContent(exportText, Icons.Rounded.Download, loading = isExporting)
                        }
                    } else {
                        Button(
                            onClick = { if (!isExporting) onExportAcceptPhotos() },
                            enabled = acceptCount > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            ButtonContent(exportText, Icons.Rounded.Download, loading = isExporting)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedButton(
                        onClick = onOpenGallery,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        ButtonContent(stringResource(R.string.done_open_gallery), Icons.Rounded.GridView)
                    }
                    if (acceptCount > 0) {
                        Spacer(Modifier.height(Spacing.sm))
                        TextButton(onClick = onShareKept) {
                            ButtonContent(stringResource(R.string.share_kept_list), Icons.Rounded.Share)
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                    TextButton(onClick = { confirmReset = true }) {
                        ButtonContent(stringResource(R.string.done_new_folder), Icons.Rounded.FolderOpen)
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
            title = { Text(stringResource(R.string.done_reset_title)) },
            text = { Text(stringResource(R.string.done_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onRestartSelection()
                    },
                ) { Text(stringResource(R.string.done_reset_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Breakdown(total: Int, keep: Int, reject: Int, pending: Int) {
    val colors = OmaiTheme.colors
    // The bar fills in once on arrival; afterwards it is static.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.Bottom) {
                SectionLabel(stringResource(R.string.done_breakdown), Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.count_photos, total),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clearAndSetSemantics {},
            ) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = radius)
                if (total == 0) return@Canvas
                val gap = 2.dp.toPx()
                var x = 0f
                listOf(keep to colors.keep, reject to colors.reject, pending to colors.pending).forEach { (count, color) ->
                    if (count == 0) return@forEach
                    val w = size.width * count / total * reveal.value
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size((w - gap).coerceAtLeast(size.height), size.height),
                        cornerRadius = radius,
                    )
                    x += w
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BreakdownStat(SelectionState.ACCEPT, keep, total, Modifier.weight(1f))
                BreakdownStat(SelectionState.REJECT, reject, total, Modifier.weight(1f))
                BreakdownStat(SelectionState.PENDING, pending, total, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BreakdownStat(state: SelectionState, count: Int, total: Int, modifier: Modifier = Modifier) {
    val label = state.label()
    val countText = stringResource(R.string.count_photos, count)
    val percent = stringResource(R.string.done_percent, PhotoFormatting.percent(count, total))
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md)
            .clearAndSetSemantics { contentDescription = "$label $countText $percent" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(OmaiTheme.colors.forState(state)),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = percent,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
