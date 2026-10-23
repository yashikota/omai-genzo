package com.yashikota.omaigenzo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Palette. The chrome is a neutral grey in both themes so it never tints how a photo is judged;
// the only saturated colours carry meaning (keep / reject / pending / undo) or the brand mint
// taken from the launcher icon's check mark.

// Dark
internal val Ink950 = Color(0xFF0C0D0F)
internal val Ink900 = Color(0xFF121416)
internal val Ink850 = Color(0xFF171A1D)
internal val Ink800 = Color(0xFF1D2024)
internal val Ink750 = Color(0xFF24282C)
internal val Ink700 = Color(0xFF2C3035)
internal val Ink600 = Color(0xFF3B4047)
internal val Ink400 = Color(0xFF8A919A)
internal val Ink300 = Color(0xFFA9AFB7)
internal val Ink100 = Color(0xFFECEEF1)

// Light
internal val Paper50 = Color(0xFFF7F8F9)
internal val Paper0 = Color(0xFFFFFFFF)
internal val Paper100 = Color(0xFFF1F3F5)
internal val Paper150 = Color(0xFFEBEEF1)
internal val Paper200 = Color(0xFFE4E7EB)
internal val Paper300 = Color(0xFFD5DAE0)
internal val Paper400 = Color(0xFFBFC5CC)
internal val Slate600 = Color(0xFF59616A)
internal val Slate900 = Color(0xFF16191C)

// Brand mint (icon check mark), tuned per theme for contrast.
internal val Mint300 = Color(0xFF4CEFB9)
internal val Mint900 = Color(0xFF003828)
internal val Mint800 = Color(0xFF0B4A37)
internal val Mint100 = Color(0xFFB4F5DD)
internal val Mint700 = Color(0xFF00765A)
internal val Mint50 = Color(0xFFD6F8EA)

internal val Coral300 = Color(0xFFFF7A70)
internal val Coral900 = Color(0xFF4A1512)
internal val Coral700 = Color(0xFFC0332B)
internal val Coral50 = Color(0xFFFDE3E0)

internal val Amber300 = Color(0xFFFFC95C)
internal val Amber900 = Color(0xFF45320B)
internal val Amber700 = Color(0xFF9A5B00)
internal val Amber50 = Color(0xFFFFF0CF)

internal val Iris300 = Color(0xFFB1B9FF)
internal val Iris900 = Color(0xFF262B5C)
internal val Iris700 = Color(0xFF4A50C9)
internal val Iris50 = Color(0xFFE5E7FF)

/** Always-dark scrim for text laid over a photo, in either theme. */
val PhotoScrim = Color(0xD9000000)
val OnPhoto = Color(0xFFFFFFFF)
val OnPhotoMuted = Color(0xFFC9CDD2)

// Legacy names, kept so existing call sites compile. They now follow the active light/dark theme.
// New code should read MaterialTheme.colorScheme or OmaiTheme.colors directly.
val DarkBackground: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background
val DarkSurface: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainer
val DarkSurfaceVariant: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerHigh
val BorderColor: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant
val PrimaryNeon: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary
val PrimaryLight: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer
val SecondaryAccent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary
val AcceptGreen: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.keep
val AcceptGreenContainer: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.keepContainer
val RejectRed: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.reject
val RejectRedContainer: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.rejectContainer
val UndoPurple: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.undo
val SkipYellow: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.pending
val TextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface
val TextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextTertiary: Color
    @Composable @ReadOnlyComposable
    get() = OmaiTheme.colors.textTertiary
