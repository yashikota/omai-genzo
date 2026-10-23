package com.yashikota.omaigenzo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.SelectionState

private val DarkColorScheme = darkColorScheme(
    primary = Mint300,
    onPrimary = Mint900,
    primaryContainer = Mint800,
    onPrimaryContainer = Mint100,
    secondary = Ink300,
    onSecondary = Ink950,
    secondaryContainer = Ink750,
    onSecondaryContainer = Ink100,
    tertiary = Iris300,
    onTertiary = Iris900,
    error = Coral300,
    onError = Coral900,
    errorContainer = Coral900,
    onErrorContainer = Coral50,
    background = Ink950,
    onBackground = Ink100,
    surface = Ink950,
    onSurface = Ink100,
    surfaceVariant = Ink750,
    onSurfaceVariant = Ink300,
    surfaceDim = Ink950,
    surfaceBright = Ink700,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink850,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink750,
    outline = Ink600,
    outlineVariant = Ink700,
    inverseSurface = Ink100,
    inverseOnSurface = Ink900,
    inversePrimary = Mint700,
    scrim = Color.Black,
)

private val LightColorScheme = lightColorScheme(
    primary = Mint700,
    onPrimary = Paper0,
    primaryContainer = Mint50,
    onPrimaryContainer = Mint900,
    secondary = Slate600,
    onSecondary = Paper0,
    secondaryContainer = Paper200,
    onSecondaryContainer = Slate900,
    tertiary = Iris700,
    onTertiary = Paper0,
    error = Coral700,
    onError = Paper0,
    errorContainer = Coral50,
    onErrorContainer = Coral900,
    background = Paper50,
    onBackground = Slate900,
    surface = Paper50,
    onSurface = Slate900,
    surfaceVariant = Paper200,
    onSurfaceVariant = Slate600,
    surfaceDim = Paper200,
    surfaceBright = Paper0,
    surfaceContainerLowest = Paper0,
    surfaceContainerLow = Paper0,
    surfaceContainer = Paper100,
    surfaceContainerHigh = Paper150,
    surfaceContainerHighest = Paper200,
    outline = Paper400,
    outlineVariant = Paper300,
    inverseSurface = Ink850,
    inverseOnSurface = Ink100,
    inversePrimary = Mint300,
    scrim = Color.Black,
)

/** Colours that carry a decision. Every screen that shows keep / reject / pending reads them from here. */
@Immutable
data class OmaiColors(
    val keep: Color,
    val onKeep: Color,
    val keepContainer: Color,
    val onKeepContainer: Color,
    val reject: Color,
    val onReject: Color,
    val rejectContainer: Color,
    val onRejectContainer: Color,
    val pending: Color,
    val onPending: Color,
    val pendingContainer: Color,
    val onPendingContainer: Color,
    val undo: Color,
    val undoContainer: Color,
    val textTertiary: Color,
    val photoCanvas: Color,
) {
    fun forState(state: SelectionState): Color = when (state) {
        SelectionState.ACCEPT -> keep
        SelectionState.REJECT -> reject
        SelectionState.PENDING -> pending
    }

    fun onForState(state: SelectionState): Color = when (state) {
        SelectionState.ACCEPT -> onKeep
        SelectionState.REJECT -> onReject
        SelectionState.PENDING -> onPending
    }

    fun containerForState(state: SelectionState): Color = when (state) {
        SelectionState.ACCEPT -> keepContainer
        SelectionState.REJECT -> rejectContainer
        SelectionState.PENDING -> pendingContainer
    }

    fun onContainerForState(state: SelectionState): Color = when (state) {
        SelectionState.ACCEPT -> onKeepContainer
        SelectionState.REJECT -> onRejectContainer
        SelectionState.PENDING -> onPendingContainer
    }
}

private val DarkOmaiColors = OmaiColors(
    keep = Mint300,
    onKeep = Mint900,
    keepContainer = Mint800,
    onKeepContainer = Mint100,
    reject = Coral300,
    onReject = Coral900,
    rejectContainer = Coral900,
    onRejectContainer = Coral50,
    pending = Amber300,
    onPending = Amber900,
    pendingContainer = Amber900,
    onPendingContainer = Amber50,
    undo = Iris300,
    undoContainer = Iris900,
    textTertiary = Ink400,
    photoCanvas = Ink900,
)

private val LightOmaiColors = OmaiColors(
    keep = Mint700,
    onKeep = Paper0,
    keepContainer = Mint50,
    onKeepContainer = Mint900,
    reject = Coral700,
    onReject = Paper0,
    rejectContainer = Coral50,
    onRejectContainer = Coral900,
    pending = Amber700,
    onPending = Paper0,
    pendingContainer = Amber50,
    onPendingContainer = Amber900,
    undo = Iris700,
    undoContainer = Iris50,
    textTertiary = Slate600.copy(alpha = 0.78f),
    // Photos are judged on a dark neutral in both themes; a light mat makes images look darker than they are.
    photoCanvas = Ink850,
)

/** 4dp grid. Use these instead of ad-hoc numbers so screens line up with each other. */
@Immutable
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Readable line length for text-heavy screens on tablets and in landscape. */
    val contentMaxWidth = 560.dp
}

private val OmaiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LocalOmaiColors = staticCompositionLocalOf { DarkOmaiColors }

object OmaiTheme {
    val colors: OmaiColors
        @Composable @ReadOnlyComposable
        get() = LocalOmaiColors.current
}

@Composable
fun OmaiGenzoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOmaiColors provides if (darkTheme) DarkOmaiColors else LightOmaiColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = OmaiTypography,
            shapes = OmaiShapes,
            content = content,
        )
    }
}
