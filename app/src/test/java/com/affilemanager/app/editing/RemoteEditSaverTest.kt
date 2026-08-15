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

    @Test
    fun saveAsReportsExistingTargetAndKeepBothChoosesANewName() = runBlocking {
        val original = "server version".toByteArray()
        val remote = FakeRemoteClient("/notes.txt", original).apply {
            add("/copy.txt", "occupied".toByteArray())
        }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-save-as"))
        val edited = store.stageText(remoteSession(store, original), "my edit")
        val saver = RemoteEditSaver(store)

        val conflict = saver.saveAs(
            session = edited,
            client = remote,
            profileId = "profile",
            connectionName = "Test server",
            directoryPath = "/",
            requestedName = "copy.txt",
            policy = EditExistingPolicy.ASK,
        )
        assertTrue(conflict is EditSaveAsResult.Conflict)
        assertArrayEquals("occupied".toByteArray(), remote.bytes("/copy.txt"))

        val saved = saver.saveAs(
            session = edited,
            client = remote,
            profileId = "profile",
            connectionName = "Test server",
            directoryPath = "/",
            requestedName = "copy.txt",
            policy = EditExistingPolicy.KEEP_BOTH,
        ) as EditSaveAsResult.Saved
        assertTrue((saved.destination as EditDestination.Remote).path == "/copy (1).txt")
        assertArrayEquals("my edit".toByteArray(), remote.bytes("/copy (1).txt"))
    }

    @Test
    fun changeWhileStagingStillStopsTheRemoteReplacement() = runBlocking {
        val original = "server version".toByteArray()
        val changedDuringSave = "changed during save".toByteArray()
        val remote = FakeRemoteClient("/notes.txt", original).apply {
            replaceOriginalAfterNextUpload(changedDuringSave)
        }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-race"))
        val edited = store.stageText(remoteSession(store, original), "my edit")

        val result = RemoteEditSaver(store).saveOrigin(edited, remote, forceOverwrite = false)

        assertTrue(result is EditSaveResult.Conflict)
        assertArrayEquals(changedDuringSave, remote.bytes())
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
        private val files = linkedMapOf(RemotePath.normalize(remotePath) to initialBytes.copyOf())
        private val modified = linkedMapOf(RemotePath.normalize(remotePath) to 1_000L)
        private var clock = 1_000L
        private var replaceAfterUpload: ByteArray? = null
        var uploadCount: Int = 0
            private set

        fun replace(bytes: ByteArray) {
            add(remotePath, bytes)
        }

        fun add(path: String, bytes: ByteArray) {
            clock += 1_000L
            val normalized = RemotePath.normalize(path)
            files[normalized] = bytes.copyOf()
            modified[normalized] = clock
        }

        fun bytes(path: String = remotePath): ByteArray = requireNotNull(files[RemotePath.normalize(path)]).copyOf()

        fun replaceOriginalAfterNextUpload(bytes: ByteArray) {
            replaceAfterUpload = bytes.copyOf()
        }

        override suspend fun list(path: String): List<RemoteEntry> {
            val parent = RemotePath.normalize(path)
            return files.mapNotNull { (filePath, content) ->
                if (RemotePath.normalize("$filePath/..") != parent) return@mapNotNull null
                RemoteEntry(
                    name = filePath.substringAfterLast('/'),
                    path = filePath,
                    directory = false,
                    sizeBytes = content.size.toLong(),
                    modifiedAtMillis = modified[filePath],
                )
            }
        }

        override suspend fun download(
            remotePath: String,
            localDestination: File,
            operation: OperationContext?,
            maxBytes: Long?,
        ) {
            val content = requireNotNull(files[RemotePath.normalize(remotePath)])
            require(maxBytes == null || content.size <= maxBytes)
            localDestination.writeBytes(content)
        }

        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) {
            add(remotePath, localSource.readBytes())
            uploadCount += 1
            replaceAfterUpload?.let { replacement ->
                replaceAfterUpload = null
                add(this.remotePath, replacement)
            }
        }

        override suspend fun createDirectory(path: String) = error("Not used")
        override suspend fun rename(fromPath: String, toPath: String) {
            val from = RemotePath.normalize(fromPath)
            val to = RemotePath.normalize(toPath)
            require(to !in files)
            val content = requireNotNull(files.remove(from))
            val modifiedAt = modified.remove(from)
            files[to] = content
            modified[to] = modifiedAt ?: clock
        }

        override suspend fun delete(path: String, recursive: Boolean) {
            val normalized = RemotePath.normalize(path)
            files.remove(normalized)
            modified.remove(normalized)
        }
        override suspend fun close() = Unit
    }
}
