package com.yashikota.omaigenzo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.ImportProgress
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.ui.components.ButtonContent
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.Spacing

@Composable
fun FolderSelectScreen(
    onSelectFolderClick: () -> Unit,
    isImporting: Boolean = false,
    importProgress: ImportProgress = ImportProgress(),
    logPath: String = "",
) {
    val importing = isImporting || importProgress.isImporting
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            StartBottomBar(
                importing = importing,
                importMessage = importProgress.message,
                onSelectFolderClick = onSelectFolderClick,
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val wide = maxWidth >= 720.dp
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier.widthIn(max = 1040.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Hero(alignStart = true)
                            Spacer(Modifier.height(Spacing.xl))
                            Assurances()
                        }
                        Column(Modifier.weight(1f)) {
                            GestureGuide()
                            Diagnostics(logPath)
                        }
                    }
                } else {
                    Column(Modifier.widthIn(max = Spacing.contentMaxWidth)) {
                        Hero(alignStart = false)
                        Spacer(Modifier.height(Spacing.xxl))
                        GestureGuide()
                        Spacer(Modifier.height(Spacing.xl))
                        Assurances()
                        Diagnostics(logPath)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(alignStart: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_mark),
                contentDescription = null,
                modifier = Modifier.size(76.dp),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.start_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        )
    }
}

@Composable
private fun GestureGuide() {
    val colors = OmaiTheme.colors
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.start_gesture_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GestureTile(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    direction = stringResource(R.string.start_gesture_swipe_right),
                    label = stringResource(R.string.state_keep),
                    caption = stringResource(R.string.start_gesture_keep_caption),
                    tint = colors.keep,
                    container = colors.keepContainer,
                    modifier = Modifier.weight(1f),
                )
                GestureTile(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    direction = stringResource(R.string.start_gesture_swipe_left),
                    label = stringResource(R.string.state_reject),
                    caption = stringResource(R.string.start_gesture_reject_caption),
                    tint = colors.reject,
                    container = colors.rejectContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GestureTile(
                    icon = Icons.Rounded.ArrowDownward,
                    direction = stringResource(R.string.start_gesture_swipe_down),
                    label = stringResource(R.string.state_pending),
                    caption = stringResource(R.string.start_gesture_pending_caption),
                    tint = colors.pending,
                    container = colors.pendingContainer,
                    modifier = Modifier.weight(1f),
                )
                GestureTile(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    direction = stringResource(R.string.start_gesture_swipe_up),
                    label = stringResource(R.string.action_undo),
                    caption = stringResource(R.string.start_gesture_undo_caption),
                    tint = colors.undo,
                    container = colors.undoContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GestureTile(
    icon: ImageVector,
    direction: String,
    label: String,
    caption: String,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
) {
    // Icon above text so the tile still reads cleanly on narrow (360dp) phones.
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = direction,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun Assurances() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        AssuranceRow(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.start_safe_title),
            body = stringResource(R.string.start_safe_body),
        )
        AssuranceRow(
            icon = Icons.Rounded.Layers,
            title = stringResource(R.string.start_formats_title),
            body = stringResource(R.string.start_formats_body),
        )
    }
}

@Composable
private fun AssuranceRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.xxs))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Diagnostics(logPath: String) {
    if (logPath.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = stringResource(R.string.start_diagnostics),
                style = MaterialTheme.typography.labelMedium,
                color = OmaiTheme.colors.textTertiary,
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = OmaiTheme.colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    text = stringResource(R.string.start_diagnostics_body, logPath),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.md),
                )
            }
        }
    }
}

@Composable
private fun StartBottomBar(
    importing: Boolean,
    importMessage: String,
    onSelectFolderClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = importing, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            Column(
                modifier = Modifier
                    .widthIn(max = Spacing.contentMaxWidth)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    // Stays in its enabled colours while importing so the spinner reads as progress, not as "unavailable".
                    onClick = { if (!importing) onSelectFolderClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    ButtonContent(
                        text = if (importing) {
                            importMessage.ifEmpty { stringResource(R.string.start_importing) }
                        } else {
                            stringResource(R.string.start_cta)
                        },
                        icon = Icons.Rounded.FolderOpen,
                        loading = importing,
                    )
                }
            }
        }
    }
}
