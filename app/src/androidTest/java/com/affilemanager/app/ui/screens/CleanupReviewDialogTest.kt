package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.DirectoryContentUsage
import com.affilemanager.app.model.DirectoryContentsUsage
import com.affilemanager.app.model.DirectoryUsage
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.StorageAnalysis
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CleanupReviewDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun checkboxSelectsWhileCardOpensTheCandidate() {
        val opened = AtomicInteger()
        val candidate = FileEntry(
            absolutePath = "/storage/emulated/0/Download/report.pdf",
            name = "report.pdf",
            kind = EntryKind.DOCUMENT,
            sizeBytes = 4_096,
            modifiedAtMillis = 1,
            isHidden = false,
            isReadable = true,
            isWritable = true,
        )
        compose.setContent {
            MaterialTheme {
                CleanupReviewDialog(
                    analysis = StorageAnalysis(
                        scannedFiles = 1,
                        scannedDirectories = 0,
                        totalBytes = candidate.sizeBytes,
                        largestFiles = listOf(candidate),
                        oldestFiles = listOf(candidate),
                        emptyDirectories = emptyList(),
                        truncated = false,
                    ),
                    duplicates = emptyList(),
                    similarImages = emptyList(),
                    similarImagesRunning = false,
                    similarImagesAnalyzed = false,
                    similarImagesError = null,
                    initialCategory = CleanupCategory.LARGE,
                    analysisRootPaths = listOf("/storage/emulated/0"),
                    onAnalyzeSimilarImages = {},
                    onMoveToTrash = {},
                    onLoadFolder = { Result.failure(IllegalStateException("not used")) },
                    onOpenFile = { opened.incrementAndGet() },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("cleanup_candidate_checkbox").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("cleanup_candidate_visual", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, opened.get())

        compose.onNodeWithTag("cleanup_candidate_card").performClick()
        assertEquals(1, opened.get())
    }

    @Test
    fun folderOpensInsideTheCleanupScreenAndBackReturnsToTheCategory() {
        val dismissed = AtomicInteger()
        val externallyOpened = AtomicInteger()
        val root = "/storage/emulated/0"
        val folder = "$root/Download"
        val child = FileEntry(
            absolutePath = "$folder/report.pdf",
            name = "report.pdf",
            kind = EntryKind.DOCUMENT,
            sizeBytes = 4_096,
            modifiedAtMillis = 1,
            isHidden = false,
            isReadable = true,
            isWritable = true,
        )
        val nestedFolder = FileEntry(
            absolutePath = "$folder/Documents",
            name = "Documents",
            kind = EntryKind.DIRECTORY,
            sizeBytes = 4_096,
            modifiedAtMillis = 1,
            isHidden = false,
            isReadable = true,
            isWritable = true,
        )
        compose.setContent {
            MaterialTheme {
                CleanupReviewDialog(
                    analysis = StorageAnalysis(
                        scannedFiles = 1,
                        scannedDirectories = 1,
                        totalBytes = child.sizeBytes,
                        largestFiles = listOf(child),
                        oldestFiles = listOf(child),
                        emptyDirectories = emptyList(),
                        truncated = false,
                        largestDirectories = listOf(DirectoryUsage(folder, child.sizeBytes, 1)),
                    ),
                    duplicates = emptyList(),
                    similarImages = emptyList(),
                    similarImagesRunning = false,
                    similarImagesAnalyzed = false,
                    similarImagesError = null,
                    initialCategory = CleanupCategory.LARGEST_FOLDERS,
                    analysisRootPaths = listOf(root),
                    onAnalyzeSimilarImages = {},
                    onMoveToTrash = {},
                    onLoadFolder = {
                        Result.success(
                            DirectoryContentsUsage(
                                directoryPath = folder,
                                entries = listOf(
                                    DirectoryContentUsage(nestedFolder, fileCount = 1),
                                    DirectoryContentUsage(child, fileCount = 1),
                                ),
                                totalBytes = nestedFolder.sizeBytes + child.sizeBytes,
                                scannedEntries = 2,
                                truncated = false,
                            ),
                        )
                    },
                    onOpenFile = { externallyOpened.incrementAndGet() },
                    onDismiss = { dismissed.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithTag("cleanup_candidate_card").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("cleanup_folder_browser").assertIsDisplayed()
        assertEquals(0, externallyOpened.get())
        assertEquals(0, dismissed.get())
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val artifact = File(requireNotNull(application.getExternalFilesDir("validation")), "cleanup-folder-browser.png")
        artifact.outputStream().use { output ->
            assertTrue(
                compose.onNodeWithTag("cleanup_review_dialog", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(artifact.isFile && artifact.length() > 0)

        compose.onNodeWithTag("cleanup_back").performClick()
        assertTrue(compose.onAllNodesWithTag("cleanup_folder_browser").fetchSemanticsNodes().isEmpty())
        assertEquals(0, dismissed.get())

        compose.onNodeWithTag("cleanup_back").performClick()
        assertEquals(1, dismissed.get())
    }

    @Test
    fun smartDuplicateSelectionKeepsOneCopyAndDoesNotDeleteBeforeConfirmation() {
        val moved = AtomicReference<Set<String>>(emptySet())
        val group = DuplicateGroup(
            sha256 = "verified",
            sizeBytes = 4_096,
            paths = listOf(
                "/storage/emulated/0/Download/a.txt",
                "/storage/emulated/0/Download/b.txt",
                "/storage/emulated/0/Download/c.txt",
            ),
        )
        compose.setContent {
            MaterialTheme {
                CleanupReviewDialog(
                    analysis = StorageAnalysis(
                        scannedFiles = 3,
                        scannedDirectories = 0,
                        totalBytes = 12_288,
                        largestFiles = emptyList(),
                        oldestFiles = emptyList(),
                        emptyDirectories = emptyList(),
                        truncated = false,
                    ),
                    duplicates = listOf(group),
                    similarImages = emptyList(),
                    similarImagesRunning = false,
                    similarImagesAnalyzed = false,
                    similarImagesError = null,
                    initialCategory = CleanupCategory.DUPLICATES,
                    analysisRootPaths = listOf("/storage/emulated/0"),
                    onAnalyzeSimilarImages = {},
                    onMoveToTrash = moved::set,
                    onLoadFolder = { Result.failure(IllegalStateException("not used")) },
                    onOpenFile = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("cleanup_select_duplicate_copies").assertIsDisplayed().performClick()
        assertEquals(emptySet<String>(), moved.get())

        compose.onNodeWithTag("cleanup_move_selected").performClick()
        compose.onNodeWithTag("cleanup_confirm_move").performClick()

        assertEquals(setOf(group.paths[1], group.paths[2]), moved.get())
    }
}
