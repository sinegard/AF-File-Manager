package com.affilemanager.app.operations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileOperationManagerTest {
    @Test fun fastFailuresStayVisibleUntilAcknowledgedButRestoredHistoryDoesNotReappear() = runTest {
        val manager = FileOperationManager(backgroundScope)
        manager.restore(listOf(OperationSnapshot("old", "old error", OperationStatus.FAILED)))
        assertTrue(manager.resultNotifications.value.isEmpty())
        val id = manager.submit("archive") { throw java.io.IOException("Archive size limit exceeded") }.getOrThrow()
        runCurrent()
        val result = manager.resultNotifications.value.single()
        assertEquals(id, result.id)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals("Archive size limit exceeded", result.message)
        manager.dismissResult(id)
        assertTrue(manager.resultNotifications.value.isEmpty())
        assertTrue(manager.operations.value.any { it.id == id })
    }

    @Test fun partialCompletionCannotBeShownAsSuccessful() = runTest {
        val manager = FileOperationManager(backgroundScope)
        manager.submit("delete") { completeWithErrors(1, "One file could not be removed") }.getOrThrow()
        runCurrent()
        assertEquals(OperationStatus.COMPLETED_WITH_ERRORS, manager.resultNotifications.value.single().status)
    }
    @Test fun aQueuedOperationStartsBackgroundVisibilityAndCanBeCancelled() = runTest {
        var starts = 0
        val gate = CompletableDeferred<Unit>()
        val manager = FileOperationManager(backgroundScope) { starts++ }
        val id = manager.submit("copy") { gate.await() }.getOrThrow()
        runCurrent()
        assertEquals(1, starts)
        assertTrue(manager.operations.value.single { it.id == id }.status in
            setOf(OperationStatus.QUEUED, OperationStatus.RUNNING))
        manager.cancel(id)
        runCurrent()
        assertEquals(OperationStatus.CANCELLED, manager.operations.value.single { it.id == id }.status)
    }

    @Test fun notificationStartupFailureDoesNotLoseTheFileOperation() = runTest {
        val manager = FileOperationManager(backgroundScope) { error("notification unavailable") }
        val result = manager.submit("copy") { }
        assertTrue(result.isSuccess)
        runCurrent()
        assertEquals(OperationStatus.SUCCEEDED, manager.operations.value.single().status)
    }

    @Test fun largeFileProgressKeepsExactBytesWithoutPublishingEveryBuffer() {
        var snapshot = OperationSnapshot("large", "copy", OperationStatus.RUNNING)
        var publishes = 0
        val context = OperationContext("large", MutableStateFlow(false)) { transform ->
            snapshot = snapshot.transform()
            publishes++
        }

        repeat(4_096) { context.progress(byteDelta = 256L * 1_024L, currentName = "large.bin") }
        context.flushProgress()

        assertEquals(1L * 1_024L * 1_024L * 1_024L, snapshot.completedBytes)
        assertEquals("large.bin", snapshot.currentName)
        assertTrue("progress updates were not throttled: $publishes", publishes < 128)
    }

    @Test fun manySmallItemsDoNotPublishANotificationForEveryFile() {
        var snapshot = OperationSnapshot("many", "delete", OperationStatus.RUNNING)
        var publishes = 0
        val context = OperationContext("many", MutableStateFlow(false)) { transform ->
            snapshot = snapshot.transform()
            publishes++
        }
        repeat(4_096) { context.progress(itemDelta = 1, byteDelta = 13, currentName = "file-$it") }
        context.flushProgress()
        assertEquals(4_096, snapshot.completedItems)
        assertEquals(4_096 * 13L, snapshot.completedBytes)
        assertEquals("file-4095", snapshot.currentName)
        assertTrue("per-file updates were not bounded: $publishes", publishes < 128)
    }

    @Test fun indexingIsNotShownAsCompleteEvenWhenAllBytesAreWritten() {
        var snapshot = OperationSnapshot("index", "copy", OperationStatus.RUNNING)
        val context = OperationContext("index", MutableStateFlow(false)) { snapshot = snapshot.it() }
        context.setTotals(1, 10)
        context.progress(1, 10, "file.txt")
        context.setStage(OperationStage.PUBLISHING_INDEX)
        assertEquals(10L, snapshot.completedBytes)
        assertEquals(null, snapshot.visibleProgress())
        assertEquals(1f, snapshot.copy(status = OperationStatus.SUCCEEDED).visibleProgress())
    }
}
