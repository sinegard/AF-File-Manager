package com.affilemanager.app.operations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileOperationManagerTest {
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
}
