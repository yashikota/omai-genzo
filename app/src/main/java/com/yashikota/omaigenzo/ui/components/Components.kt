package com.yashikota.omaigenzo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.R
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.theme.OmaiTheme
import com.yashikota.omaigenzo.ui.theme.OnPhoto
import com.yashikota.omaigenzo.ui.theme.PhotoScrim
import com.yashikota.omaigenzo.ui.theme.Spacing

fun SelectionState.icon(): ImageVector = when (this) {
    SelectionState.ACCEPT -> Icons.Rounded.Check
    SelectionState.REJECT -> Icons.Rounded.Close
    SelectionState.PENDING -> Icons.Rounded.Schedule
}

@Composable
fun SelectionState.label(): String = stringResource(
    when (this) {
        SelectionState.ACCEPT -> R.string.state_keep
        SelectionState.REJECT -> R.string.state_reject
        SelectionState.PENDING -> R.string.state_pending
    },
)

/** Small solid disc with the state's glyph, for thumbnails and lists. */
@Composable
fun SelectionStateBadge(
    state: SelectionState,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val colors = OmaiTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.forState(state)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = state.icon(),
            contentDescription = null,
            tint = colors.onForState(state),
            modifier = Modifier.size(size * 0.64f),
        )
    }
}

/** File-format chip laid over a photo. Always dark so it reads on any image. */
@Composable
fun PhotoBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = OnPhoto,
        maxLines = 1,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(PhotoScrim)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** Button content that swaps its icon for a spinner while work is running, without changing width much. */
@Composable
fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.size(Spacing.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(Spacing.sm))
        }
        Text(text = text, maxLines = 1)
    }
}

/** Section heading used inside cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}
