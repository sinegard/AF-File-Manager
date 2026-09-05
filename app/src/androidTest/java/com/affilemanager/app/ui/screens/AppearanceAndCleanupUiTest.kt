package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.cleanup.DeviceCleanupApp
import com.affilemanager.app.cleanup.DeviceCleanupSnapshot
import com.affilemanager.app.ui.DeviceCleanupUiState
import com.affilemanager.app.ui.theme.CustomThemeColors
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import com.affilemanager.app.ui.theme.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppearanceAndCleanupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optionalRedPaletteAndCustomPaletteRenderInLightDarkAndAmoledModes() {
        val settings = mutableStateOf(AppearanceSettings(themeMode = AppThemeMode.LIGHT))
        compose.setContent { AFFileManagerTheme(settings.value) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppearanceSettingsCard(settings.value,
                    { settings.value = settings.value.copy(themeMode = it) },
                    { settings.value = settings.value.copy(colorPalette = it) },
                    { settings.value = settings.value.copy(amoledBlack = it) },
                    { settings.value = settings.value.copy(colorPalette = AppColorPalette.CUSTOM, customColors = it); true })
            }
        } }
        compose.onNodeWithTag("palette_red").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppColorPalette.RED, settings.value.colorPalette) }
        capture("palette-red-light")
        compose.runOnIdle { settings.value = settings.value.copy(themeMode = AppThemeMode.DARK) }
        capture("palette-red-dark")
        compose.onNodeWithTag("palette_custom").performScrollTo().performClick()
        compose.onNodeWithTag("custom_palette_save").performClick()
        compose.runOnIdle { settings.value = settings.value.copy(amoledBlack = true) }
        capture("palette-custom-amoled")
    }

    @Test fun customPaletteDraftValidatesResetsAndCancelsWithoutChangingSettings() {
        var saved: CustomThemeColors? = null
        val show = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (show.value) CustomPaletteDialog(CustomThemeColors(), { saved = it; true }, { show.value = false }) } }
        compose.onNodeWithTag("custom_color_0").performTextReplacement("#oops")
        compose.onNodeWithTag("custom_palette_save").assertIsNotEnabled()
        compose.onNodeWithTag("custom_color_4").performScrollTo().performTextReplacement("#111111")
        // Close only the keyboard before checking the complete modal layout.
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithText("Reset").performClick()
        assertNull(saved)
        capture("custom-palette")
        compose.onNodeWithText("Cancel").performClick()
        assertNull(saved)
        compose.runOnIdle { show.value = true }
        compose.onNodeWithTag("custom_color_0").performTextReplacement("#BA1428")
        compose.onNodeWithTag("custom_palette_save").performClick()
        compose.runOnIdle { assertEquals(0xFFBA1428.toInt(), saved?.primary) }
    }

    @Test fun cleanupIconsAndControlsTargetOnlyTheSelectedAppAndCacheNeedsSystemConfirmation() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixture = DeviceCleanupApp(app.packageName, "Test application with a long name", 1L, 1L, 8192L)
        var settings: String? = null
        var uninstall: String? = null
        compose.setContent { MaterialTheme {
            DeviceCleanupDialog(
                state = DeviceCleanupUiState(open = true, snapshot = DeviceCleanupSnapshot(true, listOf(fixture), listOf(fixture), 1, true)),
                onDismiss = {}, onRefresh = {}, onGrantUsageAccess = {},
                onOpenAppSettings = { settings = it }, onUninstall = { uninstall = it },
            )
        } }
        compose.onNodeWithTag("cleanup_icon_${app.packageName}").assertIsDisplayed()
        compose.onNodeWithTag("cleanup_uninstall_${app.packageName}").performClick()
        assertEquals(app.packageName, uninstall)
        compose.onNodeWithTag("cleanup_cache_tab").performClick()
        compose.onNodeWithTag("cleanup_icon_${app.packageName}").assertIsDisplayed()
        capture("cleanup-app-cache")
        compose.onNodeWithTag("cleanup_cache_${app.packageName}").performClick()
        compose.onNodeWithTag("cleanup_cache_instructions").assertIsDisplayed()
        assertNull(settings)
        compose.onNodeWithText("Cancel").performClick()
        assertNull(settings)
        compose.onNodeWithTag("cleanup_cache_${app.packageName}").performClick()
        compose.onNodeWithTag("cleanup_cache_settings").performClick()
        assertEquals(app.packageName, settings)
        assertEquals(0, compose.onAllNodesWithText("Old media and screenshots").fetchSemanticsNodes().size)
    }

    @Test fun failedCustomPaletteSaveStaysOpenAndShowsTheError() {
        var closed = false
        compose.setContent { MaterialTheme { CustomPaletteDialog(CustomThemeColors(), { false }, { closed = true }) } }
        compose.onNodeWithTag("custom_palette_save").performClick()
        compose.onNodeWithText("Could not save appearance settings").assertIsDisplayed()
        assertEquals(false, closed)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val directory = File(app.getExternalFilesDir(null), "validation").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name-${bitmap.width}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
