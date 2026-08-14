package com.affilemanager.app.sync

import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun reportOnlyKeepsConflictVisible() = runBlocking {
        val local = temporary.newFolder("local")
        File(local, "note.txt").writeText("local")
        val remote = FakeRemote(mutableMapOf("/note.txt" to "remote content".toByteArray()))

        val preview = SyncEngine().preview(local, "/", remote, SyncMode.TWO_WAY, SyncConflictPolicy.REPORT_ONLY)

        assertTrue(preview.actions.any { it.type == SyncActionType.CONFLICT && it.relativePath == "note.txt" })
    }

    @Test
    fun keepBothCreatesTwoExplicitUniqueActions() = runBlocking {
        val local = temporary.newFolder("local-both")
        File(local, "note.txt").writeText("local")
        val remote = FakeRemote(mutableMapOf("/note.txt" to "remote content".toByteArray()))

        val preview = SyncEngine().preview(local, "/", remote, SyncMode.TWO_WAY, SyncConflictPolicy.KEEP_BOTH)

        assertFalse(preview.actions.any { it.type == SyncActionType.CONFLICT })
        assertEquals(setOf(SyncActionType.UPLOAD, SyncActionType.DOWNLOAD), preview.actions.map { it.type }.toSet())
        assertEquals(2, preview.actions.mapNotNull { it.targetRelativePath }.distinct().size)
    }

    private class FakeRemote(private val files: MutableMap<String, ByteArray>) : RemoteClient {
        override suspend fun list(path: String): List<RemoteEntry> = files.map { (filePath, bytes) ->
            RemoteEntry(filePath.substringAfterLast('/'), filePath, false, bytes.size.toLong(), 1L)
        }

        override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?) {
            localDestination.writeBytes(requireNotNull(files[remotePath]))
        }

        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) {
            files[remotePath] = localSource.readBytes()
        }

        override suspend fun createDirectory(path: String) = Unit
        override suspend fun rename(fromPath: String, toPath: String) { files[toPath] = requireNotNull(files.remove(fromPath)) }
        override suspend fun delete(path: String, recursive: Boolean) { files.remove(path) }
        override suspend fun close() = Unit
    }
}
