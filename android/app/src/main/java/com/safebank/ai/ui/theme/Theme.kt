package com.safebank.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    secondary = SecondaryViolet,
    background = BackgroundDark,
    surface = CardBackground,
    onPrimary = TextMain,
    onSecondary = TextMain,
    onBackground = TextMain,
    onSurface = TextMain,
    error = StatusCritical,
    onError = TextMain
)

@Composable
fun SafeBankTheme(
    content: @Composable () -> Unit
) {
    // SafeBank AI uses a strict NeoSecure Dark Theme across all devices
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
