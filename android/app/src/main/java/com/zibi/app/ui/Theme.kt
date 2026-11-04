package com.zibi.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Zibi is a dark-first product: the floating bubble and the app share one palette,
// so the panel never looks like a different app from the screens inside it.
private val Ink = Color(0xFF17171F) // surface
private val InkDeep = Color(0xFF101017) // background
private val InkLow = Color(0xFF1E1E28)
private val InkMid = Color(0xFF262633)
private val InkHigh = Color(0xFF2F2F3E)
private val InkHighest = Color(0xFF383849)
private val Mist = Color(0xFFEDEDF3) // onSurface
private val MistMuted = Color(0xFFA6A6BA) // onSurfaceVariant

private val Accent = Color(0xFF7E9BFF) // primary
private val AccentDeep = Color(0xFF0D1330) // onPrimary
private val AccentContainer = Color(0xFF2A3566)
private val AccentContainerText = Color(0xFFDCE3FF)

private val Alarm = Color(0xFFFF6B6B) // recording / error
private val AlarmDeep = Color(0xFF3B0A0A)
private val AlarmContainer = Color(0xFF5C1F1F)
private val AlarmContainerText = Color(0xFFFFDAD6)

private val ZibiDarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = AccentDeep,
    primaryContainer = AccentContainer,
    onPrimaryContainer = AccentContainerText,
    inversePrimary = AccentContainer,

    secondary = Color(0xFFB9B9CC),
    onSecondary = Color(0xFF232330),
    secondaryContainer = InkHigh,
    onSecondaryContainer = Color(0xFFE1E1EC),

    tertiary = Alarm,
    onTertiary = AlarmDeep,
    tertiaryContainer = AlarmContainer,
    onTertiaryContainer = AlarmContainerText,

    background = InkDeep,
    onBackground = Mist,
    surface = Ink,
    onSurface = Mist,
    surfaceVariant = InkHigh,
    onSurfaceVariant = MistMuted,
    surfaceTint = Accent,
    surfaceDim = InkDeep,
    surfaceBright = InkHighest,
    surfaceContainerLowest = InkDeep,
    surfaceContainerLow = InkLow,
    surfaceContainer = InkMid,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = InkHighest,

    inverseSurface = Mist,
    inverseOnSurface = Ink,

    error = Alarm,
    onError = AlarmDeep,
    errorContainer = AlarmContainer,
    onErrorContainer = AlarmContainerText,

    outline = Color(0xFF49495E),
    outlineVariant = Color(0xFF2F2F3C),
    scrim = Color.Black,
)

@Composable
fun ZibiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZibiDarkScheme, content = content)
}
