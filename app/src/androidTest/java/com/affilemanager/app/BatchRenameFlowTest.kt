package com.affilemanager.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.core.app.ApplicationProvider

@RunWith(AndroidJUnit4::class)
class BatchRenameFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun previewExecutionAndSnackbarUndoKeepTheBatchRecoverable() {
        val root = File(compose.activity.getExternalFilesDir(null), "rename-${System.nanoTime()}").apply { mkdirs() }
        val first = File(root, "one.txt").apply { writeText("one") }
        val second = File(root, "two.txt").apply { writeText("two") }
        val renamedFirst = File(root, "new-one.txt")
        val renamedSecond = File(root, "new-two.txt")
        try {
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            compose.runOnUiThread {
                viewModel.beginBatchRename(listOf(first.absolutePath, second.absolutePath))
            }

            compose.onNodeWithTag("batch_rename_dialog").fetchSemanticsNode()
            compose.onNodeWithTag("batch_rename_prefix").performTextInput("new-")
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.batchRename.value.preview?.canExecute == true }
            compose.onNodeWithTag("batch_rename_list").performScrollToNode(hasText("→ new-one.txt"))
            compose.onNodeWithText("→ new-one.txt").fetchSemanticsNode()
            captureDialog()
            compose.onNodeWithTag("batch_rename_execute").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { renamedFirst.exists() && renamedSecond.exists() }
            assertTrue(!first.exists() && !second.exists())

            compose.onNodeWithText("Atšaukti").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { first.exists() && second.exists() }
            assertTrue(!renamedFirst.exists() && !renamedSecond.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun captureDialog() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val artifact = File(requireNotNull(application.getExternalFilesDir("validation")), "batch-rename-0.5.0.png")
        artifact.parentFile?.mkdirs()
        artifact.outputStream().use { output ->
            assertTrue(
                compose.onNodeWithTag("batch_rename_dialog", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(artifact.isFile && artifact.length() > 0)
    }
}
