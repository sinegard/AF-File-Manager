package com.affilemanager.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
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
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun homeCustomizationExposesSectionAndQuickLocationControls() {
        compose.onNodeWithTag("home_customize").performClick()
        compose.onNodeWithText("Customize home").fetchSemanticsNode()
        compose.onNodeWithText("Section order").fetchSemanticsNode()
        compose.onNodeWithText("Add a file or folder shortcut").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
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

        compose.onNodeWithTag("nav_files").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.filesHomeVisible.value }
        compose.onNodeWithText("File locations").fetchSemanticsNode()
    }

    @Test
    fun returningFromAnotherBottomDestinationKeepsTheOpenFolder() {
        val directory = File(compose.activity.getExternalFilesDir(null), "bottom-nav-${System.nanoTime()}").apply { mkdirs() }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path
        try {
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                !viewModel.filesHomeVisible.value && viewModel.leftPanel.value.path == directory.canonicalPath
            }

            compose.runOnUiThread {
                viewModel.setSection(AppSection.ANALYZE)
                viewModel.setSection(AppSection.FILES)
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.section.value == AppSection.FILES }
            assertFalse(viewModel.filesHomeVisible.value)
            assertEquals(directory.canonicalPath, viewModel.leftPanel.value.path)

            compose.runOnUiThread { viewModel.setSection(AppSection.FILES) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.filesHomeVisible.value }
        } finally {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, previousPath) }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rootStorageRemainsVisibleWhenPrivilegedAccessIsOff() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread {
            viewModel.setAdvancedAccessMode(com.affilemanager.app.advanced.AdvancedAccessMode.OFF)
            viewModel.setSection(AppSection.FILES)
            viewModel.setSection(AppSection.FILES)
        }
        compose.onNodeWithTag("root_storage_location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("root_storage_location").performClick()
        compose.onNodeWithTag("root_access_explanation").assertIsDisplayed()
        assertEquals(AppSection.FILES, viewModel.section.value)
        compose.onNodeWithTag("root_access_settings").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.section.value == AppSection.TOOLS }
        compose.onNodeWithTag("tools_list").performScrollToNode(hasTestTag("advanced_mode_off"))
        compose.onNodeWithTag("advanced_mode_off").assertIsDisplayed()
    }

    @Test
    fun shareDestinationOffersWebFtpAndWebDav() {
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Share with a computer").assertIsDisplayed()
        compose.onNodeWithText("Web").assertIsDisplayed()
        compose.onNodeWithText("FTP").assertIsDisplayed()
        compose.onNodeWithText("WebDAV").assertIsDisplayed()
        compose.onNodeWithTag("sharing_list").performScrollToNode(hasText("Start sharing"))
        compose.onNodeWithText("Start sharing").assertIsDisplayed()
    }

    @Test
    fun storageOverviewUsesTheSelectedInterfaceLanguage() {
        compose.onNodeWithText("Analyze").performClick()

        compose.onNodeWithText("Storage usage").assertIsDisplayed()
        compose.onNodeWithText("Internal storage").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Vidinė atmintis").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun analysisOffersMountedStorageSelectionAndExplicitSdUsbStates() {
        compose.onNodeWithText("Analyze").performClick()

        compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("analyze_storage_sd_absent"))
        compose.onNodeWithTag("analyze_storage_sd_absent").assertIsDisplayed()
        compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("analyze_storage_usb_absent"))
        compose.onNodeWithTag("analyze_storage_usb_absent").assertIsDisplayed()
        compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("search_scope_selected"))
        compose.onNodeWithTag("search_scope_selected").performClick()
        compose.onNodeWithTag("search_storage_primary").assertIsDisplayed()
        compose.onNodeWithTag("search_storage_apply").performClick()

        compose.onNodeWithText("Selected storage locations: 1").assertIsDisplayed()
    }

    @Test
    fun analysisUsesConciseSectionsAndOpensAFullCategoryView() {
        val root = File(compose.activity.getExternalFilesDir(null), "analysis-${System.nanoTime()}").apply { mkdirs() }
        val empty = File(root, "empty").apply { mkdirs() }
        File(root, "photo.jpg").writeBytes(ByteArray(32) { it.toByte() })
        File(root, "notes.txt").apply {
            writeText("notes")
            setLastModified(1_700_000_000_000L)
        }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.analyze(root.absolutePath)
                viewModel.setSection(AppSection.ANALYZE)
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                viewModel.analysisState.value.analysis != null && !viewModel.analysisState.value.running
            }

            listOf(
                "analysis_overview_types",
                "analysis_overview_folders",
                "analysis_overview_files",
                "analysis_overview_oldest",
                "analysis_overview_empty",
                "analysis_overview_duplicates",
            ).forEach { tag ->
                compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }

            compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("analysis_overview_types_view_all"))
            compose.onNodeWithTag("analysis_overview_types_view_all").performClick()
            compose.onNodeWithTag("analysis_type_usage").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close").performClick()
        } finally {
            empty.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun storageCardOpensAHistoryBoundarySoSystemBackReturnsToFileLocations() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread {
            viewModel.activatePanel(PanelId.LEFT)
            viewModel.setSection(AppSection.FILES)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Internal storage").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            !viewModel.filesHomeVisible.value && viewModel.leftPanel.value.backHistory.isEmpty()
        }
        compose.waitForIdle()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

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
            compose.onNodeWithText("Network").performClick()
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
    fun afWorkflowCenterExposesPlansTimelineAndAutomationWithoutMixedLanguage() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { viewModel.openAfWorkflowCenter() }

        compose.onNodeWithTag("af_plans_list").fetchSemanticsNode()
        compose.onNodeWithText("AF Plans").fetchSemanticsNode()
        compose.onNodeWithText("Timeline").performClick()
        compose.onNodeWithTag("af_timeline_list").fetchSemanticsNode()
        compose.onNodeWithText("Where did my file go?").fetchSemanticsNode()
        compose.onNodeWithText("Automation").performClick()
        compose.onNodeWithTag("af_automation_list").fetchSemanticsNode()
        compose.onNodeWithText("Safe automation").fetchSemanticsNode()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !viewModel.afWorkflowUi.value.open }
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
            compose.onNodeWithTag("display_sort_name").fetchSemanticsNode()
            compose.onNodeWithTag("display_sort_ascending").fetchSemanticsNode()
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun displaySettingsApplySizeDateAndTypeSortingToTheOpenFolder() {
        val directory = File(compose.activity.getExternalFilesDir(null), "sorting-${System.nanoTime()}").apply { mkdirs() }
        val smallText = File(directory, "z.txt").apply { writeBytes(ByteArray(1)); setLastModified(200_000L) }
        val largePdf = File(directory, "a.pdf").apply { writeBytes(ByteArray(30)); setLastModified(300_000L) }
        val mediumCsv = File(directory, "m.csv").apply { writeBytes(ByteArray(10)); setLastModified(100_000L) }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.setDirectoryDisplaySettings(
                    PanelId.LEFT,
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST),
                )
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.path == directory.canonicalPath &&
                    viewModel.leftPanel.value.entries.all { it.metadataComplete }
            }

            compose.onNodeWithTag("directory_layout_local_LEFT").performTouchInput { longClick() }
            compose.onNodeWithTag("display_sort_size").performClick()
            compose.onNodeWithTag("display_sort_descending").performClick()
            compose.onNodeWithTag("display_apply").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.entries.map(FileEntry::name) == listOf(largePdf.name, mediumCsv.name, smallText.name)
            }

            compose.onNodeWithTag("directory_layout_local_LEFT").performTouchInput { longClick() }
            compose.onNodeWithTag("display_sort_modified").performClick()
            compose.onNodeWithTag("display_sort_ascending").performClick()
            compose.onNodeWithTag("display_apply").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.entries.map(FileEntry::name) == listOf(mediumCsv.name, smallText.name, largePdf.name)
            }

            compose.onNodeWithTag("directory_layout_local_LEFT").performTouchInput { longClick() }
            compose.onNodeWithTag("display_sort_type").performClick()
            compose.onNodeWithTag("display_sort_ascending").performClick()
            compose.onNodeWithTag("display_apply").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.entries.map(FileEntry::name) == listOf(mediumCsv.name, largePdf.name, smallText.name)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sizeSortingCalculatesFolderContentsAndShowsRelativeSizeBars() {
        val directory = File(compose.activity.getExternalFilesDir(null), "folder-size-${System.nanoTime()}").apply { mkdirs() }
        val smallFolder = File(directory, "small-folder").apply { mkdirs() }
        File(smallFolder, "small.bin").writeBytes(ByteArray(4))
        val largeFolder = File(directory, "large-folder").apply { mkdirs() }
        File(largeFolder, "large.bin").writeBytes(ByteArray(64))
        val rootFile = File(directory, "root.bin").apply { writeBytes(ByteArray(128)) }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.setDirectoryDisplaySettings(
                    PanelId.LEFT,
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST),
                )
                viewModel.setSort(PanelId.LEFT, com.affilemanager.app.model.SortMode.SIZE, com.affilemanager.app.model.SortDirection.DESCENDING)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.leftPanel.value
                !state.loading && state.entries.map(FileEntry::name) == listOf("large-folder", "small-folder", "root.bin")
            }

            assertEquals(listOf(64L, 4L, 128L), viewModel.leftPanel.value.entries.map(FileEntry::sizeBytes))
            compose.onNodeWithTag("file_size_bar_${largeFolder.absolutePath.hashCode()}").assertIsDisplayed()
            compose.onNodeWithTag("file_size_bar_${rootFile.absolutePath.hashCode()}").assertIsDisplayed()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localQuickSearchFiltersOnlyTheCurrentFolder() {
        val directory = File(compose.activity.getExternalFilesDir(null), "search-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "alpha.txt").writeText("alpha")
        File(directory, "beta.txt").writeText("beta")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                !viewModel.filesHomeVisible.value && viewModel.leftPanel.value.entries.size == 2
            }

            compose.onNodeWithTag("directory_search_local_LEFT").performClick()
            compose.onNodeWithTag("directory_search_field_local_LEFT").performTextInput("alpha")
            compose.onNodeWithText("alpha.txt").fetchSemanticsNode()
            assertTrue(compose.onAllNodesWithText("beta.txt").fetchSemanticsNodes().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
