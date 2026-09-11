package com.affilemanager.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrashBrowserVisualTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun longTrashGridNamesKeepCardsAndActionsAligned() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("trash-grid-source")), "run-${System.nanoTime()}")
        val short = File(fixtureRoot, "short").apply { mkdirs() }
        val long = File(fixtureRoot, "this-is-a-very-long-trash-folder-name-that-must-not-change-the-card-height").apply { mkdirs() }
        val repository = application.graph.trash
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            repository.moveToTrash(listOf(short.absolutePath, long.absolutePath), OperationContext.background())
            compose.runOnUiThread {
                viewModel.openTrashBrowser()
                viewModel.setTrashDisplaySettings(
                    DirectoryDisplaySettings(
                        layoutMode = DirectoryLayoutMode.GRID,
                        iconScalePercent = 100,
                        spacingScalePercent = 100,
                        gridColumns = 4,
                        gridStyle = DirectoryGridStyle.CARDS,
                        showThumbnails = false,
                    ),
                )
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.trashBrowser.value
                state.open && !state.loading && state.grid &&
                    state.entries.any { it.name == short.name } && state.entries.any { it.name == long.name }
            }

            val shortBounds = compose.onNodeWithTag("trash-grid-item-${short.name}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val longBounds = compose.onNodeWithTag("trash-grid-item-${long.name}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(shortBounds.height - longBounds.height) <= 1f)
            assertTrue(kotlin.math.abs(shortBounds.top - longBounds.top) <= 1f)
            captureDialog(
                File(requireNotNull(application.getExternalFilesDir("validation")), "trash-grid-long-names.png"),
            )
        } finally {
            repository.list().filter { it.originalPath == short.absolutePath || it.originalPath == long.absolutePath }
                .forEach { repository.deleteForever(it.id) }
            fixtureRoot.deleteRecursively()
            compose.runOnUiThread { viewModel.closeTrashBrowser() }
        }
    }

    @Test
    fun trashOpensAsFolderNavigatesWithSystemBackAndCanEmptyAll() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("trash-ui-source")), "run-${System.nanoTime()}")
        val deletedDirectory = File(fixtureRoot, "AFTrashVisual")
        val nestedDirectory = File(deletedDirectory, "Vidinis katalogas")
        require(nestedDirectory.mkdirs())
        File(deletedDirectory, "tik-pirmame-lygyje.txt").writeText("pirmas")
        File(nestedDirectory, "tik-viduje.txt").writeText("antras")
        val originalPath = deletedDirectory.absolutePath
        val repository = application.graph.trash
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            repository.moveToTrash(listOf(originalPath), OperationContext.background())
            compose.runOnUiThread {
                viewModel.refreshTrash()
                viewModel.setSection(AppSection.TOOLS)
            }
            compose.onNodeWithTag("tools_list").performScrollToNode(hasText("Open trash"))
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Open trash").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Open trash").performClick()
            compose.onNodeWithText("AFTrashVisual").assertIsDisplayed()
            // The view choice survives app restarts and previous test runs. Start
            // this toggle check from icons without relying on a fresh install.
            compose.runOnUiThread {
                if (viewModel.trashBrowser.value.showThumbnails) viewModel.toggleTrashThumbnails()
            }
            compose.onNodeWithTag("directory_toolbar_trash").assertIsDisplayed()
            compose.onNodeWithTag("directory_search_trash").assertIsDisplayed()
            compose.onNodeWithTag("directory_layout_trash").assertIsDisplayed()
            compose.onNodeWithContentDescription("Folder actions").performClick()
            compose.onNodeWithText("Show thumbnails").assertIsDisplayed().performClick()
            compose.onNodeWithText("AFTrashVisual").performClick()
            compose.onNodeWithText("Vidinis katalogas").assertIsDisplayed()
            compose.onNodeWithText("tik-pirmame-lygyje.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("tik-viduje.txt").fetchSemanticsNodes().isEmpty())
            captureDialog(File(requireNotNull(application.getExternalFilesDir("validation")), "trash-folder-0.4.0.png"))

            compose.onNodeWithText("tik-pirmame-lygyje.txt").performClick()
            compose.onNodeWithTag("full-text-editor").assertIsDisplayed()
            compose.onNodeWithText("UTF-8").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Vidinis katalogas").assertIsDisplayed()

            compose.onNodeWithText("Vidinis katalogas").performClick()
            compose.onNodeWithText("tik-viduje.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("tik-pirmame-lygyje.txt").fetchSemanticsNodes().isEmpty())

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("tik-pirmame-lygyje.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("tik-viduje.txt").fetchSemanticsNodes().isEmpty())
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Restore AFTrashVisual").assertIsDisplayed()

            compose.onNodeWithContentDescription("Folder actions").performClick()
            compose.onNodeWithText("Empty trash").performClick()
            compose.onNodeWithText("Empty all trash?").assertIsDisplayed()
            compose.onNodeWithText("Delete all").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Trash is empty").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(repository.list().isEmpty())
        } finally {
            repository.list().filter { it.originalPath == originalPath }.forEach { repository.deleteForever(it.id) }
            fixtureRoot.deleteRecursively()
            compose.runOnUiThread { viewModel.closeTrashBrowser() }
        }
    }

    @Test
    fun orphanOnlyTrashCanStillBeEmptiedFromTheMenu() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val repository = application.graph.trash
        val trashRoot = requireNotNull(application.getExternalFilesDir("trash"))
        val orphan = File(trashRoot, "ui-orphan-${System.nanoTime()}.partial")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            repository.emptyAll(OperationContext.background())
            orphan.writeBytes(ByteArray(2_048) { 11 })
            compose.runOnUiThread {
                viewModel.refreshTrash()
                viewModel.openTrashBrowser()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.trashBrowser.value
                state.open && !state.loading && state.entries.isEmpty() && state.storedItemCount == 1
            }

            compose.onNodeWithContentDescription("Folder actions").performClick()
            compose.onNodeWithTag("trash_empty_all").assertIsEnabled().performClick()
            compose.onNodeWithText("Empty all trash?").assertIsDisplayed()
            compose.onNodeWithText("Delete all").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                val state = viewModel.trashBrowser.value
                !state.emptying && !state.loading && state.storedItemCount == 0
            }
            assertFalse(repository.storageState().hasStoredData)
            assertTrue(trashRoot.listFiles().orEmpty().isEmpty())
        } finally {
            repository.emptyAll(OperationContext.background())
            compose.runOnUiThread { viewModel.closeTrashBrowser() }
        }
    }

    private fun captureDialog(target: File) {
        compose.waitForIdle()
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            assertTrue(
                compose.onNodeWithTag("trash-browser-dialog", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(target.isFile && target.length() > 0)
    }
}
