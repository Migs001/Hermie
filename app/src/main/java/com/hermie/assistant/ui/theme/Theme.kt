package com.hermie.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HermieColorScheme = lightColorScheme(
    primary = HermiePrimary,
    onPrimary = HermieOnPrimary,
    primaryContainer = HermieForest,
    onPrimaryContainer = HermieCream,
    secondary = HermieSecondary,
    onSecondary = HermieCream,
    secondaryContainer = HermieTan,
    onSecondaryContainer = HermieForest,
    tertiary = HermieTerra,
    onTertiary = HermieCream,
    background = HermieSurface,
    onBackground = HermieOnSurface,
    surface = HermieSurface,
    onSurface = HermieOnSurface,
    surfaceVariant = HermieSurfaceVariant,
    onSurfaceVariant = HermieOnSurfaceVariant,
    outline = HermieBorder,
    error = HermieError,
    onError = HermieCream
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = HermieSurface.toArgb()
            window.navigationBarColor = HermieSurface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = HermieColorScheme,
        typography = HermieTypography,
        content = content
    )
}
