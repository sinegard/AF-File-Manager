package com.affilemanager.app.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteUploadPublicationTest {
    private class Store(existing: Boolean = true) {
        val files = linkedMapOf("/stage" to "new").apply { if (existing) put("/target", "old") }
        val events = mutableListOf<String>()
        var beforeRename: suspend (String, String) -> Unit = { _, _ -> }
        var afterRename: suspend (String, String) -> Unit = { _, _ -> }
        var beforeRemove: suspend (String) -> Unit = {}
        suspend fun publish() = RemoteUploadPublication.publish("/stage", "/target", null,
            exists = { files.containsKey(it) },
            rename = { from, to ->
                beforeRename(from, to)
                check(!files.containsKey(to)) { "occupied fixture" }
                files[to] = checkNotNull(files.remove(from))
                events += "rename:$from:$to"
                afterRename(from, to)
            },
            remove = { path -> beforeRemove(path); checkNotNull(files.remove(path)); events += "delete:$path" })
    }

    @Test fun newPublicationDoesNotCreateOrDeleteABackup() = runBlocking {
        val store = Store(false)
        store.publish()
        assertEquals(mapOf("/target" to "new"), store.files)
        assertEquals(listOf("rename:/stage:/target"), store.events)
    }

    @Test fun successfulReplacementDeletesThePreviousCopyOnlyAfterPublication() = runBlocking {
        val store = Store()
        store.publish()
        assertEquals(mapOf("/target" to "new"), store.files)
        assertEquals(3, store.events.size)
        assertTrue(store.events[0].startsWith("rename:/target:/.af-upload-backup-"))
        assertEquals("rename:/stage:/target", store.events[1])
        assertTrue(store.events[2].startsWith("delete:/.af-upload-backup-"))
    }

    @Test fun rejectedPublicationRestoresThePreviousVersionBeforeReturningTheError() = runBlocking {
        val store = Store()
        store.beforeRename = { from, _ -> if (from == "/stage") throw IOException("publication denied") }
        val failure = runCatching { store.publish() }.exceptionOrNull()
        assertEquals("publication denied", failure?.message)
        assertEquals(mapOf("/stage" to "new", "/target" to "old"), store.files)
        assertFalse(store.events.any { it.startsWith("delete:") })
    }

    @Test fun failureBeforeMovingTheOriginalDoesNotAlterIt() = runBlocking {
        val store = Store()
        store.beforeRename = { from, _ -> if (from == "/target") throw IOException("backup denied") }
        assertEquals("backup denied", runCatching { store.publish() }.exceptionOrNull()?.message)
        assertEquals(mapOf("/stage" to "new", "/target" to "old"), store.files)
        assertTrue(store.events.isEmpty())
    }

    @Test fun lostBackupRenameReplyIsInspectedAndRecovered() = runBlocking {
        val store = Store()
        store.afterRename = { from, _ -> if (from == "/target") throw IOException("lost reply") }
        assertEquals("lost reply", runCatching { store.publish() }.exceptionOrNull()?.message)
        assertEquals(mapOf("/stage" to "new", "/target" to "old"), store.files)
    }

    @Test fun concurrentDestinationAndPreviousVersionBothSurviveFailedPublication() = runBlocking {
        val store = Store()
        store.beforeRename = { from, _ -> if (from == "/stage") {
            store.files["/target"] = "concurrent"; throw IOException("publication denied")
        } }
        val failure = runCatching { store.publish() }.exceptionOrNull()
        assertTrue(failure is RemoteUploadRecoveryException)
        val path = (failure as RemoteUploadRecoveryException).recoveryPath
        assertEquals("AF-RECOVERY: $path", failure.message)
        assertEquals("old", store.files[path])
        assertEquals("concurrent", store.files["/target"])
        assertEquals("new", store.files["/stage"])
    }

    @Test fun unconfirmedPublicationRetainsBothVersionsInsteadOfOverwritingTheDestination() = runBlocking {
        val store = Store()
        store.afterRename = { from, _ -> if (from == "/stage") throw IOException("lost publish reply") }
        val failure = runCatching { store.publish() }.exceptionOrNull() as RemoteUploadRecoveryException
        assertEquals("old", store.files[failure.recoveryPath])
        assertEquals("new", store.files["/target"])
        assertFalse(store.events.any { it.startsWith("delete:") })
    }

    @Test fun cleanupFailureIsVisibleAndDoesNotDiscardEitherCopy() = runBlocking {
        val store = Store()
        store.beforeRemove = { throw IOException("cleanup denied") }
        val failure = runCatching { store.publish() }.exceptionOrNull() as RemoteUploadRecoveryException
        assertEquals("old", store.files[failure.recoveryPath])
        assertEquals("new", store.files["/target"])
    }

    @Test fun actualCoroutineCancellationAfterBackupRestoresInANonCancelledContext() = runBlocking {
        supervisorScope {
            val store = Store()
            var recoveryWasActive = false
            store.beforeRename = { from, _ ->
                if (from == "/target") currentCoroutineContext().cancel()
                if (from.startsWith("/.af-upload-backup-")) {
                    yield()
                    recoveryWasActive = currentCoroutineContext().isActive
                }
            }
            val task = async { store.publish() }
            assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
            assertTrue(recoveryWasActive)
            assertEquals(mapOf("/stage" to "new", "/target" to "old"), store.files)
        }
    }
}
