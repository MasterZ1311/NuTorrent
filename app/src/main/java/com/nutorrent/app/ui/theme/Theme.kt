package com.nutorrent.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NuPrimary,
    onPrimary = Color.Black,
    primaryContainer = NuDarkBorder,
    onPrimaryContainer = NuPrimary,
    secondary = NuSecondary,
    onSecondary = Color.White,
    background = NuDarkBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = NuDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = NuDarkCard,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = NuDarkBorder,
    error = NuError,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = NuPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = NuLightBorder,
    onPrimaryContainer = NuPrimaryDark,
    secondary = NuSecondary,
    onSecondary = Color.White,
    background = NuLightBackground,
    onBackground = Color(0xFF0F172A),
    surface = NuLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = NuLightCard,
    onSurfaceVariant = Color(0xFF475569),
    outline = NuLightBorder,
    error = NuError,
    onError = Color.White
)

@Composable
fun NuTorrentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
