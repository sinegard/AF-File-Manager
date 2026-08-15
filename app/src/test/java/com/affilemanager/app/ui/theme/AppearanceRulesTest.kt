package com.affilemanager.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceRulesTest {
    @Test
    fun systemModeFollowsSystemAndExplicitModesOverrideIt() {
        assertTrue(AppearanceRules.useDarkTheme(AppThemeMode.SYSTEM, systemDark = true))
        assertFalse(AppearanceRules.useDarkTheme(AppThemeMode.SYSTEM, systemDark = false))
        assertFalse(AppearanceRules.useDarkTheme(AppThemeMode.LIGHT, systemDark = true))
        assertTrue(AppearanceRules.useDarkTheme(AppThemeMode.DARK, systemDark = false))
    }

    @Test
    fun dynamicPaletteRequiresAndroidTwelve() {
        assertFalse(AppearanceRules.paletteSupported(AppColorPalette.DYNAMIC, sdkInt = 30))
        assertTrue(AppearanceRules.paletteSupported(AppColorPalette.DYNAMIC, sdkInt = 31))
        assertTrue(AppearanceRules.paletteSupported(AppColorPalette.CATPPUCCIN, sdkInt = 26))
    }
}
