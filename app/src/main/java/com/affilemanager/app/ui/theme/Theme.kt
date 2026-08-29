package com.affilemanager.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

private val MaterialBlueDarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF234777),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBBC6DC),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E2F9),
    tertiary = Color(0xFFD9BDE4),
    onTertiary = Color(0xFF3C2948),
    tertiaryContainer = Color(0xFF543F60),
    onTertiaryContainer = Color(0xFFF6D9FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
)

private val MaterialBlueLightColors = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF565F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705575),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF28132E),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

private val AuraDarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF234777),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF8ADCD2),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFA6F3E9),
    tertiary = Color(0xFFD0BCFF),
    onTertiary = Color(0xFF381E72),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111722),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF283244),
    onSurfaceVariant = Color(0xFFC2CAD8),
)

private val AuraLightColors = lightColorScheme(
    primary = Color(0xFF355FAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF246B64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA8F2E8),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF66558E),
    onTertiary = Color.White,
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
)

private val TokyoDarkColors = darkColorScheme(
    primary = Color(0xFF9DBFF9),
    onPrimary = Color(0xFF08315E),
    primaryContainer = Color(0xFF27496F),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFF8BD5CA),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF164E4A),
    onSecondaryContainer = Color(0xFFA6F2E8),
    tertiary = Color(0xFFC4A7E7),
    onTertiary = Color(0xFF3B2755),
    background = Color(0xFF16161E),
    onBackground = Color(0xFFC0CAF5),
    surface = Color(0xFF1A1B26),
    onSurface = Color(0xFFC0CAF5),
    surfaceVariant = Color(0xFF343B58),
    onSurfaceVariant = Color(0xFFA9B1D6),
)

private val TokyoLightColors = lightColorScheme(
    primary = Color(0xFF3D5F8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF366B65),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F1E8),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF72558F),
    onTertiary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2E9),
    onSurfaceVariant = Color(0xFF44474F),
)

private val YinYangDarkColors = darkColorScheme(
    primary = Color(0xFFE5E2E1),
    onPrimary = Color(0xFF313030),
    primaryContainer = Color(0xFF474646),
    onPrimaryContainer = Color(0xFFFFF8F6),
    secondary = Color(0xFFC8C6C5),
    onSecondary = Color(0xFF303030),
    tertiary = Color(0xFFBFC6CE),
    onTertiary = Color(0xFF29313A),
    background = Color(0xFF101010),
    onBackground = Color(0xFFE6E1E0),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFE6E1E0),
    surfaceVariant = Color(0xFF3A3939),
    onSurfaceVariant = Color(0xFFCBC6C5),
)

private val YinYangLightColors = lightColorScheme(
    primary = Color(0xFF454747),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2E1),
    onPrimaryContainer = Color(0xFF191C1C),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E2E1),
    onSecondaryContainer = Color(0xFF1C1B1B),
    tertiary = Color(0xFF59636D),
    onTertiary = Color.White,
    background = Color(0xFFFAF9F8),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFAF9F8),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE5E2E1),
    onSurfaceVariant = Color(0xFF484646),
)

fun palettePreviewColors(palette: AppColorPalette): List<Color> = when (palette) {
    AppColorPalette.DEFAULT -> listOf(Color(0xFF006B5D), Color(0xFF5DD6C0), Color(0xFF9DC9FF))
    AppColorPalette.DYNAMIC -> listOf(Color(0xFF4866A8), Color(0xFFA9C7FF), Color(0xFFD6B9FF))
    AppColorPalette.CATPPUCCIN -> listOf(Color(0xFFCBA6F7), Color(0xFF89B4FA), Color(0xFFFAB387))
    AppColorPalette.ORANGE -> listOf(Color(0xFF9A4600), Color(0xFFFFB86B), Color(0xFFFFDDB8))
    AppColorPalette.MATERIAL_BLUE -> listOf(Color(0xFF415F91), Color(0xFFAEC6FF), Color(0xFFD9BDE4))
    AppColorPalette.AURA -> listOf(Color(0xFF355FAD), Color(0xFF8ADCD2), Color(0xFFD0BCFF))
    AppColorPalette.TOKYO -> listOf(Color(0xFF3D5F8F), Color(0xFF8BD5CA), Color(0xFFC4A7E7))
    AppColorPalette.YIN_YANG -> listOf(Color(0xFF454747), Color(0xFFC8C6C5), Color(0xFFF5F5F5))
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
        AppColorPalette.MATERIAL_BLUE -> if (darkTheme) MaterialBlueDarkColors else MaterialBlueLightColors
        AppColorPalette.AURA -> if (darkTheme) AuraDarkColors else AuraLightColors
        AppColorPalette.TOKYO -> if (darkTheme) TokyoDarkColors else TokyoLightColors
        AppColorPalette.YIN_YANG -> if (darkTheme) YinYangDarkColors else YinYangLightColors
        AppColorPalette.DEFAULT -> if (darkTheme) DefaultDarkColors else DefaultLightColors
    }
    val colors = if (settings.amoledBlack && darkTheme) baseColors.withAmoledBackground() else baseColors

    MaterialTheme(
        colorScheme = colors,
    ) {
        SystemBarsTheme(colors = colors, darkTheme = darkTheme)
        content()
    }
}

@Composable
private fun SystemBarsTheme(colors: ColorScheme, darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = colors.surface.toArgb()
        window.navigationBarColor = colors.surfaceContainer.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
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
