package com.affilemanager.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun useEnglishForEveryTest() {
        compose.runOnUiThread {
            AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Files").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun startupShowsFileManagerAndCurrentPermissionState() {
        assertEquals("AF File Manager", compose.activity.applicationInfo.loadLabel(compose.activity.packageManager).toString())
        compose.onNodeWithText("Files").fetchSemanticsNode()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) {
            compose.onNodeWithText("Shared-file access is required").fetchSemanticsNode()
        } else {
            assertTrue(compose.onAllNodesWithText("Shared-file access is required").fetchSemanticsNodes().isEmpty())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("File locations").fetchSemanticsNode()
        assertTrue(compose.onAllNodesWithText("% used", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Kairysis:").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun filesDestinationOpensLocationsAndAChoiceOpensTheActivePanel() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Internal storage").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !viewModel.filesHomeVisible.value }
        assertTrue(compose.onAllNodesWithText("Kairysis:").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.filesHomeVisible.value }
        compose.onNodeWithText("File locations").fetchSemanticsNode()
    }

    @Test
    fun systemBackMovesTheActivePanelUpBeforeExiting() {
        val directory = File(compose.activity.getExternalFilesDir(null), "back-${System.nanoTime()}").apply { mkdirs() }
        try {
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            val previousPath = viewModel.leftPanel.value.path
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.path == directory.canonicalPath }
            compose.waitForIdle()
            assertTrue(
                viewModel.leftPanel.value.backHistory.lastOrNull()?.let { File(it).canonicalPath == File(previousPath).canonicalPath } == true,
            )

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

            compose.waitUntil(timeoutMillis = 5_000) {
                runCatching { File(viewModel.leftPanel.value.path).canonicalPath == File(previousPath).canonicalPath }.getOrDefault(false)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun savedConnectionCanBeEditedWithoutReenteringItsSecret() {
        val graph = (compose.activity.application as AFFileManagerApplication).graph
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val created = runBlocking {
            graph.networkProfiles.list().forEach { graph.networkProfiles.remove(it.id).getOrThrow() }
            graph.networkProfiles.save(
                NetworkProfile(
                    id = "",
                    name = "Test NAS",
                    protocol = NetworkProtocol.SFTP,
                    host = "nas.example.test",
                    port = 22,
                    username = "tester",
                    basePath = "/files",
                    allowFirstUseTrust = true,
                ),
                "kept-secret".toCharArray(),
                null,
            ).getOrThrow()
        }
        try {
            compose.runOnUiThread { viewModel.refreshProfiles() }
            compose.onNodeWithText("Connections").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.networkState.value.profiles.any { it.id == created.id } }
            compose.onNodeWithContentDescription("Edit connection").performClick()
            compose.onNodeWithText("Edit connection").fetchSemanticsNode()
            compose.onNode(hasSetTextAction() and hasText("nas.example.test", substring = true))
                .performTextReplacement("edited.example.test")

            compose.onNodeWithText("Save").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.networkState.value.profiles.singleOrNull { it.id == created.id }?.host == "edited.example.test"
            }
            val stored = runBlocking { graph.networkProfiles.list().single { it.id == created.id } }
            assertEquals("edited.example.test", stored.host)
            runBlocking {
                graph.networkProfiles.secret(created.id).getOrThrow().use { secret ->
                    assertEquals("kept-secret", secret.password.concatToString())
                }
            }
        } finally {
            runBlocking { graph.networkProfiles.remove(created.id) }
            compose.runOnUiThread { viewModel.refreshProfiles() }
        }
    }

    @Test
    fun languageCanSwitchToLithuanianAndBackToEnglish() {
        try {
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Language").fetchSemanticsNode()
            compose.onNodeWithText("Lietuvių").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Daugiau").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Daugiau").performClick()
            compose.onNodeWithText("Kalba").fetchSemanticsNode()
            compose.onNodeWithText("Įrankiai ir saugumas").fetchSemanticsNode()
            compose.onNodeWithText("English").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            compose.runOnUiThread {
                AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
            }
        }
    }

    @Test
    fun appearanceControlsApplyThemePaletteAndAmoledSetting() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithTag("appearance_settings").performScrollTo()
            compose.onNodeWithTag("theme_mode_dark").performClick()
            compose.onNodeWithTag("palette_catppuccin").performClick()
            compose.onNodeWithTag("amoled_black").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                val settings = viewModel.appearanceSettings.value
                settings.themeMode == AppThemeMode.DARK &&
                    settings.colorPalette == AppColorPalette.CATPPUCCIN &&
                    settings.amoledBlack
            }
        } finally {
            compose.runOnUiThread {
                viewModel.setThemeMode(AppThemeMode.SYSTEM)
                viewModel.setColorPalette(AppColorPalette.DEFAULT)
                viewModel.setAmoledBlack(false)
            }
        }
    }

    @Test
    fun homeLayoutButtonTogglesAndLongPressOpensFourToSixColumnSettings() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.setFilesHomeDisplaySettings(
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, gridColumns = 4),
                )
            }
            compose.onNodeWithTag("home_layout_toggle").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.filesHomeDisplaySettings.value.layoutMode == DirectoryLayoutMode.GRID
            }

            compose.onNodeWithTag("home_layout_toggle").performTouchInput { longClick() }
            compose.onNodeWithTag("display_settings_dialog").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_4").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_5").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_6").performClick()
            compose.onNodeWithTag("display_apply").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.filesHomeDisplaySettings.value.gridColumns == 6
            }
        } finally {
            compose.runOnUiThread {
                viewModel.setFilesHomeDisplaySettings(
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, gridColumns = 4),
                )
            }
        }
    }

    @Test
    fun localFolderLayoutButtonTogglesAndLongPressOpensTheSameSettings() {
        val directory = File(compose.activity.getExternalFilesDir(null), "layout-${System.nanoTime()}").apply { mkdirs() }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.setDirectoryDisplaySettings(
                    PanelId.LEFT,
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, gridColumns = 3),
                )
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                !viewModel.filesHomeVisible.value && viewModel.leftPanel.value.path == directory.canonicalPath
            }

            compose.onNodeWithTag("directory_layout_local_LEFT").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.grid }

            compose.onNodeWithTag("directory_layout_local_LEFT").performTouchInput { longClick() }
            compose.onNodeWithTag("display_settings_dialog").fetchSemanticsNode()
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            directory.deleteRecursively()
        }
    }
}
