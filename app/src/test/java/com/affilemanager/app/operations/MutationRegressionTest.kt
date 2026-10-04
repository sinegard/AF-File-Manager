package com.affilemanager.app.operations

import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MutationRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mergingPublishesOnlyChangedFilesAndDoesNotRescanUnrelatedDestinationChildren() = runBlocking {
        val source = temporary.newFolder("source").apply { resolve("new.txt").writeText("new") }
        val destination = temporary.newFolder("destination")
        val existing = File(destination, source.name).apply { mkdir(); resolve("unrelated.txt").writeText("keep") }
        val changed = mutableListOf<File>()
        LocalFileOperator(onMutation = { changed += it }).copyOrMove(listOf(source.path), destination.path,
            false, ConflictPolicy.MERGE, OperationContext.background())
        assertEquals(listOf(File(existing, "new.txt")), changed)
        assertEquals("keep", existing.resolve("unrelated.txt").readText())
        assertTrue(source.resolve("new.txt").isFile)
    }

    @Test fun duplicateProtectionRetainsOneCopyButDoesNotBlockUnrelatedSelection() {
        val first = temporary.newFile("a.txt")
        val second = temporary.newFile("b.txt")
        val other = temporary.newFile("c.txt")
        val result = CleanupCopyProtection.select(listOf(first, second, other), listOf(listOf(first.path, second.path)))
        assertEquals(listOf(first), result.retained)
        assertEquals(listOf(second, other), result.movable)
        assertEquals(listOf(first, second, other), CleanupCopyProtection.select(listOf(first, second, other),
            listOf(listOf(first.path, second.path)), keepOneCopy = false).movable)
        assertEquals(listOf(second), CleanupCopyProtection.select(listOf(second), listOf(listOf(first.path, second.path))).movable)
    }

    @Test fun protectingAContainedCopyRetainsItsFolderInsteadOfDeletingTheLastCopy() {
        val directory = temporary.newFolder("photos")
        val first = File(directory, "a.jpg")
        val second = temporary.newFile("b.jpg")
        val result = CleanupCopyProtection.select(listOf(directory, second), listOf(listOf(first.path, second.path)))
        // Deterministically retain the alphabetically first copy, regardless of selection order.
        assertEquals(1, result.retained.size)
        assertEquals(1, result.movable.size)
        assertTrue(result.retained.single() == directory || result.retained.single() == second)
    }

    @Test fun aSkippedMoveLeavesTheOriginalAndDestinationIntact() = runBlocking {
        val sourceDir = temporary.newFolder("source")
        val destination = temporary.newFolder("destination")
        val source = File(sourceDir, "same.txt").apply { writeText("original") }
        val target = File(destination, "same.txt").apply { writeText("existing") }
        LocalFileOperator().copyOrMove(listOf(source.path), destination.path, true, ConflictPolicy.SKIP, OperationContext.background())
        assertEquals("original", source.readText())
        assertEquals("existing", target.readText())
    }

    @Test fun replacePreservesTheOldFileWhenCopyingIsCancelled() = runBlocking {
        val sourceDir = temporary.newFolder("replace-source")
        val destination = temporary.newFolder("replace-destination")
        val source = File(sourceDir, "same.txt").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 7 }) }
        val target = File(destination, "same.txt").apply { writeText("existing") }
        val cancelling = OperationContext("cancel", kotlinx.coroutines.flow.MutableStateFlow(false)) { transform ->
            val state = OperationSnapshot("cancel", "copy", OperationStatus.RUNNING).transform()
            if (state.completedBytes > 0) {
                assertEquals("existing", target.readText())
                throw kotlinx.coroutines.CancellationException("cancel after the first copied block")
            }
        }
        assertTrue(runCatching { LocalFileOperator().copyOrMove(listOf(source.path), destination.path,
            false, ConflictPolicy.REPLACE, cancelling) }.isFailure)
        assertEquals("existing", target.readText())
        assertEquals(2L * 1024 * 1024, source.length())
    }

    @Test fun lateIndexFailureCannotEraseTheOriginalOperationError() = runBlocking {
        val original = java.io.IOException("copy failed")
        val paths = listOf(temporary.newFile("committed.txt"))
        val failure = runCatching {
            publishingStorageChanges({ throw java.io.IOException("index failed") }, { paths }) { throw original }
        }.exceptionOrNull()
        assertSame(original, failure)
        assertEquals("index failed", failure!!.suppressed.single().message)
    }

    @Test fun copyingOntoTheSourceCannotDeleteIt() = runBlocking {
        val source = temporary.newFile("self.txt").apply { writeText("kept") }
        assertTrue(runCatching { LocalFileOperator().copyOrMove(listOf(source.path), source.parent, true,
            ConflictPolicy.REPLACE, OperationContext.background()) }.isFailure)
        assertEquals("kept", source.readText())
    }
}
