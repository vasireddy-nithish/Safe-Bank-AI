package com.safebank.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = TextMain,
    primaryContainer = PrimaryPurpleGlow,
    onPrimaryContainer = SecondaryViolet,
    secondary = SecondaryViolet,
    onSecondary = TextMain,
    secondaryContainer = SecondaryVioletGlow,
    onSecondaryContainer = TextMain,
    tertiary = StatusSuccess,
    onTertiary = TextMain,
    background = BackgroundDark,
    onBackground = TextMain,
    surface = CardBackground,
    onSurface = TextMain,
    surfaceVariant = CardBorder,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    outlineVariant = CardBorderGlow,
    error = StatusCritical,
    onError = TextMain,
    errorContainer = StatusCriticalGlow,
    onErrorContainer = StatusCritical
)

@Composable
fun SafeBankTheme(
    content: @Composable () -> Unit
) {
    // SafeBank AI strictly uses the NeoSecure Dark Theme across all devices
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
