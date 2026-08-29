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
