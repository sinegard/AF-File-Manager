package com.affilemanager.app.editing

import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteEditSaverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun remoteChangeProducesConflictWithoutUploadingWorkingCopy() = runBlocking {
        val original = "server version".toByteArray()
        val changedElsewhere = "changed elsewhere".toByteArray()
        val remote = FakeRemoteClient("/notes.txt", original)
        val store = EditSessionStore(temporaryFolder.newFolder("cache-conflict"))
        val session = remoteSession(store, original)
        val edited = store.stageText(session, "my edit")
        remote.replace(changedElsewhere)

        val result = RemoteEditSaver(store).saveOrigin(edited, remote, forceOverwrite = false)

        assertTrue(result is EditSaveResult.Conflict)
        assertArrayEquals(changedElsewhere, remote.bytes())
        assertTrue(remote.uploadCount == 0)
    }

    @Test
    fun explicitOverwriteUploadsAndVerifiesWorkingCopy() = runBlocking {
        val original = "server version".toByteArray()
        val remote = FakeRemoteClient("/notes.txt", original)
        val store = EditSessionStore(temporaryFolder.newFolder("cache-overwrite"))
        val session = remoteSession(store, original)
        val edited = store.stageText(session, "my edit")
        remote.replace("changed elsewhere".toByteArray())

        val result = RemoteEditSaver(store).saveOrigin(edited, remote, forceOverwrite = true)

        assertTrue(result is EditSaveResult.Saved)
        assertArrayEquals("my edit".toByteArray(), remote.bytes())
        assertTrue(edited.workingRevision.hasSameContent((result as EditSaveResult.Saved).revision))
        assertTrue(remote.uploadCount == 1)
    }

    private fun remoteSession(store: EditSessionStore, original: ByteArray): EditSession {
        val downloaded = temporaryFolder.newFile("download-${System.nanoTime()}.txt").apply {
            writeBytes(original)
            setLastModified(1_000L)
        }
        return store.prepareFromFile(
            sourceKey = "remote|profile|/notes.txt",
            displayName = "notes.txt",
            mimeType = "text/plain",
            sourceFile = downloaded,
            origin = EditOrigin.Remote(
                profileId = "profile",
                connectionName = "Test server",
                path = "/notes.txt",
            ),
            modifiedAtMillis = 1_000L,
            internalTextEditor = true,
        )
    }

    private class FakeRemoteClient(
        private val remotePath: String,
        initialBytes: ByteArray,
    ) : RemoteClient {
        private var content = initialBytes.copyOf()
        private var modifiedAtMillis = 1_000L
        var uploadCount: Int = 0
            private set

        fun replace(bytes: ByteArray) {
            content = bytes.copyOf()
            modifiedAtMillis += 1_000L
        }

        fun bytes(): ByteArray = content.copyOf()

        override suspend fun list(path: String): List<RemoteEntry> {
            require(RemotePath.normalize(path) == RemotePath.normalize("$remotePath/.."))
            return listOf(
                RemoteEntry(
                    name = RemotePath.normalize(remotePath).substringAfterLast('/'),
                    path = RemotePath.normalize(remotePath),
                    directory = false,
                    sizeBytes = content.size.toLong(),
                    modifiedAtMillis = modifiedAtMillis,
                ),
            )
        }

        override suspend fun download(
            remotePath: String,
            localDestination: File,
            operation: OperationContext?,
            maxBytes: Long?,
        ) {
            require(RemotePath.normalize(remotePath) == RemotePath.normalize(this.remotePath))
            require(maxBytes == null || content.size <= maxBytes)
            localDestination.writeBytes(content)
        }

        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) {
            require(RemotePath.normalize(remotePath) == RemotePath.normalize(this.remotePath))
            content = localSource.readBytes()
            modifiedAtMillis += 1_000L
            uploadCount += 1
        }

        override suspend fun createDirectory(path: String) = error("Not used")
        override suspend fun rename(fromPath: String, toPath: String) = error("Not used")
        override suspend fun delete(path: String, recursive: Boolean) = error("Not used")
        override suspend fun close() = Unit
    }
}
