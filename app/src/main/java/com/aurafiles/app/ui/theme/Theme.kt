package com.aurafiles.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val AuraBlue = Color(0xFF007AFF)
val AuraPurple = Color(0xFF7655E8)
val AuraPink = Color(0xFFE85C91)
val AuraOrange = Color(0xFFE99A31)
val AuraGreen = Color(0xFF37A668)
val AuraRed = Color(0xFFE55353)

private val AuraLightColors = lightColorScheme(
    primary = AuraBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEEFF),
    onPrimaryContainer = Color(0xFF003A73),
    secondary = AuraPurple,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111113),
    surface = Color.White,
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF6D6D72),
    outline = Color(0xFFD1D1D6),
    error = AuraRed,
)

private val AuraDarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFF082E59),
    primaryContainer = Color(0xFF1F3858),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Color(0xFFA58CFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF7F7F8),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF7F7F8),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF38383A),
    error = Color(0xFFFF7373),
)

@Composable
fun AuraFilesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AuraDarkColors else AuraLightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
