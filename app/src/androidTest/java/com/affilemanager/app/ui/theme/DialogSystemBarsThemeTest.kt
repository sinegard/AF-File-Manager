package com.affilemanager.app.ui.theme

import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class DialogSystemBarsThemeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fullScreenDialogFollowsLiveLightDarkAndCustomThemeChoices() = checkWindow(true)
    @Test fun floatingDialogFollowsLiveLightDarkAndCustomThemeChoices() = checkWindow(false)

    private fun checkWindow(fullScreen: Boolean) {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        data class Case(val name: String, val settings: AppearanceSettings, val darkIcons: Boolean)
        val cases = listOf(
            Case("dark", AppearanceSettings(themeMode = AppThemeMode.DARK), false),
            Case("light", AppearanceSettings(themeMode = AppThemeMode.LIGHT), true),
            Case("custom-dark", AppearanceSettings(themeMode = AppThemeMode.LIGHT, colorPalette = AppColorPalette.CUSTOM,
                customColors = CustomThemeColors(background = 0xff121212.toInt(), surface = 0xff121212.toInt())), false),
            Case("custom-light", AppearanceSettings(themeMode = AppThemeMode.DARK, colorPalette = AppColorPalette.CUSTOM,
                customColors = CustomThemeColors(background = 0xfff6f8fc.toInt(), surface = 0xfff6f8fc.toInt())), true),
        )
        val selected = mutableStateOf(cases.first().settings)
        val native = AtomicReference<Pair<Window, View>?>()
        compose.setContent {
            AFFileManagerTheme(selected.value) {
                Surface(Modifier.fillMaxSize()) { Text("Underlying AF test window") }
                AfDialog({}, DialogProperties(usePlatformDefaultWidth = !fullScreen, decorFitsSystemWindows = !fullScreen)) {
                    val view = LocalView.current
                    val window = (view.parent as? DialogWindowProvider)?.window
                    SideEffect { if (window != null) native.set(window to view) }
                    Surface((if (fullScreen) Modifier.fillMaxSize() else Modifier.width(240.dp).height(200.dp))
                        .testTag("dialog_system_bars_body"), color = MaterialTheme.colorScheme.background) {
                        Text("AF dialog system bars")
                    }
                }
            }
        }
        cases.forEach { case ->
            compose.runOnIdle { selected.value = case.settings }
            compose.waitUntil(5_000) { native.get()?.second?.isAttachedToWindow == true }
            compose.onNodeWithTag("dialog_system_bars_body").assertIsDisplayed()
            compose.waitForIdle()
            val flags = compose.runOnIdle {
                val (window, view) = requireNotNull(native.get())
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.waitForIdle(250, 5000)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(uiAuditDirectory(instrumentation.targetContext), "window-bars-$fullScreen-${case.name}.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            assertEquals("$fullScreen/${case.name}: status icons do not follow the selected AF background", case.darkIcons, flags.first)
            assertEquals("$fullScreen/${case.name}: navigation icons do not follow the selected AF background", case.darkIcons, flags.second)
        }
    }
}
