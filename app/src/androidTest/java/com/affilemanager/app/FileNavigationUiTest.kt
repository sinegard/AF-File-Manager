package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun renameKeepsListViewportStable() = renameKeepsViewport(false)
    @Test fun renameKeepsGridViewportStable() = renameKeepsViewport(true)

    @Test fun wideScreenDualPaneCanBeDisabledAndGridColumnsFollowOrientation() {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true)) { "Owned emulator required" }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val app = compose.activity.application as AFFileManagerApplication
        val before = app.graph.appearance.settings.value
        val root = File(compose.activity.getExternalFilesDir(null), "orientation-${System.nanoTime()}").apply { mkdirs() }
        repeat(24) { File(root, "layout-$it.txt").writeText("$it") }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        try {
            navigateAndWait(vm, root)
            compose.runOnUiThread {
                vm.setInterfaceScalePercent(100)
                vm.setAutomaticDualPane(true)
                vm.setDirectoryDisplaySettings(PanelId.LEFT, DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.GRID,
                    gridColumns = 3, gridColumnsLandscape = 7))
                vm.navigate(PanelId.RIGHT, root.path)
            }
            shell("wm size 1920x1080")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("file_grid_LEFT").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("file_grid_RIGHT").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { vm.setAutomaticDualPane(false) }
            compose.onNodeWithTag("file_grid_RIGHT").assertDoesNotExist()
            compose.onNodeWithTag("file_grid_LEFT").assertIsDisplayed()
            fun renderedColumns(): Int {
                val viewport = compose.onNodeWithTag("file_grid_LEFT").fetchSemanticsNode().boundsInRoot
                val matcher = androidx.compose.ui.test.SemanticsMatcher("Owned fixture tiles") { node ->
                    node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("local_entry_${root.canonicalPath}/")
                }
                return compose.onAllNodes(matcher).fetchSemanticsNodes().map { it.boundsInRoot }
                    .filter { it.height > 0 && it.top >= viewport.top && it.top < viewport.bottom }
                    .groupBy { it.top.toInt() }.values.maxOfOrNull { row -> row.map { it.left.toInt() }.distinct().size } ?: 0
            }
            compose.waitUntil(10_000) { renderedColumns() == 7 }
            assertEquals(7, renderedColumns())
            shell("wm size 640x1280")
            compose.waitUntil(10_000) { runCatching { renderedColumns() == 3 }.getOrDefault(false) }
            assertEquals(7, vm.leftPanel.value.gridColumnsLandscape)
        } finally {
            shell(if (originalSize == null) "wm size reset" else "wm size $originalSize")
            compose.runOnUiThread {
                vm.setAutomaticDualPane(before.automaticDualPane)
                vm.setInterfaceScalePercent(before.interfaceScalePercent)
                vm.navigate(PanelId.LEFT, requireNotNull(root.parentFile).path)
                vm.navigate(PanelId.RIGHT, requireNotNull(root.parentFile).path)
            }
            root.deleteRecursively()
        }
    }

    private fun renameKeepsViewport(grid: Boolean) {
        val directory = File(compose.activity.getExternalFilesDir(null), "rename-scroll-${System.nanoTime()}").apply { mkdirs() }
        repeat(1200) { File(directory, "item-${it.toString().padStart(4, '0')}.txt").writeText("$it") }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = vm.leftPanel.value.path
        try {
            navigateAndWait(vm, directory)
            compose.runOnUiThread { vm.setDirectoryDisplaySettings(PanelId.LEFT,
                DirectoryDisplaySettings(layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST)) }
            compose.waitForIdle()
            compose.onNodeWithTag(if (grid) "file_grid_LEFT" else "file_list_LEFT").performScrollToIndex(600)
            val anchor = compose.onNodeWithText("item-0600.txt")
            anchor.assertIsDisplayed()
            val before = anchor.fetchSemanticsNode().boundsInRoot
            compose.runOnUiThread { vm.rename(PanelId.LEFT, File(directory, "item-1199.txt").path, "zzz-renamed.txt") }
            compose.waitUntil(15_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.any { it.name == "zzz-renamed.txt" } }
            compose.waitForIdle()
            anchor.assertIsDisplayed()
            val after = anchor.fetchSemanticsNode().boundsInRoot
            assertTrue("Rename moved viewport: $before -> $after", kotlin.math.abs(before.top - after.top) <= 2f)
        } finally {
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, previous) }
            compose.waitForIdle()
            directory.deleteRecursively()
        }
    }

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
            compose.runOnUiThread {
                viewModel.setDirectoryDisplaySettings(
                    PanelId.LEFT,
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST),
                )
            }
            compose.waitForIdle()
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
            compose.waitForIdle()
            assertEquals(listOf(first.canonicalPath), viewModel.clipboard.value?.paths)

            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, missed.canonicalPath) }
            assertEquals(setOf(missed.canonicalPath), viewModel.leftPanel.value.selectedPaths)
            compose.onNodeWithTag("copy-more-local").assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(emptySet<String>(), viewModel.leftPanel.value.selectedPaths)
            assertEquals(listOf(first.canonicalPath, missed.canonicalPath), viewModel.clipboard.value?.paths)

            compose.runOnUiThread { viewModel.toggleSelection(PanelId.LEFT, first.canonicalPath) }
            compose.onNodeWithTag("copy-more-local").performClick()
            compose.waitForIdle()
            assertEquals(listOf(first.canonicalPath, missed.canonicalPath), viewModel.clipboard.value?.paths)
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
            compose.onNodeWithTag("nav_files").assertIsDisplayed()

            compose.onNodeWithTag("nav_files").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.fileCategory.value.open }
        }
    }

    @Test
    fun installedAppsSelectionOffersInfoBackupUninstallAndSystemFilter() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.openFileCategory(FileCategory.INSTALLED_APPS, forceRefresh = true) }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.fileCategory.value
                state.open && !state.loading && state.entries.isNotEmpty()
            }

            compose.onNodeWithTag("installed_apps_scope_ALL").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.fileCategory.value.showSystemApps && !viewModel.fileCategory.value.loading
            }
            val entry = viewModel.fileCategory.value.entries.first()
            compose.runOnUiThread { viewModel.toggleFileCategorySelection(entry.absolutePath) }

            compose.onNodeWithTag("category_info").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("installed_app_information").assertIsDisplayed()
            compose.onNodeWithTag("system_app_information").assertIsDisplayed().assertHasClickAction()
            compose.runOnUiThread { viewModel.clearFileCategorySelection() }
            pressSystemBack()
            compose.waitForIdle()

            compose.runOnUiThread { viewModel.toggleFileCategorySelection(entry.absolutePath) }
            compose.onNodeWithTag("installed_apps_export").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("installed_apps_uninstall").performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.closeFileCategory() }
        }
    }

    @Test
    fun categorySelectionKeepsItsVisibleRowInPlace() = categorySelectionKeepsRow(false)

    @Test
    fun categoryGridSelectionKeepsItsVisibleRowInPlace() = categorySelectionKeepsRow(true)

    private fun categorySelectionKeepsRow(grid: Boolean) {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        var previousGrid: Boolean? = null
        try {
            compose.runOnUiThread { viewModel.openFileCategory(FileCategory.INSTALLED_APPS, forceRefresh = true) }
            compose.waitUntil(10_000) {
                val state = viewModel.fileCategory.value
                state.open && !state.loading && state.entries.isNotEmpty()
            }
            previousGrid = viewModel.fileCategory.value.grid
            compose.runOnUiThread { if (viewModel.fileCategory.value.grid != grid) viewModel.toggleFileCategoryLayout() }
            compose.waitForIdle()
            val entries = viewModel.fileCategory.value.entries
            val visibleEntries = entries.filter { candidate ->
                entries.count { it.name == candidate.name } == 1 &&
                    compose.onAllNodesWithText(candidate.name).fetchSemanticsNodes().size == 1 &&
                    compose.onNodeWithText(candidate.name).isDisplayed()
            }
            assertTrue("Owned installed-app fixture needs two visible unique rows", visibleEntries.size >= 2)
            val entry = visibleEntries[1]
            val row = compose.onNodeWithText(entry.name)
            row.assertIsDisplayed()
            val before = row.fetchSemanticsNode().boundsInRoot.top
            val validation = uiAuditDirectory(compose.activity)
            fun snapshot(name: String) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                try {
                    java.io.FileOutputStream(File(validation, name)).use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
            snapshot("category-$grid-selection-before.png")
            compose.runOnUiThread { viewModel.toggleFileCategorySelection(entry.absolutePath) }
            compose.waitForIdle()
            snapshot("category-$grid-selection-after.png")
            val during = row.fetchSemanticsNode().boundsInRoot.top
            assertEquals("Category selection moved the file row under the held pointer", before, during, 1f)
            compose.runOnUiThread { viewModel.clearFileCategorySelection() }
            compose.waitForIdle()
            assertEquals("Closing selection moved the category row", before, row.fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally { compose.runOnUiThread {
            viewModel.clearFileCategorySelection()
            if (previousGrid != null && viewModel.fileCategory.value.grid != previousGrid) viewModel.toggleFileCategoryLayout()
            viewModel.closeFileCategory()
        } }
    }

    @Test
    fun privilegedListSelectionKeepsItsVisibleRowInPlace() = privilegedSelectionKeepsRow(false)

    @Test
    fun privilegedGridSelectionKeepsItsVisibleRowInPlace() = privilegedSelectionKeepsRow(true)

    private fun privilegedSelectionKeepsRow(grid: Boolean) {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val fixture = File(compose.activity.getExternalFilesDir(null), "selection-position-${System.nanoTime()}").apply { mkdirs() }
        repeat(16) { File(fixture, "position-${it.toString().padStart(2, '0')}.txt").writeText("owned fixture $it") }
        try {
            connectRootBackend(vm)
            compose.runOnUiThread { vm.openAdvancedBrowser(fixture.canonicalPath) }
            compose.waitUntil(10_000) {
                vm.advancedBrowser.value.let { it.open && !it.loading && it.entries.size == 16 && it.error == null }
            }
            compose.runOnUiThread { if (vm.advancedBrowser.value.grid != grid) vm.toggleAdvancedLayout() }
            compose.waitForIdle()
            val entry = vm.advancedBrowser.value.entries.first()
            val row = compose.onNodeWithText(entry.name).assertIsDisplayed()
            val before = row.fetchSemanticsNode().boundsInRoot.top
            fun capture(phase: String) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(250, 5000)
                val frame = requireNotNull(automation.takeScreenshot())
                try { File(uiAuditDirectory(compose.activity), "privileged-$grid-$phase.png").outputStream().use {
                    check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { frame.recycle() }
            }
            capture("before")
            compose.runOnUiThread { vm.toggleAdvancedSelection(entry.absolutePath) }
            compose.onNodeWithTag("advanced_selection_bar").assertIsDisplayed()
            assertEquals("Privileged $grid selection moved the held row", before, row.fetchSemanticsNode().boundsInRoot.top, 1f)
            capture("selected")
            compose.runOnUiThread { vm.clearAdvancedSelection() }
            compose.onNodeWithTag("directory_toolbar_advanced").assertIsDisplayed()
            assertEquals("Privileged $grid selection close moved the row", before, row.fetchSemanticsNode().boundsInRoot.top, 1f)
            capture("cleared")
        } finally {
            compose.runOnUiThread { vm.closeAdvancedBrowser(); vm.setAdvancedAccessMode(AdvancedAccessMode.OFF) }
            fixture.deleteRecursively()
        }
    }

    @Test
    fun englishNavigationWordsStayWholeAtNormalAndOwnedTextScale() {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val app = compose.activity.application as AFFileManagerApplication
        val previous = app.graph.appearance.settings.value.interfaceScalePercent
        try {
            compose.runOnUiThread {
                com.affilemanager.app.ui.localization.AppLanguageManager.setLanguage(compose.activity, "en")
                vm.setSection(AppSection.FILES)
                vm.showFilesHome()
            }
            for (scale in listOf(100, 115)) {
                compose.runOnUiThread { vm.setInterfaceScalePercent(scale) }
                val targetDensity = compose.activity.resources.displayMetrics.density * scale / 100f
                compose.waitUntil(5_000) {
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    compose.onNodeWithText("Network", useUnmergedTree = true).fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    kotlin.math.abs(layouts.single().layoutInput.density.density - targetDensity) < .01f
                }
                compose.waitForIdle()
                val node = compose.onNodeWithText("Network", useUnmergedTree = true).fetchSemanticsNode()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                val action = node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
                assertTrue(requireNotNull(action.action).invoke(layouts))
                val text = layouts.single()
                val validation = uiAuditDirectory(compose.activity)
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5000)
                val frame = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try { java.io.FileOutputStream(File(validation, "navigation-scale-$scale.png")).use { check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { frame.recycle() }
                val cell = compose.onNodeWithTag("nav_connections").fetchSemanticsNode().boundsInRoot
                assertEquals("Network became an orphaned fragment at $scale%; size=${text.size}; font=${text.layoutInput.style.fontSize}; density=${text.layoutInput.density.density}; fontScale=${text.layoutInput.density.fontScale}; cell=$cell; lines=${(0 until text.lineCount).map { text.getLineLeft(it) to text.getLineRight(it) }}; node=${node.boundsInRoot}", 1, text.lineCount)
            }
        } finally { compose.runOnUiThread { vm.setInterfaceScalePercent(previous) } }
    }

    @Test
    fun largeSystemTextWithPersianKeepsEveryNavigationActionReachable() {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val app = compose.activity.application as AFFileManagerApplication
        val previous = app.graph.appearance.settings.value.interfaceScalePercent
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val originalFont = shell("settings get system font_scale").trim()
        require(originalFont.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) && originalFont.toFloat() in .5f..3f)
        try {
            compose.runOnUiThread {
                vm.setInterfaceScalePercent(100)
                com.affilemanager.app.ui.localization.AppLanguageManager.setLanguage(compose.activity, "fa")
                vm.setSection(AppSection.FILES)
            }
            shell("settings put system font_scale 2.0")
            check(shell("settings get system font_scale").trim() == "2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.99f }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("navigation_rows_2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("navigation_rows_2").assertIsDisplayed()
            val targets = listOf("files" to AppSection.FILES, "analyze" to AppSection.ANALYZE,
                "connections" to AppSection.CONNECTIONS, "share" to AppSection.SHARE, "tools" to AppSection.TOOLS)
            val bounds = targets.map { compose.onNodeWithTag("nav_${it.first}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            bounds.forEachIndexed { i, left -> bounds.drop(i + 1).forEach { right ->
                assertTrue("Navigation targets overlap: $left / $right", minOf(left.right, right.right) - maxOf(left.left, right.left) <= 1f ||
                    minOf(left.bottom, right.bottom) - maxOf(left.top, right.top) <= 1f)
            } }
            val iconYs = targets.map { compose.onNodeWithTag("nav_icon_${it.first}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y }
            for (row in listOf(iconYs.take(3), iconYs.drop(3))) {
                assertTrue("Two-row navigation did not keep its three/two grouping: $iconYs", row.max() - row.min() <= 1.1f)
            }
            assertTrue(iconYs[3] > iconYs[0] + 1.1f)
            for ((tag, section) in targets) {
                compose.onNodeWithTag("nav_$tag").assertIsDisplayed().performClick().assertIsSelected()
                assertEquals(section, vm.section.value)
            }
            val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            try { java.io.FileOutputStream(File(uiAuditDirectory(compose.activity), "navigation-fa-double-interface.png")).use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        } finally { compose.runOnUiThread {
            vm.setInterfaceScalePercent(previous)
            com.affilemanager.app.ui.localization.AppLanguageManager.setLanguage(compose.activity, "en")
            vm.setSection(AppSection.FILES)
        }
            shell("settings put system font_scale $originalFont")
            check(shell("settings get system font_scale").trim() == originalFont)
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
            pressSystemBack()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.trashBrowser.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)
            assertTrue(viewModel.filesHomeVisible.value)

            compose.runOnUiThread { viewModel.openFileCategory(FileCategory.ARCHIVES) }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.fileCategory.value.open }
            compose.onNodeWithTag("directory_toolbar_category").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.fileCategory.value.open }
            assertEquals(AppSection.FILES, viewModel.section.value)

            compose.runOnUiThread { viewModel.setSection(AppSection.SHARE) }
            compose.onNodeWithTag("sharing_list").performScrollToNode(hasText("Browse folders"))
            compose.onNodeWithText("Browse folders").performScrollTo().performClick()
            compose.onNodeWithTag("share_folder_picker").assertIsDisplayed()
            pressSystemBack()
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

    private fun pressSystemBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        ))
        compose.waitForIdle()
    }

    @Test
    fun rootStorageOpensAsAFileOverlayAndBackReturnsToFiles() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Device-root browsing requires an actual UID-0 provider, not Shizuku shell",
            arguments.getString("afRoot") == "true" || arguments.getString("afShizukuRoot") == "true")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        try {
            compose.runOnUiThread { viewModel.showFilesHome() }
            connectRootBackend(viewModel)
            assertTrue(com.affilemanager.app.terminal.PrivilegedTerminalRuntimeVerifier.verifyAuthorizedBackend(compose.activity, 0))
            compose.runOnUiThread { viewModel.openAdvancedBrowser("/") }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == "/" && state.error == null
            }
            compose.onNodeWithTag("directory_toolbar_advanced").assertIsDisplayed()

            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
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
    fun directlyOpenedPrivilegedSubfolderBackMovesToItsParentFirst() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val parent = requireNotNull(compose.activity.getExternalFilesDir(null)).canonicalFile
        val child = File(parent, "advanced-back-${System.nanoTime()}").apply { mkdirs() }.canonicalFile

        try {
            connectRootBackend(viewModel)

            compose.runOnUiThread { viewModel.openAdvancedBrowser(child.absolutePath) }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == child.absolutePath && state.error == null
            }

            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == parent.absolutePath && state.error == null
            }
            assertTrue(viewModel.advancedBrowser.value.open)
            assertEquals(parent.absolutePath, viewModel.advancedBrowser.value.path)
        } finally {
            compose.runOnUiThread {
                viewModel.closeAdvancedBrowser()
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF)
                viewModel.setSection(AppSection.FILES)
            }
            compose.waitForIdle()
            child.deleteRecursively()
        }
    }

    private fun connectRootBackend(viewModel: MainViewModel) {
        val arguments = InstrumentationRegistry.getArguments()
        val useRoot = arguments.getString("afRoot") == "true"
        val useShizukuRoot = arguments.getString("afShizukuRoot") == "true"
        val useShizukuShell = arguments.getString("afShizukuShell") == "true"
        assumeTrue(useRoot || useShizukuRoot || useShizukuShell)
        val expected = when {
            useRoot -> AdvancedAccessBackend.ROOT
            useShizukuRoot -> AdvancedAccessBackend.SHIZUKU_ROOT
            else -> AdvancedAccessBackend.SHIZUKU_SHELL
        }
        compose.runOnUiThread {
            viewModel.setSection(AppSection.FILES)
            viewModel.setAdvancedAccessMode(if (useRoot) AdvancedAccessMode.ROOT else AdvancedAccessMode.SHIZUKU)
            if (useRoot) viewModel.requestRootAccess() else viewModel.requestShizukuAccess()
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            val state = viewModel.advancedAccess.value
            state.activeBackend == expected || (!state.connecting && state.error != null)
        }
        assertEquals("Root backend did not connect: ${viewModel.advancedAccess.value}", expected, viewModel.advancedAccess.value.activeBackend)
        assertEquals(if (expected == AdvancedAccessBackend.SHIZUKU_SHELL) 2_000 else 0, viewModel.advancedAccess.value.serviceUid)
    }

    @Test
    fun rootBackendReportsRealRunningUserAppsAndRamWithoutUsageAccess() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            connectRootBackend(viewModel)
            compose.runOnUiThread { viewModel.openDeviceCleanup() }
            compose.waitUntil(timeoutMillis = 20_000) {
                val state = viewModel.deviceCleanup.value
                !state.loading && state.snapshot?.privilegedAppAccessAvailable == true &&
                    state.snapshot.privilegedAppAccessError == null
            }
            val snapshot = requireNotNull(viewModel.deviceCleanup.value.snapshot)
            assertTrue(snapshot.runningApps.isNotEmpty())
            assertTrue(snapshot.runningApps.none { it.packageName == compose.activity.packageName })
            assertTrue(snapshot.runningApps.all { it.ramBytes != null && it.ramBytes >= 0L })
            if (InstrumentationRegistry.getArguments().getString("afShizukuShell") == "true") {
                val shizukuManager = snapshot.runningApps.firstOrNull {
                    it.packageName == "moe.shizuku.privileged.api"
                }
                assertNotNull("The running Shizuku manager fixture was not reported", shizukuManager)
                compose.runOnUiThread { viewModel.forceStopApplication(requireNotNull(shizukuManager).packageName) }
                compose.waitUntil(timeoutMillis = 20_000) {
                    val refreshed = viewModel.deviceCleanup.value
                    !refreshed.loading && refreshed.snapshot?.runningApps?.none {
                        it.packageName == "moe.shizuku.privileged.api"
                    } == true
                }
            }
        } finally {
            compose.runOnUiThread {
                viewModel.closeDeviceCleanup()
                viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF)
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun shizukuReconnectsAfterRapidModeChanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            repeat(3) {
                compose.runOnUiThread { viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF) }
                connectRootBackend(viewModel)
                compose.runOnUiThread { viewModel.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU) }
                assertEquals(AdvancedAccessBackend.SHIZUKU_ROOT, viewModel.advancedAccess.value.activeBackend)
            }
        } finally {
            compose.runOnUiThread { viewModel.setAdvancedAccessMode(AdvancedAccessMode.OFF) }
        }
    }

    @Test
    fun shizukuRootBrowsesProtectedStorageAndBackUsesRootBoundaries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

        try {
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.showFilesHome()
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

            // Shizuku running as UID 0 can navigate above Android/data; only the
            // shell backend stops at the protected-storage boundary.
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == "/storage/emulated/0/Android" && state.error == null
            }
            compose.runOnUiThread { viewModel.openAdvancedBrowser("/") }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.advancedBrowser.value
                state.open && !state.loading && state.path == "/" && state.error == null
            }
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
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
            compose.runOnUiThread {
                viewModel.setDirectoryDisplaySettings(
                    PanelId.LEFT,
                    DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST),
                )
            }
            compose.waitForIdle()
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
