package com.zibi.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Zibi brand colors matching the bubble overlay design
private val DarkCharcoal = Color(0xFF1A1A2E)
private val DarkCharcoalLighter = Color(0xFF1E1E2E)
private val ZibiWhite = Color.White
private val ZibiRed = Color(0xFFFF0000)

private val DarkColorScheme = darkColorScheme(
    primary = ZibiWhite,
    primaryContainer = DarkCharcoalLighter,
    secondary = ZibiWhite,
    secondaryContainer = DarkCharcoalLighter,
    tertiary = ZibiRed,
    tertiaryContainer = DarkCharcoal,
    surface = DarkCharcoal,
    surfaceVariant = DarkCharcoalLighter,
    background = DarkCharcoal,
    onPrimary = DarkCharcoal,
    onPrimaryContainer = ZibiWhite,
    onSecondary = DarkCharcoal,
    onSecondaryContainer = ZibiWhite,
    onTertiary = ZibiWhite,
    onTertiaryContainer = ZibiWhite,
    onSurface = ZibiWhite,
    onSurfaceVariant = ZibiWhite.copy(alpha = 0.7f),
    onBackground = ZibiWhite,
    error = ZibiRed,
    onError = ZibiWhite,
    errorContainer = DarkCharcoalLighter,
    onErrorContainer = ZibiWhite,
    outline = ZibiWhite.copy(alpha = 0.3f),
    outlineVariant = ZibiWhite.copy(alpha = 0.15f),
    scrim = Color.Black,
    inverseSurface = ZibiWhite,
    inverseOnSurface = DarkCharcoal,
    inversePrimary = DarkCharcoal,
)

@Composable
fun ZibiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColorScheme
        else -> lightColorScheme(
            primary = DarkCharcoal,
            primaryContainer = ZibiWhite,
            secondary = DarkCharcoal,
            secondaryContainer = ZibiWhite,
            tertiary = ZibiRed,
            surface = ZibiWhite,
            surfaceVariant = ZibiWhite.copy(alpha = 0.9f),
            background = ZibiWhite,
            onPrimary = ZibiWhite,
            onPrimaryContainer = DarkCharcoal,
            onSecondary = ZibiWhite,
            onSecondaryContainer = DarkCharcoal,
            onTertiary = ZibiWhite,
            onSurface = DarkCharcoal,
            onSurfaceVariant = DarkCharcoal.copy(alpha = 0.7f),
            onBackground = DarkCharcoal,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
