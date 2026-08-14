package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteCopyEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun uploadsFilesAndFoldersWithoutReplacingRemoteNames() = runBlocking {
        val local = temporary.newFolder("upload")
        File(local, "duplicate.txt").writeText("new")
        File(local, "album").apply { mkdir() }
        File(local, "album/picture.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val remote = FakeRemoteClient().apply {
            addDirectory("/target")
            addFile("/target/duplicate.txt", "old".toByteArray())
        }

        val result = RemoteCopyEngine().upload(
            sources = listOf(File(local, "duplicate.txt"), File(local, "album")),
            remoteDirectory = "/target",
            remote = remote,
            operation = OperationContext.background(),
        )

        assertEquals(2, result.copiedRoots)
        assertTrue(result.failures.isEmpty())
        assertArrayEquals("old".toByteArray(), remote.bytes("/target/duplicate.txt"))
        assertArrayEquals("new".toByteArray(), remote.bytes("/target/duplicate (1).txt"))
        assertArrayEquals(byteArrayOf(1, 2, 3), remote.bytes("/target/album/picture.jpg"))
        assertTrue(remote.uploadTargets().all { it.substringAfterLast('/').length <= 46 })
        assertFalse(remote.allPaths().any { ".af-upload" in it })
    }

    @Test
    fun temporarySiblingDoesNotExtendAUserFileName() {
        val target = "/folder/${"x".repeat(255)}"

        val temporary = RemotePath.temporarySibling(target, "af-partial")

        assertTrue(temporary.startsWith("/folder/.af-partial-"))
        assertTrue(temporary.substringAfterLast('/').length <= 47)
        assertFalse(temporary.contains("x".repeat(64)))
    }

    @Test
    fun downloadsFilesAndFoldersWithoutReplacingLocalNames() = runBlocking {
        val local = temporary.newFolder("download")
        File(local, "report.pdf").writeText("old")
        val remote = FakeRemoteClient().apply {
            addDirectory("/source")
            addFile("/source/report.pdf", "new".toByteArray())
            addDirectory("/source/photos")
            addFile("/source/photos/image.png", byteArrayOf(9, 8, 7))
        }

        val result = RemoteCopyEngine().download(
            entries = remote.list("/source"),
            localDirectory = local,
            remote = remote,
            operation = OperationContext.background(),
        )

        assertEquals(2, result.copiedRoots)
        assertTrue(result.failures.isEmpty())
        assertEquals("old", File(local, "report.pdf").readText())
        assertEquals("new", File(local, "report (1).pdf").readText())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(local, "photos/image.png").readBytes())
        assertFalse(local.walkTopDown().any { ".af-download" in it.name })
    }

    @Test
    fun oneFailedRootIsReportedAndTheNextRootStillCopies() = runBlocking {
        val local = temporary.newFolder("continue")
        File(local, "bad.txt").writeText("bad")
        File(local, "good.txt").writeText("good")
        val remote = FakeRemoteClient(failUploadsNamed = setOf("bad.txt")).apply { addDirectory("/target") }

        val result = RemoteCopyEngine().upload(
            sources = listOf(File(local, "bad.txt"), File(local, "good.txt")),
            remoteDirectory = "/target",
            remote = remote,
            operation = OperationContext.background(),
        )

        assertEquals(1, result.copiedRoots)
        assertEquals(listOf("bad.txt"), result.failures.map(RemoteCopyFailure::sourceName))
        assertArrayEquals("good".toByteArray(), remote.bytes("/target/good.txt"))
        assertFalse(remote.allPaths().any { ".af-upload" in it })
    }

    @Test
    fun selectedRootLimitIsEnforcedBeforeRemoteWrites() {
        val local = temporary.newFolder("limit")
        val sources = (0..RemoteCopyEngine.MAX_SELECTED_ROOTS).map { File(local, "file-$it") }
        val remote = FakeRemoteClient().apply { addDirectory("/target") }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RemoteCopyEngine().upload(sources, "/target", remote, OperationContext.background())
            }
        }
        assertEquals(setOf("/", "/target"), remote.allPaths())
    }

    private class FakeRemoteClient(
        private val failUploadsNamed: Set<String> = emptySet(),
    ) : RemoteClient {
        private val directories = linkedSetOf("/")
        private val files = linkedMapOf<String, ByteArray>()
        private val uploads = mutableListOf<String>()

        fun addDirectory(path: String) {
            val normalized = RemotePath.normalize(path)
            require(parent(normalized) in directories)
            directories += normalized
        }

        fun addFile(path: String, bytes: ByteArray) {
            val normalized = RemotePath.normalize(path)
            require(parent(normalized) in directories)
            files[normalized] = bytes.copyOf()
        }

        fun bytes(path: String): ByteArray = requireNotNull(files[RemotePath.normalize(path)]).copyOf()

        fun allPaths(): Set<String> = directories + files.keys

        fun uploadTargets(): List<String> = uploads.toList()

        override suspend fun list(path: String): List<RemoteEntry> {
            val normalized = RemotePath.normalize(path)
            require(normalized in directories)
            val children = ArrayList<RemoteEntry>()
            directories.filter { it != normalized && parent(it) == normalized }.forEach { child ->
                children += RemoteEntry(name(child), child, directory = true, sizeBytes = 0, modifiedAtMillis = 1)
            }
            files.filterKeys { parent(it) == normalized }.forEach { (child, bytes) ->
                children += RemoteEntry(name(child), child, directory = false, sizeBytes = bytes.size.toLong(), modifiedAtMillis = 1)
            }
            return children.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name })
        }

        override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?) {
            val bytes = bytes(remotePath)
            localDestination.writeBytes(bytes)
            operation?.progress(itemDelta = 1, byteDelta = bytes.size.toLong(), currentName = localDestination.name)
        }

        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) {
            if (localSource.name in failUploadsNamed) throw IllegalStateException("Synthetic upload failure")
            val normalized = RemotePath.normalize(remotePath)
            uploads += normalized
            require(parent(normalized) in directories)
            val bytes = localSource.readBytes()
            files[normalized] = bytes
            operation?.progress(itemDelta = 1, byteDelta = bytes.size.toLong(), currentName = localSource.name)
        }

        override suspend fun createDirectory(path: String) {
            val normalized = RemotePath.normalize(path)
            require(normalized !in directories && normalized !in files) { "Already exists" }
            require(parent(normalized) in directories)
            directories += normalized
        }

        override suspend fun rename(fromPath: String, toPath: String) {
            val from = RemotePath.normalize(fromPath)
            val to = RemotePath.normalize(toPath)
            require(to !in directories && to !in files) { "Already exists" }
            require(parent(to) in directories)
            files.remove(from)?.let { bytes -> files[to] = bytes; return }
            require(from in directories)
            val movedDirectories = directories.filter { it == from || it.startsWith("$from/") }
            val movedFiles = files.filterKeys { it.startsWith("$from/") }
            directories.removeAll(movedDirectories.toSet())
            movedDirectories.forEach { old -> directories += to + old.removePrefix(from) }
            movedFiles.keys.forEach(files::remove)
            movedFiles.forEach { (old, bytes) -> files[to + old.removePrefix(from)] = bytes }
        }

        override suspend fun delete(path: String, recursive: Boolean) {
            val normalized = RemotePath.normalize(path)
            if (files.remove(normalized) != null) return
            if (normalized !in directories || normalized == "/") throw IllegalArgumentException("Missing")
            val childDirectories = directories.filter { it.startsWith("$normalized/") }
            val childFiles = files.keys.filter { it.startsWith("$normalized/") }
            require(recursive || (childDirectories.isEmpty() && childFiles.isEmpty()))
            directories.removeAll(childDirectories.toSet())
            directories.remove(normalized)
            childFiles.forEach(files::remove)
        }

        override suspend fun close() = Unit

        private fun parent(path: String): String = if (path == "/") "/" else RemotePath.normalize("$path/..")
        private fun name(path: String): String = path.substringAfterLast('/')
    }
}
