package com.affilemanager.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.affilemanager.app.data.FileSelectionSummary
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OperationDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deleteConfirmationWaitsForAndShowsTheRecursiveSummary() {
        val summary = CompletableDeferred<Result<FileSelectionSummary>>()
        compose.setContent { MaterialTheme {
            DeleteConfirmationDialog(
                names = listOf("Documents"), permanent = true, onDismiss = {}, onConfirm = {},
                loadSummary = { summary.await() },
            )
        } }
        compose.onNodeWithTag("delete_name").assertIsDisplayed()
        compose.onNodeWithTag("confirm_delete").assertIsNotEnabled()
        summary.complete(Result.success(FileSelectionSummary(1, 7, 3, 12_345, 11, true)))
        compose.waitForIdle()
        compose.onNodeWithTag("confirm_delete").assertIsEnabled()
        compose.onNodeWithTag("delete_file_count").assertIsDisplayed()
        compose.onNodeWithTag("delete_folder_count").assertIsDisplayed()
        compose.onNodeWithTag("delete_size").assertIsDisplayed()
    }

    @Test fun deleteSummaryIsNotRestartedByParentRecomposition() {
        val summary = CompletableDeferred<Result<FileSelectionSummary>>()
        val parentRevision = mutableIntStateOf(0)
        val calls = AtomicInteger(0)
        compose.setContent { MaterialTheme {
            if (parentRevision.intValue >= 0) {
                DeleteConfirmationDialog(
                    names = (1..80).map { "file-$it.txt" },
                    permanent = false,
                    onDismiss = {},
                    onConfirm = {},
                    loadSummary = {
                        calls.incrementAndGet()
                        summary.await()
                    },
                )
            }
        } }
        compose.onNodeWithTag("confirm_delete").assertIsNotEnabled()
        repeat(4) {
            compose.runOnIdle { parentRevision.intValue += 1 }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, calls.get()) }

        summary.complete(Result.success(FileSelectionSummary(80, 80, 0, 80, 80, true)))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onNodeWithTag("confirm_delete").fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
        }
        compose.onNodeWithTag("confirm_delete").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun operationProgressOffersHideAndCancelWithoutConflatingThem() {
        var hidden = 0
        var cancelled = 0
        compose.setContent { MaterialTheme {
            OperationProgressDialog(
                operation = OperationSnapshot(
                    id = "operation", title = "Copying", status = OperationStatus.RUNNING,
                    completedItems = 2, totalItems = 5, completedBytes = 1_024, totalBytes = 4_096,
                    currentName = "/folder/current.txt",
                ),
                onCancel = { cancelled++ },
                onHide = { hidden++ },
            )
        } }
        compose.onNodeWithText("current.txt").assertIsDisplayed()
        compose.onNodeWithTag("operation_bytes").assertIsDisplayed()
        compose.onNodeWithTag("operation_items").assertIsDisplayed()
        compose.onNodeWithTag("hide_operation").assertHasClickAction().performClick()
        compose.onNodeWithTag("cancel_operation").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(1, hidden)
            assertEquals(1, cancelled)
        }
    }
}
