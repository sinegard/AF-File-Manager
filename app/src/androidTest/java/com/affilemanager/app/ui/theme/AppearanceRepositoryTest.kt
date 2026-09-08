package com.affilemanager.app.ui.theme

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceRepositoryTest {
    @Test
    fun customPalettePersistsAndInvalidWritesLeavePreviousSettingsIntact() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val repository = AppearanceRepository(application)
        val before = repository.settings.value
        try {
            val custom = CustomThemeColors(primary = 0xFFAB1428.toInt(), background = 0xFF111111.toInt(), surface = 0xFF171717.toInt(),
                popup = 0xff663311.toInt(), controls = 0xfffada11.toInt())
            repository.setCustomColors(custom)
            repository.setWallpaperShading(38)
            repository.setTransparentMenus(true)
            assertEquals(38, AppearanceRepository(application).settings.value.wallpaperShading)
            assertEquals(true, AppearanceRepository(application).settings.value.transparentMenus)
            assertEquals(AppColorPalette.CUSTOM, AppearanceRepository(application).settings.value.colorPalette)
            assertEquals(custom, AppearanceRepository(application).settings.value.customColors)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { repository.setCustomColors(custom.copy(primary = 0x00123456)) }
            assertEquals(custom, AppearanceRepository(application).settings.value.customColors)
            repository.setColorPalette(AppColorPalette.RED)
            assertEquals(custom, AppearanceRepository(application).settings.value.customColors)
            assertEquals(AppColorPalette.RED, AppearanceRepository(application).settings.value.colorPalette)
        } finally {
            repository.setCustomColors(before.customColors)
            repository.setColorPalette(before.colorPalette)
            repository.setWallpaperShading(before.wallpaperShading)
            repository.setTransparentMenus(before.transparentMenus)
        }
    }

    @Test
    fun appearanceSettingsPersistAcrossRepositoryInstances() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val repository = AppearanceRepository(application)
        try {
            repository.setThemeMode(AppThemeMode.DARK)
            repository.setColorPalette(AppColorPalette.MATERIAL_BLUE)
            repository.setAmoledBlack(true)

            assertEquals(
                AppearanceSettings(AppThemeMode.DARK, AppColorPalette.MATERIAL_BLUE, amoledBlack = true),
                AppearanceRepository(application).settings.value,
            )
        } finally {
            repository.setThemeMode(AppThemeMode.SYSTEM)
            repository.setColorPalette(AppColorPalette.DEFAULT)
            repository.setAmoledBlack(false)
        }
    }
}
