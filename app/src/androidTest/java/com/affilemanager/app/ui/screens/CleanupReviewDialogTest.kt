package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.StorageAnalysis
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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
                    analysisRootPath = "/storage/emulated/0",
                    onAnalyzeSimilarImages = {},
                    onMoveToTrash = {},
                    onOpenLocation = { _, _ -> opened.incrementAndGet() },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("cleanup_candidate_checkbox").assertIsOff().performClick().assertIsOn()
        assertEquals(0, opened.get())

        compose.onNodeWithTag("cleanup_candidate_card").performClick()
        assertEquals(1, opened.get())
    }
}
