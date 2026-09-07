package com.avaruus.astro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Astrophotography Night Vision Palette (Deep Red & Pure Black)
val AstroRed = Color(0xFFFF1744)
val AstroRedDark = Color(0xFFB71C1C)
val AstroRedDim = Color(0xFF7F0000)
val AstroBlack = Color(0xFF000000)
val AstroSurface = Color(0xFF121212)
val AstroSurfaceElevated = Color(0xFF1E1E1E)
val AstroTextPrimary = Color(0xFFFF5252)
val AstroTextSecondary = Color(0xFFD32F2F)
val AstroTextDim = Color(0xFF882222)

private val AstroDarkColorScheme = darkColorScheme(
    primary = AstroRed,
    onPrimary = Color.Black,
    primaryContainer = AstroRedDark,
    onPrimaryContainer = Color.White,
    secondary = AstroRedDark,
    onSecondary = Color.White,
    background = AstroBlack,
    onBackground = AstroTextPrimary,
    surface = AstroSurface,
    onSurface = AstroTextPrimary,
    surfaceVariant = AstroSurfaceElevated,
    onSurfaceVariant = AstroTextSecondary
)

@Composable
fun AstroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AstroDarkColorScheme,
        content = content
    )
}
