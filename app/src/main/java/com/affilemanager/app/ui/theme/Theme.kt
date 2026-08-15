package com.affilemanager.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DefaultDarkColors = darkColorScheme(
    primary = Color(0xFF5DD6C0),
    onPrimary = Color(0xFF00372F),
    secondary = Color(0xFF9DC9FF),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF07101C),
    surface = Color(0xFF0B1625),
    surfaceVariant = Color(0xFF182536),
)

private val DefaultLightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    secondary = Color(0xFF23649A),
    tertiary = Color(0xFF8B4E00),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E9F2),
)

private val CatppuccinDarkColors = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF352347),
    secondary = Color(0xFF89B4FA),
    tertiary = Color(0xFFFAB387),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF181825),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
)

private val CatppuccinLightColors = lightColorScheme(
    primary = Color(0xFF8839EF),
    onPrimary = Color.White,
    secondary = Color(0xFF1E66F5),
    tertiary = Color(0xFFFE640B),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFF4F5F9),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFDCE0E8),
    onSurfaceVariant = Color(0xFF5C5F77),
)

private val OrangeDarkColors = darkColorScheme(
    primary = Color(0xFFFFB86B),
    onPrimary = Color(0xFF4B2500),
    secondary = Color(0xFFFFDDB8),
    tertiary = Color(0xFFFFB4AB),
    background = Color(0xFF1C1108),
    surface = Color(0xFF24170E),
    surfaceVariant = Color(0xFF49392C),
)

private val OrangeLightColors = lightColorScheme(
    primary = Color(0xFF9A4600),
    onPrimary = Color.White,
    secondary = Color(0xFF7A5734),
    tertiary = Color(0xFF9C4238),
    background = Color(0xFFFFF8F4),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF2DFD1),
)

fun palettePreviewColors(palette: AppColorPalette): List<Color> = when (palette) {
    AppColorPalette.DEFAULT -> listOf(Color(0xFF006B5D), Color(0xFF5DD6C0), Color(0xFF9DC9FF))
    AppColorPalette.DYNAMIC -> listOf(Color(0xFF4866A8), Color(0xFFA9C7FF), Color(0xFFD6B9FF))
    AppColorPalette.CATPPUCCIN -> listOf(Color(0xFFCBA6F7), Color(0xFF89B4FA), Color(0xFFFAB387))
    AppColorPalette.ORANGE -> listOf(Color(0xFF9A4600), Color(0xFFFFB86B), Color(0xFFFFDDB8))
}

@Composable
fun AFFileManagerTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = AppearanceRules.useDarkTheme(settings.themeMode, systemDark)
    val context = LocalContext.current
    val baseColors = when (settings.colorPalette) {
        AppColorPalette.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) DefaultDarkColors else DefaultLightColors
        }
        AppColorPalette.CATPPUCCIN -> if (darkTheme) CatppuccinDarkColors else CatppuccinLightColors
        AppColorPalette.ORANGE -> if (darkTheme) OrangeDarkColors else OrangeLightColors
        AppColorPalette.DEFAULT -> if (darkTheme) DefaultDarkColors else DefaultLightColors
    }
    val colors = if (settings.amoledBlack && darkTheme) baseColors.withAmoledBackground() else baseColors

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

private fun ColorScheme.withAmoledBackground(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color(0xFF050505),
    surfaceContainerHigh = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF121212),
)
