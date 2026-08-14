package com.affilemanager.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.model.SearchFilters
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import androidx.test.core.app.ApplicationProvider

@RunWith(AndroidJUnit4::class)
class SearchWorkspaceFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun scopedSearchResultCanBeSelectedAndRevealedInItsFolder() {
        val root = File(compose.activity.getExternalFilesDir(null), "search-${System.nanoTime()}").apply { mkdirs() }
        val token = "scope-${System.nanoTime()}"
        val resultFile = File(root, "$token.pdf").apply { writeText("pdf") }
        try {
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, root.absolutePath)
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.setSection(AppSection.ANALYZE)
            }

            compose.onNodeWithText("This folder").fetchSemanticsNode()
            compose.onNodeWithTag("search_advanced_toggle").performClick()
            compose.onNodeWithText("File types").fetchSemanticsNode()
            compose.onNodeWithTag("search_advanced_toggle").performClick()
            compose.onNodeWithTag("search_query").performTextInput(token)
            compose.onNodeWithTag("search_query").assert(hasText(token))
            compose.runOnUiThread {
                viewModel.search(SearchFilters(query = token), listOf(root.absolutePath))
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.searchState.value.results.any { it.absolutePath == resultFile.absolutePath }
            }
            compose.onNodeWithTag("analyze_list").performScrollToNode(hasText(resultFile.name))
            compose.onNodeWithText(resultFile.name).fetchSemanticsNode()
            captureSearchWorkspace()

            compose.onNodeWithTag("search_result").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Batch rename").fetchSemanticsNode()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("analyze_list").performScrollToNode(hasContentDescription("Show in folder"))
            compose.onNodeWithContentDescription("Show in folder").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.section.value == AppSection.FILES }
            compose.waitForIdle()
            assertEquals(root.canonicalPath, File(viewModel.activePanelState().path).canonicalPath)
            assertTrue(resultFile.absolutePath in viewModel.activePanelState().selectedPaths)
            compose.onNodeWithText("Files").fetchSemanticsNode()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun captureSearchWorkspace() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val artifact = File(requireNotNull(application.getExternalFilesDir("validation")), "search-workspace-0.5.0.png")
        artifact.parentFile?.mkdirs()
        artifact.outputStream().use { output ->
            assertTrue(
                compose.onRoot(useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(artifact.isFile && artifact.length() > 0)
    }
}
