package com.affilemanager.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
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
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
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
            compose.onNodeWithContentDescription("Show thumbnails").assertIsDisplayed()
            compose.onNodeWithText("AFTrashVisual").performClick()
            compose.onNodeWithText("Vidinis katalogas").assertIsDisplayed()
            compose.onNodeWithText("tik-pirmame-lygyje.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("tik-viduje.txt").fetchSemanticsNodes().isEmpty())
            captureDialog(File(requireNotNull(application.getExternalFilesDir("validation")), "trash-folder-0.4.0.png"))

            compose.onNodeWithText("tik-pirmame-lygyje.txt").performClick()
            compose.onNodeWithText("UTF-8 · up to 2 MB · editable working copy").assertIsDisplayed()
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

            compose.onNodeWithContentDescription("Empty trash").performClick()
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
