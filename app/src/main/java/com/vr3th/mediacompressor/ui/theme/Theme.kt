package com.vr3th.mediacompressor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = DustyPink,
    onPrimary = CharcoalBlack,
    primaryContainer = DustyPinkContainer,
    onPrimaryContainer = OffWhite,
    secondary = DustyPinkDark,
    onSecondary = CharcoalBlack,
    background = CharcoalBlack,
    onBackground = OffWhite,
    surface = CharcoalSurface,
    onSurface = OffWhite,
    surfaceVariant = CharcoalCard,
    onSurfaceVariant = TextMuted,
    outline = CharcoalBorder
)

@Composable
fun MediaCompressorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
