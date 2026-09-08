package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RemoteSelectionInfoScannerTest {
    @Test fun countsNestedRemoteContentWithoutDoubleCountingNestedSelections() = runTest {
        val entries = mapOf(
            "/folder" to listOf(RemoteEntry("nested", "/folder/nested", true, 0, null), RemoteEntry("a.txt", "/folder/a.txt", false, 5, null)),
            "/folder/nested" to listOf(RemoteEntry("b.txt", "/folder/nested/b.txt", false, 7, null)),
        )
        val client = FakeRemoteClient(entries)
        val summary = RemoteSelectionInfoScanner().scan(listOf(
            RemoteEntry("folder", "/folder", true, 0, null),
            RemoteEntry("b.txt", "/folder/nested/b.txt", false, 7, null),
        ), client)
        assertEquals(2, summary.fileCount)
        assertEquals(1, summary.folderCount)
        assertEquals(12L, summary.totalBytes)
        assertEquals(1, summary.selectedItems)
    }

    @Test fun listingFailureProducesAnHonestPartialSummary() = runTest {
        val summary = RemoteSelectionInfoScanner().scan(listOf(RemoteEntry("folder", "/folder", true, 0, null)), FakeRemoteClient(emptyMap()))
        assertFalse(summary.complete)
    }

    private class FakeRemoteClient(private val entries: Map<String, List<RemoteEntry>>) : RemoteClient {
        override suspend fun list(path: String) = entries[path] ?: error("unavailable")
        override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?, maxBytes: Long?) = Unit
        override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) = Unit
        override suspend fun createDirectory(path: String) = Unit
        override suspend fun rename(fromPath: String, toPath: String) = Unit
        override suspend fun delete(path: String, recursive: Boolean) = Unit
        override suspend fun close() = Unit
    }
}
