package com.affilemanager.app.network

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** The remote double records requests only; no server, credentials or real user data. */
class SelectedLinkUploadSafetyTest {
    @Test fun aSelectedFileLinkCannotUploadTheUnselectedReferencedFile() = verifyRootLink(directory = false)
    @Test fun aSelectedFolderLinkCannotUploadTheUnselectedReferencedTree() = verifyRootLink(directory = true)

    private fun verifyRootLink(directory: Boolean) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "upload-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val referenced = File(root, "not-selected").apply {
            if (directory) { check(mkdir()); resolve("private.txt").writeText("not selected") }
            else writeText("not selected")
        }
        val selected = File(root, "selected-link")
        Files.createSymbolicLink(selected.toPath(), referenced.toPath())
        val remote = RecordingRemote()
        try {
            val result = RemoteCopyEngine().upload(listOf(selected), "/", remote, OperationContext.background())
            assertEquals("A selected link was resolved before the no-symlink check", 0, result.copiedRoots)
            assertEquals(1, result.failures.size)
            assertTrue(result.failures.single().message.isNotBlank())
            assertTrue("Unselected data was passed to the upload backend", remote.uploaded.isEmpty())
            assertTrue(remote.created.isEmpty())
            assertTrue(Files.isSymbolicLink(selected.toPath()))
            assertEquals("not selected", if (directory) referenced.resolve("private.txt").readText() else referenced.readText())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    private class RecordingRemote : RemoteClient {
        val uploaded = mutableListOf<String>()
        val created = mutableListOf<String>()
        override suspend fun list(path: String): List<RemoteEntry> = emptyList()
        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) { uploaded += localSource.path }
        override suspend fun createDirectory(path: String) { created += path }
        override suspend fun createFile(path: String) = error("Unexpected create-file request")
        override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?, maxBytes: Long?) = error("Unexpected download")
        override suspend fun rename(fromPath: String, toPath: String) = Unit
        override suspend fun delete(path: String, recursive: Boolean) = Unit
        override suspend fun close() = Unit
    }

    private fun removeFixtureWithoutFollowingLinks(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeFixtureWithoutFollowingLinks)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
