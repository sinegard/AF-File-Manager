package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketException

class ReconnectingRemoteClientTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun brokenPipeReconnectsAndRetriesSafeListOnce() = runBlocking {
        val state = RemoteState()
        val initial = FakeClient(state, failNextList = SocketException("Broken pipe"))
        val replacement = FakeClient(state)
        var reconnects = 0
        var notifications = 0
        val client = ReconnectingRemoteClient(
            initial = initial,
            reconnect = { reconnects += 1; replacement },
            onReconnected = { notifications += 1 },
        )

        assertEquals(listOf("ready.txt"), client.list("/").map(RemoteEntry::name))
        assertEquals(1, reconnects)
        assertEquals(1, notifications)
        assertTrue(initial.closed)
    }

    @Test
    fun authenticationFailureIsNotRetried() {
        val state = RemoteState()
        val initial = FakeClient(state, failNextList = IllegalStateException("Authentication denied"))
        var reconnects = 0
        val client = ReconnectingRemoteClient(initial, reconnect = { reconnects += 1; FakeClient(state) })

        assertThrows(IllegalStateException::class.java) { runBlocking { client.list("/") } }
        assertEquals(0, reconnects)
        assertFalse(initial.closed)
    }

    @Test
    fun createDirectoryWhoseReplyBreaksIsRecognizedAfterReconnect() = runBlocking {
        val state = RemoteState()
        val initial = FakeClient(state, breakAfterCreate = true)
        val replacement = FakeClient(state)
        val client = ReconnectingRemoteClient(initial, reconnect = { replacement })

        client.createDirectory("/created")

        assertTrue("/created" in state.directories)
        assertEquals(0, replacement.createCalls)
    }

    @Test
    fun renameWhoseReplyBreaksIsRecognizedAfterReconnect() = runBlocking {
        val state = RemoteState().apply { files["/old.txt"] = "old".toByteArray() }
        val initial = FakeClient(state, breakAfterRename = true)
        val replacement = FakeClient(state)
        val client = ReconnectingRemoteClient(initial, reconnect = { replacement })

        client.rename("/old.txt", "/new.txt")

        assertFalse("/old.txt" in state.files)
        assertEquals("old", state.files.getValue("/new.txt").toString(Charsets.UTF_8))
        assertEquals(0, replacement.renameCalls)
    }

    @Test
    fun interruptedUploadIsRetriedOnTheFreshConnection() = runBlocking {
        val state = RemoteState()
        val source = temporary.newFile("payload.txt").apply { writeText("payload") }
        val initial = FakeClient(state, failNextUpload = SocketException("Connection reset"))
        val replacement = FakeClient(state)
        val client = ReconnectingRemoteClient(initial, reconnect = { replacement })

        client.upload(source, "/payload.txt", OperationContext.background())

        assertEquals("payload", state.files.getValue("/payload.txt").toString(Charsets.UTF_8))
        assertEquals(1, replacement.uploadCalls)
    }

    @Test
    fun classifierRejectsSecurityErrorsButAcceptsSocketBreaks() {
        assertTrue(RemoteFailureClassifier.isTransient(SocketException("Broken pipe")))
        assertTrue(RemoteFailureClassifier.isTransient(IllegalStateException("FTP connection closed without indication")))
        assertFalse(RemoteFailureClassifier.isTransient(IllegalStateException("SSH host key fingerprint mismatch")))
        assertFalse(RemoteFailureClassifier.isTransient(IllegalStateException("Login denied")))
    }

    private data class RemoteState(
        val directories: MutableSet<String> = linkedSetOf("/"),
        val files: MutableMap<String, ByteArray> = linkedMapOf("/ready.txt" to byteArrayOf(1)),
    )

    private class FakeClient(
        private val state: RemoteState,
        private var failNextList: Throwable? = null,
        private val breakAfterCreate: Boolean = false,
        private val breakAfterRename: Boolean = false,
        private var failNextUpload: Throwable? = null,
    ) : RemoteClient {
        var closed = false
        var createCalls = 0
        var renameCalls = 0
        var uploadCalls = 0

        override suspend fun list(path: String): List<RemoteEntry> {
            failNextList?.let { error -> failNextList = null; throw error }
            val normalized = RemotePath.normalize(path)
            return buildList {
                state.directories.filter { it != normalized && parent(it) == normalized }.forEach {
                    add(RemoteEntry(it.substringAfterLast('/'), it, true, 0, null))
                }
                state.files.filterKeys { parent(it) == normalized }.forEach { (file, bytes) ->
                    add(RemoteEntry(file.substringAfterLast('/'), file, false, bytes.size.toLong(), null))
                }
            }
        }

        override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?) {
            localDestination.writeBytes(state.files.getValue(RemotePath.normalize(remotePath)))
        }

        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) {
            uploadCalls += 1
            failNextUpload?.let { error -> failNextUpload = null; throw error }
            state.files[RemotePath.normalize(remotePath)] = localSource.readBytes()
        }

        override suspend fun createDirectory(path: String) {
            createCalls += 1
            state.directories += RemotePath.normalize(path)
            if (breakAfterCreate) throw SocketException("Broken pipe")
        }

        override suspend fun rename(fromPath: String, toPath: String) {
            renameCalls += 1
            state.files.remove(RemotePath.normalize(fromPath))?.let { state.files[RemotePath.normalize(toPath)] = it }
            if (breakAfterRename) throw SocketException("Broken pipe")
        }

        override suspend fun delete(path: String, recursive: Boolean) {
            state.files.remove(RemotePath.normalize(path))
            state.directories.remove(RemotePath.normalize(path))
        }

        override suspend fun close() {
            closed = true
        }

        private fun parent(path: String): String = if (path == "/") "/" else RemotePath.normalize("$path/..")
    }
}
