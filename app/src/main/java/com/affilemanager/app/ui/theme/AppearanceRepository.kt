package com.affilemanager.app.ui.theme

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AppColorPalette {
    DEFAULT,
    DYNAMIC,
    CATPPUCCIN,
    ORANGE,
    MATERIAL_BLUE,
    AURA,
    TOKYO,
    YIN_YANG,
    RED,
    CUSTOM,
}

data class AppearanceSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val amoledBlack: Boolean = false,
    val customColors: CustomThemeColors = CustomThemeColors(),
    val wallpaperRevision: Long = 0L,
    val cardTransparency: Int = 0,
    val wallpaperShading: Int = 72,
    val transparentMenus: Boolean = false,
)

object AppearanceRules {
    fun useDarkTheme(mode: AppThemeMode, systemDark: Boolean): Boolean = when (mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    fun paletteSupported(palette: AppColorPalette, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        palette != AppColorPalette.DYNAMIC || sdkInt >= Build.VERSION_CODES.S
}

class AppearanceRepository(context: Context) {
    companion object {
        private const val PREFS = "appearance_v1"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_PALETTE = "color_palette"
        private const val KEY_AMOLED_BLACK = "amoled_black"
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppearanceSettings> = mutableSettings.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) = update { it.copy(themeMode = mode) }

    fun setColorPalette(palette: AppColorPalette) = update { it.copy(colorPalette = palette) }

    fun setAmoledBlack(enabled: Boolean) = update { it.copy(amoledBlack = enabled) }

    fun setWallpaperRevision(revision: Long) = update { it.copy(wallpaperRevision = revision.coerceAtLeast(0L)) }

    fun setCardTransparency(percent: Int) = update { it.copy(cardTransparency = percent.coerceIn(0, 100)) }

    fun setWallpaperShading(percent: Int) = update { it.copy(wallpaperShading = percent.coerceIn(0, 100)) }

    fun setTransparentMenus(enabled: Boolean) = update { it.copy(transparentMenus = enabled) }

    fun setCustomColors(colors: CustomThemeColors) = update {
        require(colors.values().all { color -> color ushr 24 == 255 }) { "Use opaque RGB colors" }
        it.copy(colorPalette = AppColorPalette.CUSTOM, customColors = colors)
    }

    @Synchronized
    private fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val updated = transform(mutableSettings.value)
        if (updated == mutableSettings.value) return
        check(
            preferences.edit()
                .putString(KEY_THEME_MODE, updated.themeMode.name)
                .putString(KEY_COLOR_PALETTE, updated.colorPalette.name)
                .putBoolean(KEY_AMOLED_BLACK, updated.amoledBlack)
                .putLong("wallpaper_revision", updated.wallpaperRevision)
                .putInt("card_transparency", updated.cardTransparency)
                .putInt("wallpaper_shading", updated.wallpaperShading)
                .putBoolean("transparent_menus", updated.transparentMenus)
                .putString("custom_colors_v1", updated.customColors.values().take(5).joinToString(",", transform = CustomThemeRules::hex))
                .putString("custom_colors_v2", updated.customColors.values().joinToString(",", transform = CustomThemeRules::hex))
                .commit(),
        ) { "Appearance settings could not be saved" }
        mutableSettings.value = updated
    }

    private fun readSettings(): AppearanceSettings = AppearanceSettings(
        themeMode = preferences.getString(KEY_THEME_MODE, null)
            ?.let { stored -> AppThemeMode.entries.firstOrNull { it.name == stored } }
            ?: AppThemeMode.SYSTEM,
        colorPalette = preferences.getString(KEY_COLOR_PALETTE, null)
            ?.let { stored -> AppColorPalette.entries.firstOrNull { it.name == stored } }
            ?: AppColorPalette.DEFAULT,
        amoledBlack = preferences.getBoolean(KEY_AMOLED_BLACK, false),
        customColors = CustomThemeRules.parseStored(preferences.getString("custom_colors_v2", null)
            ?: preferences.getString("custom_colors_v1", null)),
        wallpaperRevision = preferences.getLong("wallpaper_revision", 0L).coerceAtLeast(0L),
        cardTransparency = preferences.getInt("card_transparency", 0).coerceIn(0, 100),
        wallpaperShading = preferences.getInt("wallpaper_shading", 72).coerceIn(0, 100),
        transparentMenus = preferences.getBoolean("transparent_menus", false),
    )
}
