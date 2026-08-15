package com.affilemanager.app.ui.preview

import com.affilemanager.app.network.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RemotePreviewCacheTest {
    @Test
    fun createsContainedDestinationWithoutUsingRemoteNameAsAPath() = withTemporaryDirectory { cacheRoot ->
        val cache = RemotePreviewCache(cacheRoot) { "request-one" }

        val destination = cache.createDestination(
            profileId = "profile",
            entry = entry(name = "../../private.PDF", sizeBytes = 4),
        )

        assertEquals("content.pdf", destination.name)
        val requestDirectory = requireNotNull(destination.parentFile)
        assertEquals(cacheRoot.resolve("remote-previews").canonicalFile, requireNotNull(requestDirectory.parentFile).canonicalFile)
    }

    @Test
    fun rejectsFoldersAndFilesAboveThePreviewLimit() = withTemporaryDirectory { cacheRoot ->
        val cache = RemotePreviewCache(cacheRoot)

        assertThrows(IllegalArgumentException::class.java) {
            cache.createDestination("profile", entry(name = "folder", sizeBytes = 0, directory = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache.createDestination(
                "profile",
                entry(name = "large.bin", sizeBytes = RemotePreviewCache.MAX_FILE_BYTES + 1),
            )
        }
    }

    @Test
    fun openingAnotherPreviewRemovesThePreviousStagingCopy() = withTemporaryDirectory { cacheRoot ->
        var token = 0
        val cache = RemotePreviewCache(cacheRoot) { "request-${token++}" }

        val first = cache.createDestination("profile", entry(name = "first.txt", sizeBytes = 1))
        first.writeBytes(byteArrayOf(1))
        cache.validateCompleted(first)

        val second = cache.createDestination("profile", entry(name = "second.txt", sizeBytes = 1))

        assertFalse(first.exists())
        assertTrue(second.parentFile?.isDirectory == true)
    }

    @Test
    fun discardAndNewStoreRemovePrivateStagingCopies() = withTemporaryDirectory { cacheRoot ->
        val cache = RemotePreviewCache(cacheRoot) { "active" }
        val active = cache.createDestination("profile", entry(name = "active.txt", sizeBytes = 1))
        active.writeBytes(byteArrayOf(1))
        cache.validateCompleted(active)

        assertTrue(cache.discard(active))
        assertFalse(active.exists())

        val stale = cache.createDestination("profile", entry(name = "stale.txt", sizeBytes = 1))
        stale.writeBytes(byteArrayOf(2))
        cache.validateCompleted(stale)

        RemotePreviewCache(cacheRoot)

        assertFalse(stale.exists())
    }

    private fun entry(name: String, sizeBytes: Long, directory: Boolean = false) = RemoteEntry(
        name = name,
        path = "/$name",
        directory = directory,
        sizeBytes = sizeBytes,
        modifiedAtMillis = 1,
    )

    private fun withTemporaryDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("remote-preview-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
