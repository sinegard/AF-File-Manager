package com.affilemanager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DD6C0),
    onPrimary = Color(0xFF00372F),
    secondary = Color(0xFF9DC9FF),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF07101C),
    surface = Color(0xFF0B1625),
    surfaceVariant = Color(0xFF182536),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    secondary = Color(0xFF23649A),
    tertiary = Color(0xFF8B4E00),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E9F2),
)

@Composable
fun AFFileManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
