package com.affilemanager.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import android.os.Build
import android.os.Environment
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.HomeToolPage
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
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    fun unifiedAfDialogUsesTheLargeBatchRenameFrame() {
        compose.onNodeWithTag("home_customize").performClick()
        val dialog = compose.onNodeWithTag("home_customization_dialog")
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        val metrics = compose.activity.resources.displayMetrics
        assertTrue(bounds.width >= metrics.widthPixels * 0.90f)
        assertTrue(bounds.height >= metrics.heightPixels * 0.80f)

        val artifact = File(requireNotNull(compose.activity.getExternalFilesDir("validation")), "af-unified-dialog.png")
        artifact.outputStream().use { output ->
            assertTrue(
                dialog.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(artifact.isFile && artifact.length() > 0L)
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun homeToolsUseDedicatedAfPagesAndSystemBackReturnsHome() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { viewModel.showFilesHome() }
        compose.onNodeWithText("Tools and security").assertIsDisplayed()

        compose.onNodeWithTag("home_tool_favorites").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.homeToolPage.value == HomeToolPage.FAVORITES }
        compose.onNodeWithTag("home_tools_page_favorites").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.homeToolPage.value == null }
        compose.onNodeWithText("File locations").assertIsDisplayed()

        compose.onNodeWithTag("home_tool_tags").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.homeToolPage.value == HomeToolPage.TAGS }
        compose.onNodeWithTag("home_tools_page_tags").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.homeToolPage.value == null }
    }

    @Test
    fun localRootOpenedFromHomeDoesNotInheritTheOldFolderHistory() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread {
            viewModel.setAdvancedAccessMode(com.affilemanager.app.advanced.AdvancedAccessMode.OFF)
            viewModel.showFilesHome()
            viewModel.openRootFromHome(PanelId.LEFT)
        }
        compose.waitUntil(timeoutMillis = 10_000) { viewModel.leftPanel.value.path == "/" }
        compose.runOnIdle { assertTrue(viewModel.leftPanel.value.backHistory.isEmpty()) }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.filesHomeVisible.value }
        compose.onNodeWithText("File locations").assertIsDisplayed()
    }

    @Test
    fun folderMenuOpensOneFavoriteLocationsWindowInsteadOfInlineShortcuts() {
        val directory = File(compose.activity.getExternalFilesDir(null), "favorite-menu-${System.nanoTime()}").apply { mkdirs() }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, directory.absolutePath, rememberHistory = false) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.path == directory.absolutePath }
            if (directory.absolutePath !in viewModel.favorites.value) {
                compose.onAllNodesWithContentDescription("Folder actions")[0].performClick()
                compose.onNodeWithText("Add to favorites").performClick()
                compose.waitUntil(timeoutMillis = 5_000) { directory.absolutePath in viewModel.favorites.value }
            }

            compose.onAllNodesWithContentDescription("Folder actions")[0].performClick()
            compose.onNodeWithTag("open_favorites_LEFT").performClick()
            compose.onNodeWithTag("favorite_locations_dialog").assertIsDisplayed()
            compose.onNodeWithTag("favorite_location_${directory.absolutePath.hashCode()}").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        } finally {
            compose.runOnUiThread {
                if (directory.absolutePath in viewModel.favorites.value) viewModel.toggleFavorite(directory.absolutePath)
            }
            directory.deleteRecursively()
        }
    }

    @Test
    fun entryThreeDotMenuOpensTagsForOnlyThatEntry() {
        val directory = File(compose.activity.getExternalFilesDir(null), "tag-menu-${System.nanoTime()}").apply { mkdirs() }
        val target = File(directory, "issue-108.txt").apply { writeText("AF") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, directory.absolutePath, rememberHistory = false) }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.leftPanel.value.entries.any { it.absolutePath == target.absolutePath }
            }

            compose.onNodeWithContentDescription("File actions: ${target.name}").performClick()
            compose.onNodeWithTag("tag_entry_action").performClick()
            val dialog = compose.onNodeWithTag("tag_dialog").assertIsDisplayed()
            val bounds = dialog.fetchSemanticsNode().boundsInRoot
            val metrics = compose.activity.resources.displayMetrics
            assertTrue(bounds.width >= metrics.widthPixels * 0.90f)
            assertTrue(bounds.height >= metrics.heightPixels * 0.80f)
            compose.runOnIdle {
                assertEquals(setOf(target.absolutePath), viewModel.leftPanel.value.selectedPaths)
            }
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            compose.runOnUiThread { viewModel.clearSelection(PanelId.LEFT) }
            directory.deleteRecursively()
        }
    }

    @Test
    fun entryAndSelectionMenusExposeCopyMoveAndFavoritesActions() {
        val directory = File(compose.activity.getExternalFilesDir(null), "issue-108-actions-${System.nanoTime()}").apply { mkdirs() }
        val first = File(directory, "first.txt").apply { writeText("one") }
        val second = File(directory, "second.txt").apply { writeText("two") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, directory.absolutePath, rememberHistory = false) }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.leftPanel.value.entries.count { it.absolutePath == first.absolutePath || it.absolutePath == second.absolutePath } == 2
            }

            compose.onNodeWithContentDescription("File actions: ${first.name}").performClick()
            compose.onNodeWithTag("copy_entry_action").assertIsDisplayed()
            compose.onNodeWithTag("move_entry_action").assertIsDisplayed()
            compose.onNodeWithTag("favorite_entry_action").assertIsDisplayed()
            compose.onNodeWithTag("copy_entry_action").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.clipboard.value?.paths == listOf(first.absolutePath) }
            compose.runOnUiThread { viewModel.clearAfClipboard() }

            compose.onNodeWithContentDescription("File actions: ${first.name}").performClick()
            compose.onNodeWithTag("favorite_entry_action").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { first.absolutePath in viewModel.favorites.value }
            compose.runOnUiThread {
                viewModel.toggleFavorite(first.absolutePath)
                viewModel.selectPaths(PanelId.LEFT, listOf(first.absolutePath, second.absolutePath))
            }
            compose.onNodeWithTag("selection_favorite_local").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                first.absolutePath in viewModel.favorites.value && second.absolutePath in viewModel.favorites.value
            }
        } finally {
            compose.runOnUiThread {
                viewModel.clearAfClipboard()
                viewModel.clearSelection(PanelId.LEFT)
                listOf(first, second).forEach { file ->
                    if (file.absolutePath in viewModel.favorites.value) viewModel.toggleFavorite(file.absolutePath)
                }
            }
            directory.deleteRecursively()
        }
    }

    @Test
    fun createDialogUsesTheSharedLargeAfFrame() {
        val directory = File(compose.activity.getExternalFilesDir(null), "issue-106-create-${System.nanoTime()}").apply { mkdirs() }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, directory.absolutePath, rememberHistory = false) }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.leftPanel.value.path == directory.absolutePath }
            compose.onAllNodesWithTag("create_local_item")[0].performClick()
            val dialog = compose.onNodeWithTag("create_item_dialog").assertIsDisplayed()
            val bounds = dialog.fetchSemanticsNode().boundsInRoot
            val metrics = compose.activity.resources.displayMetrics
            assertTrue(bounds.width >= metrics.widthPixels * 0.90f)
            assertTrue(bounds.height >= metrics.heightPixels * 0.80f)
            val artifact = File(requireNotNull(compose.activity.getExternalFilesDir("validation")), "issue-106-create-dialog.png")
            artifact.outputStream().use { output ->
                assertTrue(dialog.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            compose.onNodeWithText("Close").performClick()
        } finally {
            directory.deleteRecursively()
        }
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
    fun rootStorageUsesTheNormalBrowserWhenPrivilegedAccessIsOff() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread {
            viewModel.setAdvancedAccessMode(com.affilemanager.app.advanced.AdvancedAccessMode.OFF)
            viewModel.setSection(AppSection.FILES)
            viewModel.setSection(AppSection.FILES)
        }
        compose.onNodeWithTag("root_storage_location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("root_storage_location").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            !viewModel.filesHomeVisible.value && viewModel.leftPanel.value.path == File("/").canonicalPath
        }
        compose.waitUntil(timeoutMillis = 10_000) { !viewModel.leftPanel.value.loading }
        assertTrue(compose.onAllNodesWithTag("root_access_explanation").fetchSemanticsNodes().isEmpty())
        assertEquals(AppSection.FILES, viewModel.section.value)
        assertEquals(null, viewModel.leftPanel.value.error)
        assertTrue("Root listing is empty", viewModel.leftPanel.value.entries.isNotEmpty())
        compose.onNodeWithTag("directory_toolbar_local_LEFT").assertIsDisplayed()
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
    fun analysisOffersMountedStorageSelectionWithoutDisconnectedPlaceholders() {
        compose.onNodeWithText("Analyze").performClick()

        compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("analyze_all_storage"))
        compose.onNodeWithTag("analyze_all_storage").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("analyze_storage_sd_absent").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("analyze_storage_usb_absent").fetchSemanticsNodes().isEmpty())
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
            compose.onNodeWithTag("change_language").performClick()
            compose.onNodeWithTag("language_search").performTextInput("Arabic")
            compose.onNodeWithTag("language_option_ar").assertIsDisplayed()
            compose.onNodeWithTag("language_search").performTextReplacement("Lithuanian")
            compose.onNodeWithTag("language_option_lt").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Daugiau").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Daugiau").performClick()
            compose.onNodeWithText("Kalba").fetchSemanticsNode()
            compose.onNodeWithText("Įrankiai ir saugumas").fetchSemanticsNode()
            compose.onNodeWithTag("change_language").performClick()
            compose.onNodeWithTag("language_search").performTextInput("English")
            compose.onNodeWithTag("language_option_en").performClick()

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
            compose.onNodeWithTag("palette_material_blue").performScrollTo().performClick()
            compose.onNodeWithTag("amoled_black").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                val settings = viewModel.appearanceSettings.value
                settings.themeMode == AppThemeMode.DARK &&
                    settings.colorPalette == AppColorPalette.MATERIAL_BLUE &&
                    settings.amoledBlack
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val artifact = File(requireNotNull(compose.activity.getExternalFilesDir("validation")), "material-blue-palette.png")
            artifact.outputStream().use { output ->
                assertTrue(
                    compose.onNodeWithTag("palette_material_blue", useUnmergedTree = true)
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
            assertTrue(artifact.isFile && artifact.length() > 0)
            val screenArtifact = File(requireNotNull(compose.activity.getExternalFilesDir("validation")), "material-blue-navigation.png")
            screenArtifact.outputStream().use { output ->
                assertTrue(
                    compose.onRoot(useUnmergedTree = true)
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
            assertTrue(screenArtifact.isFile && screenArtifact.length() > 0)
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
        assertTrue(compose.onAllNodesWithText("AF Plans").fetchSemanticsNodes().isNotEmpty())
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
    fun homeLayoutButtonTogglesAndLongPressOpensOneToThreeColumnSettings() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.setFilesHomeDisplaySettings(
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, gridColumns = 2),
                )
            }
            compose.onNodeWithTag("home_layout_toggle").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.filesHomeDisplaySettings.value.layoutMode == DirectoryLayoutMode.GRID
            }

            compose.onNodeWithTag("home_layout_toggle").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("display_settings_dialog").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_1").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_2").fetchSemanticsNode()
            compose.onNodeWithTag("display_grid_columns_3").performClick()
            compose.onNodeWithTag("display_apply").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.filesHomeDisplaySettings.value.gridColumns == 3
            }
        } finally {
            compose.runOnUiThread {
                viewModel.setFilesHomeDisplaySettings(
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, gridColumns = 2),
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

    @Test
    fun createDialogShowsOneRealSelectionAndCreatesAnEmptyArchive() {
        val directory = File(compose.activity.getExternalFilesDir(null), "create-${System.nanoTime()}").apply { mkdirs() }
        val archiveBaseName = "empty-${System.nanoTime()}"
        val archive = File(directory, "$archiveBaseName.zip")
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
                viewModel.leftPanel.value.path == directory.canonicalPath && !viewModel.leftPanel.value.loading
            }

            compose.onAllNodesWithTag("create_local_item")[0].performClick()
            compose.onNodeWithTag("create_type_folder").assertIsSelected()
            compose.onNodeWithTag("create_type_file").assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithTag("create_type_folder").assertIsNotSelected()
            compose.onNodeWithTag("create_type_archive").assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithTag("create_type_file").assertIsNotSelected()
            compose.onNodeWithTag("create_archive_format_ZIP").assertIsSelected()
            compose.onNodeWithTag("create_item_name").performTextInput(archiveBaseName)
            compose.onNodeWithText("Create").performClick()

            compose.waitUntil(timeoutMillis = 10_000) { archive.isFile }
            ZipFile(archive).use { zip -> assertFalse(zip.entries().hasMoreElements()) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun archiveEntryMenuNamesAndOpensTheArchiveActionExplicitly() {
        val directory = File(compose.activity.getExternalFilesDir(null), "archive-menu-${System.nanoTime()}").apply { mkdirs() }
        val archive = File(directory, "sample.zip")
        ZipOutputStream(archive.outputStream()).use { }
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
                viewModel.leftPanel.value.entries.any { it.absolutePath == archive.absolutePath }
            }

            compose.onNodeWithContentDescription("File actions: sample.zip").performClick()
            compose.onNodeWithText("Open archive").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.preview.value != null }
        } finally {
            compose.runOnUiThread { viewModel.closePreview() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun selectedFilesAndFoldersExposeAggregateInformation() {
        val directory = File(compose.activity.getExternalFilesDir(null), "info-${System.nanoTime()}").apply { mkdirs() }
        val folder = File(directory, "folder").apply { mkdirs() }
        File(folder, "nested.txt").writeText("nested")
        val file = File(directory, "direct.txt").apply { writeText("direct") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.entries.size == 2 }
            compose.runOnUiThread {
                viewModel.selectPaths(PanelId.LEFT, listOf(folder.absolutePath, file.absolutePath))
            }

            compose.onNodeWithTag("selection_info_local").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("file_info_loading").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("file_info_files").assertIsDisplayed()
            compose.onNodeWithTag("file_info_folders").assertIsDisplayed()
            compose.onNodeWithTag("file_info_size").assertIsDisplayed()
            compose.onNodeWithText("Selected").assertIsDisplayed()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun analyzeSelectedIncludesFilesAndDoesNotRequireAFolder() {
        val directory = File(compose.activity.getExternalFilesDir(null), "analyze-selected-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "only.pdf").apply { writeBytes(ByteArray(73)) }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.entries.any { it.absolutePath == file.absolutePath }
            }
            compose.runOnUiThread { viewModel.selectPaths(PanelId.LEFT, listOf(file.absolutePath)) }

            compose.onNodeWithTag("analyze_selection_local").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                viewModel.section.value == AppSection.ANALYZE && !viewModel.analysisState.value.running
            }
            val analysis = requireNotNull(viewModel.analysisState.value.analysis)
            assertEquals(1, analysis.scannedFiles)
            assertEquals(73L, analysis.totalBytes)
            assertEquals(listOf(file.canonicalPath), viewModel.analysisState.value.rootPaths)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun folderMenuAnalyzesThatFolderDirectly() {
        val directory = File(compose.activity.getExternalFilesDir(null), "analyze-menu-${System.nanoTime()}").apply { mkdirs() }
        val folder = File(directory, "target-folder").apply { mkdirs() }
        File(folder, "inside.txt").writeText("inside")
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
                viewModel.leftPanel.value.entries.any { it.absolutePath == folder.absolutePath }
            }

            compose.onNodeWithContentDescription("File actions: target-folder").performClick()
            compose.onNodeWithTag("analyze_entry_action").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                viewModel.section.value == AppSection.ANALYZE && !viewModel.analysisState.value.running
            }
            assertEquals(listOf(folder.canonicalPath), viewModel.analysisState.value.rootPaths)
            assertEquals(1, requireNotNull(viewModel.analysisState.value.analysis).scannedFiles)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun addedPalettesAreSelectableRatherThanForced() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.onNodeWithText("More").performClick()
            listOf(
                "palette_aura" to AppColorPalette.AURA,
                "palette_tokyo" to AppColorPalette.TOKYO,
                "palette_yin_yang" to AppColorPalette.YIN_YANG,
            ).forEach { (tag, palette) ->
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { viewModel.appearanceSettings.value.colorPalette == palette }
            }
        } finally {
            compose.runOnUiThread { viewModel.setColorPalette(AppColorPalette.DEFAULT) }
        }
    }

    @Test
    fun dynamicPaletteUpdatesNavigationBarColorAndIconContrast() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.setThemeMode(AppThemeMode.LIGHT)
                viewModel.setColorPalette(AppColorPalette.DYNAMIC)
            }
            val expectedLight = dynamicLightColorScheme(compose.activity).surface.toArgb()
            compose.waitUntil(timeoutMillis = 5_000) {
                val settings = viewModel.appearanceSettings.value
                settings.themeMode == AppThemeMode.LIGHT && settings.colorPalette == AppColorPalette.DYNAMIC
            }
            compose.waitForIdle()
            compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().let { bitmap ->
                assertEquals(expectedLight, bitmap.getPixel(bitmap.width / 2, bitmap.height - 2))
            }
            assertTrue(
                WindowCompat.getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView,
                ).isAppearanceLightNavigationBars,
            )

            compose.runOnUiThread { viewModel.setThemeMode(AppThemeMode.DARK) }
            val expectedDark = dynamicDarkColorScheme(compose.activity).surface.toArgb()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.appearanceSettings.value.themeMode == AppThemeMode.DARK
            }
            compose.waitForIdle()
            compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().let { bitmap ->
                assertEquals(expectedDark, bitmap.getPixel(bitmap.width / 2, bitmap.height - 2))
            }
            assertFalse(
                WindowCompat.getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView,
                ).isAppearanceLightNavigationBars,
            )
        } finally {
            compose.runOnUiThread {
                viewModel.setThemeMode(AppThemeMode.SYSTEM)
                viewModel.setColorPalette(AppColorPalette.DEFAULT)
            }
        }
    }

    @Test
    fun localLongPressDragSelectsEveryCrossedEntry() {
        val directory = File(compose.activity.getExternalFilesDir(null), "drag-select-${System.nanoTime()}").apply { mkdirs() }
        val files = (0..4).map { index -> File(directory, "file-$index.txt").apply { writeText(index.toString()) } }
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
                viewModel.leftPanel.value.entries.map(FileEntry::absolutePath) == files.map(File::getAbsolutePath)
            }

            val listBounds = compose.onNodeWithTag("file_list_LEFT").fetchSemanticsNode().boundsInRoot
            val firstBounds = compose.onNodeWithTag("local_entry_${files[0].absolutePath}").fetchSemanticsNode().boundsInRoot
            val thirdBounds = compose.onNodeWithTag("local_entry_${files[2].absolutePath}").fetchSemanticsNode().boundsInRoot
            val start = Offset(firstBounds.center.x - listBounds.left, firstBounds.center.y - listBounds.top)
            val end = Offset(thirdBounds.center.x - listBounds.left, thirdBounds.center.y - listBounds.top)

            compose.onNodeWithTag("file_list_LEFT").performTouchInput {
                down(start)
                advanceEventTime(650)
                moveTo(end)
                advanceEventTime(100)
                up()
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.leftPanel.value.selectedPaths == files.take(3).map(File::getAbsolutePath).toSet()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupFilePreviewReturnsToTheStillOpenCleanupManager() {
        val directory = File(compose.activity.getExternalFilesDir(null), "cleanup-preview-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "notes.txt").writeText("preview me")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.analyze(directory.absolutePath)
                viewModel.setSection(AppSection.ANALYZE)
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                viewModel.analysisState.value.analysis != null && !viewModel.analysisState.value.running
            }
            compose.onNodeWithTag("analyze_list").performScrollToNode(hasTestTag("open_cleanup_review"))
            compose.onNodeWithTag("open_cleanup_review").performClick()
            compose.onNodeWithTag("cleanup_review_dialog").assertIsDisplayed()

            compose.onNodeWithTag("cleanup_candidate_card").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.preview.value != null }
            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("cleanup_review_dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("cleanup_review_dialog").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.closePreview() }
            directory.deleteRecursively()
        }
    }
}
