package com.affilemanager.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.localization.AppLanguageManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WallpaperUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun optionalPrivateWallpaperRendersAcrossThemeLanguageAndRecreationAndCanBeRemoved() {
        check(android.os.Build.MODEL.contains("sdk"))
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val context = compose.activity
        val source = File(context.cacheDir, "wallpaper-ui-fixture.png")
        val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(25, 83, 170))
            drawCircle(320f, 480f, 200f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(225, 146, 22) })
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            compose.runOnUiThread { model.setWallpaper(FileProvider.getUriForFile(context, "${context.packageName}.files", source)) }
            compose.waitUntil(8_000) { model.appearanceSettings.value.wallpaperRevision > 0L }
            val revision = model.appearanceSettings.value.wallpaperRevision
            compose.onNodeWithTag("appearance_wallpaper").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("appearance_wallpaper").assertIsDisplayed()
            listOf("en" to AppColorPalette.TOKYO, "de" to AppColorPalette.AURA, "ar" to AppColorPalette.TOKYO).forEach { (language, palette) ->
                compose.runOnUiThread {
                    val active = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                    active.setColorPalette(palette); active.setThemeMode(AppThemeMode.DARK); active.setCardTransparency(65)
                    AppLanguageManager.setLanguage(compose.activity, language)
                }
                compose.waitForIdle()
                compose.onNodeWithTag("nav_files").performClick()
                val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("home_storage_heading").performScrollTo().fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(textLayouts)
                assertTrue(textLayouts.isNotEmpty())
                textLayouts.forEach { layout ->
                    assertTrue("Dark wallpaper must not inherit black default content text", CustomThemeRules.contrast(
                        layout.layoutInput.style.color, androidx.compose.ui.graphics.Color(0xFF1A1B26)) >= 4.5f)
                }
                if (language == "de") {
                    compose.onNodeWithText("Speicher").assertIsDisplayed()
                    compose.onAllNodesWithText("0 items").assertCountEquals(0)
                }
                capture("wallpaper-$language-$palette")
                compose.onNodeWithTag("nav_tools").performClick()
                compose.onNodeWithTag("wallpaper_choose").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("card_transparency").performScrollTo().assertIsDisplayed()
                capture("wallpaper-settings-$language-$palette")
            }
            compose.onNodeWithTag("wallpaper_remove").performScrollTo().performClick()
            compose.waitUntil(8_000) { !AppearanceWallpaper.file(context, revision).exists() }
            compose.onNodeWithTag("appearance_wallpaper").assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                val active = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                active.setWallpaper(null); active.setCardTransparency(0); active.setColorPalette(AppColorPalette.DEFAULT); active.setThemeMode(AppThemeMode.SYSTEM)
                AppLanguageManager.setLanguage(compose.activity, "en")
            }
            source.delete()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val root = File(compose.activity.getExternalFilesDir("validation"), "issues-158-163").apply { mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(root, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
