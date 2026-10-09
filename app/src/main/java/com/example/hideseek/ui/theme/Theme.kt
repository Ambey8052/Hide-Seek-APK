package com.example.hideseek.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ColorScheme = darkColorScheme(
    primary = Hs.Primary,
    onPrimary = Hs.PrimaryText,
    secondary = Hs.Police,
    background = Hs.Bg,
    onBackground = Hs.Text,
    surface = Hs.Surface,
    onSurface = Hs.Text,
    surfaceVariant = Hs.SurfaceAlt,
    onSurfaceVariant = Hs.TextDim,
    outline = Hs.Border,
    error = Hs.Danger,
)

/** Always dark: the game is often played at night. */
@Composable
fun HideSeekTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = Typography, content = content)
}
