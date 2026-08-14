package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            compose.onNodeWithTag("file-list-LEFT").performScrollToIndex(35)
            compose.onNodeWithText("a-035.txt").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.fileScrollPosition(firstScrollKey).firstVisibleItemIndex > 0
            }

            navigateAndWait(viewModel, secondDirectory)
            compose.onNodeWithText("b-000.txt").assertIsDisplayed()
            compose.onNodeWithTag("file-list-LEFT").performScrollToIndex(28)
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
            compose.onNodeWithTag("file-list-LEFT").performScrollToIndex(32)
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
