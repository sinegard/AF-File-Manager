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
}

data class AppearanceSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val amoledBlack: Boolean = false,
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

    @Synchronized
    private fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val updated = transform(mutableSettings.value)
        if (updated == mutableSettings.value) return
        check(
            preferences.edit()
                .putString(KEY_THEME_MODE, updated.themeMode.name)
                .putString(KEY_COLOR_PALETTE, updated.colorPalette.name)
                .putBoolean(KEY_AMOLED_BLACK, updated.amoledBlack)
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
    )
}
