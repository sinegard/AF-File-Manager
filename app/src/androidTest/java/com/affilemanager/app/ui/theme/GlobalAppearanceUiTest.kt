package com.affilemanager.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.MainActivity
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.ui.HomeToolPage
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GlobalAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun customBackgroundAndWallpaperReachEveryBrowserInsteadOfOnlyTheHomeScreen() {
        check(android.os.Build.MODEL.contains("sdk"))
        val context = compose.activity
        val model = ViewModelProvider(context)[MainViewModel::class.java]
        val before = model.appearanceSettings.value
        val source = File(context.cacheDir, "global-appearance-${System.nanoTime()}.png")
        val folder = File(context.cacheDir, "appearance-browser-${System.nanoTime()}").apply { mkdirs(); resolve("test.txt").writeText("fixture") }
        val wallpaperColor = 0xff2156ae.toInt()
        val backgroundColor = 0xff462054.toInt()
        val bitmap = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(wallpaperColor) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            compose.runOnUiThread {
                model.setWallpaper(null)
                model.setCustomColors(CustomThemeColors(background = backgroundColor, surface = 0xffffffe0.toInt(),
                    popup = 0xffff5aaf.toInt(), controls = 0xff55edbb.toInt()))
                model.setCardTransparency(100); model.setWallpaperShading(0); model.setTransparentMenus(false)
                model.showFilesHome()
            }
            checkBrowsers(model, folder, backgroundColor, "background")
            compose.runOnUiThread { model.setWallpaper(FileProvider.getUriForFile(context, "${context.packageName}.files", source)) }
            compose.waitUntil(8_000) { model.appearanceSettings.value.wallpaperRevision > 0 }
            checkBrowsers(model, folder, wallpaperColor, "wallpaper")
            compose.runOnUiThread { model.setWallpaperShading(100); model.showFilesHome() }
            captureAndCheck("wallpaper-shading-100", backgroundColor)
        } finally {
            compose.runOnUiThread {
                model.closeFileCategory(); model.closeTrashBrowser(); model.closeAdvancedBrowser(); model.closeHomeToolPage()
                model.setWallpaper(null); model.setCustomColors(before.customColors); model.setColorPalette(before.colorPalette)
                model.setCardTransparency(before.cardTransparency); model.setWallpaperShading(before.wallpaperShading)
                model.setTransparentMenus(before.transparentMenus); model.showFilesHome()
            }
            source.delete(); folder.deleteRecursively()
        }
    }

    private fun checkBrowsers(model: MainViewModel, folder: File, color: Int, variant: String) {
        compose.runOnUiThread { model.showFilesHome() }
        captureAndCheck("$variant-home", color)
        compose.runOnUiThread { model.activatePanel(com.affilemanager.app.ui.PanelId.LEFT); model.openQuickPathFromHome(folder.path) }
        compose.onNodeWithTag("directory_toolbar_local_LEFT").assertIsDisplayed()
        compose.onNodeWithText("test.txt").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(200)
        captureAndCheck("$variant-local", color)
        FileCategory.entries.forEach { category ->
            compose.runOnUiThread { model.openFileCategory(category) }
            compose.onNodeWithTag("directory_toolbar_category").assertIsDisplayed()
            captureAndCheck("$variant-${category.name.lowercase()}", color)
            compose.runOnUiThread { model.closeFileCategory() }
        }
        listOf(HomeToolPage.FAVORITES, HomeToolPage.TAGS).forEach { page ->
            compose.runOnUiThread { model.openHomeToolPage(page) }
            compose.onNodeWithTag("home_tools_page_${page.name.lowercase()}").assertIsDisplayed()
            captureAndCheck("$variant-${page.name.lowercase()}", color)
            compose.runOnUiThread { model.closeHomeToolPage() }
        }
        compose.runOnUiThread { model.openTrashBrowser() }
        compose.onNodeWithTag("trash-browser-dialog").assertIsDisplayed()
        captureAndCheck("$variant-trash", color)
        compose.runOnUiThread { model.closeTrashBrowser(); model.openAdvancedBrowser("/") }
        compose.onNodeWithTag("directory_toolbar_advanced").assertIsDisplayed()
        captureAndCheck("$variant-advanced", color)
        compose.runOnUiThread { model.closeAdvancedBrowser() }
    }

    private fun captureAndCheck(name: String, expected: Int) {
        compose.waitForIdle()
        val deadline = android.os.SystemClock.uptimeMillis() + 2_000
        var image: Bitmap
        var matches: Int
        var count: Int
        do {
            val frame = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread { android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
            } }
            assertTrue("Native frame was not drawn", frame.await(2, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2_000)
            image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            matches = 0
            count = 0
            for (y in image.height / 5 until image.height * 4 / 5 step 8) {
                for (x in image.width / 10 until image.width * 9 / 10 step 8) {
                    val pixel = image.getPixel(x, y)
                    if (listOf(0, 8, 16).all { shift -> kotlin.math.abs(((pixel shr shift) and 255) - ((expected shr shift) and 255)) <= 4 }) matches++
                    count++
                }
            }
            if (matches > count * .40 || android.os.SystemClock.uptimeMillis() >= deadline) break
            image.recycle()
            android.os.SystemClock.sleep(40)
        } while (true)
        try {
            val folder = File(compose.activity.getExternalFilesDir("validation"), "issues-164-167").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("$name: page wallpaper/background is covered ($matches/$count pixels)", matches > count * .40)
        } finally { image.recycle() }
    }
}
