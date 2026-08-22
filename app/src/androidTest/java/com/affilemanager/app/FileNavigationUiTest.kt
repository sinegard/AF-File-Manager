package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileNavigationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun newLocationStartsAtTopWhileHistoryRestoresEachPosition() {
        val parent = File(compose.activity.getExternalFilesDir(null), "scroll-${System.nanoTime()}").apply { mkdirs() }
        val firstDirectory = File(parent, "first").apply { mkdirs() }
        val secondDirectory = File(parent, "second").apply { mkdirs() }
        repeat(60) { index -> File(firstDirectory, "a-${index.toString().padStart(3, '0')}.txt").writeText("a") }
        repeat(60) { index -> File(secondDirectory, "b-${index.toString().padStart(3, '0')}.txt").writeText("b") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path
        val tabId = viewModel.leftTabs.value.activeTabId
        val firstScrollKey = FileScrollKey(tabId, firstDirectory.canonicalPath, grid = false)
        val secondScrollKey = FileScrollKey(tabId, secondDirectory.canonicalPath, grid = false)

        try {
            navigateAndWait(viewModel, firstDirectory)
            compose.onNodeWithTag("file_list_LEFT").performScrollToIndex(35)
            compose.onNodeWithText("a-035.txt").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.fileScrollPosition(firstScrollKey).firstVisibleItemIndex > 0
            }

            navigateAndWait(viewModel, secondDirectory)
            compose.onNodeWithText("b-000.txt").assertIsDisplayed()
            compose.onNodeWithTag("file_list_LEFT").performScrollToIndex(28)
            compose.onNodeWithText("b-028.txt").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.fileScrollPosition(secondScrollKey).firstVisibleItemIndex > 0
            }

            compose.runOnUiThread { viewModel.navigateBack(PanelId.LEFT) }
            waitForPathAndListing(viewModel, firstDirectory)
            assertTrue(
                "Pirmo katalogo pozicija registre prarasta: ${viewModel.fileScrollPosition(firstScrollKey)}",
                viewModel.fileScrollPosition(firstScrollKey).firstVisibleItemIndex > 0,
            )
            waitUntilDisplayed("a-035.txt")

            compose.runOnUiThread { viewModel.navigateForward(PanelId.LEFT) }
            waitForPathAndListing(viewModel, secondDirectory)
            waitUntilDisplayed("b-028.txt")

            compose.runOnUiThread { viewModel.navigateBack(PanelId.LEFT) }
            waitForPathAndListing(viewModel, firstDirectory)
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, secondDirectory.absolutePath) }
            waitForPathAndListing(viewModel, secondDirectory)
            compose.onNodeWithText("b-000.txt").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, previousPath) }
            compose.waitForIdle()
            parent.deleteRecursively()
        }
    }

    @Test
    fun selectAllCheckboxTogglesAllSelectionOffOnSecondPress() {
        val directory = File(compose.activity.getExternalFilesDir(null), "select-${System.nanoTime()}").apply { mkdirs() }
        val files = List(3) { index -> File(directory, "choice-$index.txt").apply { writeText("$index") } }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path

        try {
            navigateAndWait(viewModel, directory)
            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, files.first().canonicalPath) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.selectedPaths.size == 1 }

            compose.onNodeWithContentDescription("Select all").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.selectedPaths.size == files.size }
            compose.onNodeWithContentDescription("Deselect all").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.selectedPaths.isEmpty() }

            assertEquals(emptySet<String>(), viewModel.leftPanel.value.selectedPaths)
        } finally {
            compose.runOnUiThread {
                viewModel.clearSelection(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, previousPath)
            }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun copyMoreAppendsMissedItemsWithoutReplacingTheClipboard() {
        val directory = File(compose.activity.getExternalFilesDir(null), "copy-more-${System.nanoTime()}").apply { mkdirs() }
        val first = File(directory, "first.txt").apply { writeText("first") }
        val missed = File(directory, "missed.txt").apply { writeText("missed") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path

        try {
            navigateAndWait(viewModel, directory)
            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, first.canonicalPath) }
            compose.onNodeWithContentDescription("Copy").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.clipboard.value?.paths == listOf(first.canonicalPath)
            }

            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, missed.canonicalPath) }
            compose.onNodeWithTag("copy-more-local").assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.clipboard.value?.paths == listOf(first.canonicalPath, missed.canonicalPath)
            }

            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, first.canonicalPath) }
            compose.onNodeWithTag("copy-more-local").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.clipboard.value?.paths == listOf(first.canonicalPath, missed.canonicalPath)
            }
        } finally {
            compose.runOnUiThread {
                viewModel.clearSelection(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, previousPath)
            }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun quickLocationFolderUsesTheStandardLocalBrowserAndControls() {
        val directory = File(compose.activity.getExternalFilesDir(null), "quick-location-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "visible.txt").writeText("visible")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path

        try {
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.openHomeShortcut("builtin.downloads", directory.absolutePath, PanelId.LEFT)
            }
            waitForPathAndListing(viewModel, directory)

            assertTrue(!viewModel.fileCategory.value.open)
            compose.onNodeWithTag("directory_search_local_LEFT").assertIsDisplayed()
            compose.onNodeWithTag("directory_layout_local_LEFT").assertIsDisplayed()
            compose.onNodeWithText("visible.txt").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.navigate(PanelId.LEFT, previousPath) }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun mediaArchivesAndAppsUseTheSharedBrowserChrome() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        listOf(
            "builtin.documents" to FileCategory.DOCUMENTS,
            "builtin.pictures" to FileCategory.IMAGES,
            "builtin.videos" to FileCategory.VIDEOS,
            "builtin.music" to FileCategory.AUDIO,
            "builtin.archives" to FileCategory.ARCHIVES,
            "builtin.apps" to FileCategory.APPS,
            "builtin.installed_apps" to FileCategory.INSTALLED_APPS,
        ).forEach { (shortcutId, expectedCategory) ->
            compose.runOnUiThread { viewModel.setSection(AppSection.FILES) }
            compose.onNodeWithTag("quick_location_$shortcutId").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.fileCategory.value
                state.open && state.category == expectedCategory && !state.loading
            }
            compose.waitForIdle()
            assertEquals(
                "Quick location $shortcutId opened the wrong browser state: ${viewModel.fileCategory.value}",
                expectedCategory,
                viewModel.fileCategory.value.category,
            )
            assertTrue(viewModel.fileCategory.value.open)
            assertEquals(null, viewModel.fileCategory.value.error)

            compose.onNodeWithTag("panel_tabs_${viewModel.activePanel.value}").assertIsDisplayed()
            compose.onNodeWithTag("directory_toolbar_category").assertIsDisplayed()
            compose.onNodeWithTag("directory_search_category").assertIsDisplayed()
            compose.onNodeWithTag("directory_layout_category").assertIsDisplayed()
            compose.onNodeWithText("Files").assertIsDisplayed()

            compose.onNodeWithText("Files").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.fileCategory.value.open }
        }
    }

    @Test
    fun systemBackClosesFileOverlaysWithoutChangingTheirOriginSection() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.openTrashFromHome()
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.trashBrowser.value.open }
            compose.onNodeWithTag("trash-browser-dialog").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.trashBrowser.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)
            assertTrue(viewModel.filesHomeVisible.value)

            compose.runOnUiThread { viewModel.openFileCategory(FileCategory.ARCHIVES) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.fileCategory.value.open }
            compose.onNodeWithTag("directory_toolbar_category").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.fileCategory.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)

            compose.runOnUiThread { viewModel.setSection(AppSection.SHARE) }
            compose.onNodeWithText("Browse folders").performScrollTo().performClick()
            compose.onNodeWithTag("share_folder_picker").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag("share_folder_picker").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(AppSection.SHARE, viewModel.section.value)
        } finally {
            compose.runOnUiThread {
                viewModel.closeTrashBrowser()
                viewModel.closeFileCategory()
                viewModel.setSection(AppSection.FILES)
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun rootStorageOpensAsAFileOverlayAndBackReturnsToFiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afRoot") == "true")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.ROOT)
                viewModel.requestRootAccess()
            }
            compose.waitUntil(timeoutMillis = 20_000) {
                val state = viewModel.advancedAccess.value
                state.activeBackend == AdvancedAccessBackend.ROOT || (!state.connecting && state.error != null)
            }
            assertEquals(
                "Root backend did not connect: ${viewModel.advancedAccess.value}",
                AdvancedAccessBackend.ROOT,
                viewModel.advancedAccess.value.activeBackend,
            )
            compose.runOnUiThread { viewModel.openAdvancedBrowser("/") }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == "/" && state.error == null
            }
            compose.onNodeWithTag("directory_toolbar_advanced").assertIsDisplayed()

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.advancedBrowser.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)
            assertTrue(viewModel.filesHomeVisible.value)
        } finally {
            compose.runOnUiThread {
                viewModel.closeAdvancedBrowser()
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF)
                viewModel.setSection(AppSection.FILES)
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun shizukuRootOpensProtectedStorageAndBackReturnsToFiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU)
                viewModel.requestShizukuAccess()
            }
            compose.waitUntil(timeoutMillis = 20_000) {
                val state = viewModel.advancedAccess.value
                state.activeBackend == AdvancedAccessBackend.SHIZUKU_ROOT || (!state.connecting && state.error != null)
            }
            assertEquals(
                "Shizuku root backend did not connect: ${viewModel.advancedAccess.value}",
                AdvancedAccessBackend.SHIZUKU_ROOT,
                viewModel.advancedAccess.value.activeBackend,
            )
            compose.runOnUiThread { viewModel.openAdvancedBrowser("/storage/emulated/0/Android/data") }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path.endsWith("/Android/data") && state.error == null
            }
            compose.onNodeWithTag("directory_toolbar_advanced").assertIsDisplayed()

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.advancedBrowser.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)
            assertTrue(viewModel.filesHomeVisible.value)
        } finally {
            compose.runOnUiThread {
                viewModel.closeAdvancedBrowser()
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF)
                viewModel.setSection(AppSection.FILES)
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun newTabStartsAtTopAndReturningToTheExistingTabRestoresItsPosition() {
        val directory = File(compose.activity.getExternalFilesDir(null), "tabs-${System.nanoTime()}").apply { mkdirs() }
        repeat(60) { index -> File(directory, "tab-${index.toString().padStart(3, '0')}.txt").writeText("tab") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousPath = viewModel.leftPanel.value.path
        val originalTabId = viewModel.leftTabs.value.activeTabId
        val originalScrollKey = FileScrollKey(originalTabId, directory.canonicalPath, grid = false)
        var newTabId: String? = null

        try {
            navigateAndWait(viewModel, directory)
            compose.onNodeWithTag("file_list_LEFT").performScrollToIndex(32)
            compose.onNodeWithText("tab-032.txt").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.fileScrollPosition(originalScrollKey).firstVisibleItemIndex > 0
            }

            compose.runOnUiThread { viewModel.newTab(PanelId.LEFT) }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.leftTabs.value.activeTabId != originalTabId &&
                    viewModel.leftPanel.value.path == directory.canonicalPath &&
                    !viewModel.leftPanel.value.loading &&
                    viewModel.leftPanel.value.entries.size == 60
            }
            newTabId = viewModel.leftTabs.value.activeTabId
            compose.onNodeWithText("tab-000.txt").assertIsDisplayed()

            compose.runOnUiThread { viewModel.activateTab(PanelId.LEFT, originalTabId) }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.leftTabs.value.activeTabId == originalTabId &&
                    !viewModel.leftPanel.value.loading &&
                    viewModel.leftPanel.value.entries.size == 60
            }
            waitUntilDisplayed("tab-032.txt")
        } finally {
            compose.runOnUiThread {
                newTabId?.takeIf { id -> viewModel.leftTabs.value.tabs.any { it.id == id } }?.let { id ->
                    viewModel.activateTab(PanelId.LEFT, id)
                    viewModel.closeActiveTab(PanelId.LEFT)
                }
                viewModel.navigate(PanelId.LEFT, previousPath)
            }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

    private fun navigateAndWait(viewModel: MainViewModel, directory: File) {
        compose.runOnUiThread {
            viewModel.activatePanel(PanelId.LEFT)
            viewModel.navigate(PanelId.LEFT, directory.absolutePath)
        }
        waitForPathAndListing(viewModel, directory)
    }

    private fun waitForPathAndListing(viewModel: MainViewModel, directory: File) {
        val canonicalPath = directory.canonicalPath
        val expectedEntries = directory.listFiles()?.size ?: 0
        compose.waitUntil(timeoutMillis = 10_000) {
            val state = viewModel.leftPanel.value
            state.path == canonicalPath && !state.loading && state.error == null && state.entries.size == expectedEntries
        }
        compose.waitForIdle()
    }

    private fun waitUntilDisplayed(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }
}
