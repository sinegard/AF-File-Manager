package com.affilemanager.app.operations

import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableTransferSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val planner = DurableTransferPlanner()
    private val engine = DurableTransferEngine()

    private fun plan(sources: List<File>, destination: File, move: Boolean = false,
                     policy: ConflictPolicy = ConflictPolicy.REPLACE) = planner.create(
        sources.map(File::getAbsolutePath), destination.absolutePath, move, policy,
        TransferVerification.SIZE, TransferFailurePolicy.STOP,
    )

    private suspend fun execute(plan: DurableTransferPlan, onState: (DurableTransferState) -> Unit = {}) =
        engine.execute(plan, DurableTransferState(planId = plan.id), object : DurableTransferStateWriter {
            override fun saveState(state: DurableTransferState) = onState(state)
        }, OperationContext.background())

    @Test fun replaceCopiesDifferentContentEvenWhenLengthsMatch() = runBlocking {
        val source = temporary.newFile("same-length.txt").apply { writeText("NEW!") }
        val destination = temporary.newFolder("destination")
        val target = File(destination, source.name).apply { writeText("OLD!") }
        execute(plan(listOf(source), destination))
        assertEquals("NEW!", target.readText())
        assertEquals("NEW!", source.readText())
    }

    @Test fun moveWithSkipKeepsEverySkippedSource() = runBlocking {
        val skipped = temporary.newFile("skipped.txt").apply { writeText("source must stay") }
        val moved = temporary.newFile("moved.txt").apply { writeText("move me") }
        val destination = temporary.newFolder("destination")
        val existing = File(destination, skipped.name).apply { writeText("existing must stay") }
        execute(plan(listOf(skipped, moved), destination, move = true, policy = ConflictPolicy.SKIP))
        assertTrue("A skipped source must never be deleted", skipped.isFile)
        assertEquals("source must stay", skipped.readText())
        assertEquals("existing must stay", existing.readText())
        assertFalse(moved.exists())
        assertEquals("move me", File(destination, moved.name).readText())
    }

    @Test fun movingOntoItselfNeverDeletesTheSource() = runBlocking {
        val source = temporary.newFile("same-place.txt").apply { writeText("keep me") }
        runCatching { execute(plan(listOf(source), requireNotNull(source.parentFile), move = true)) }
        assertTrue("Moving a file to its own parent must not delete it", source.isFile)
        assertEquals("keep me", source.readText())
    }

    @Test fun moveDoesNotDeleteFilesAddedAfterPlanning() = runBlocking {
        val source = temporary.newFolder("source")
        File(source, "planned.txt").writeText("planned")
        val destination = temporary.newFolder("destination")
        val transfer = plan(listOf(source), destination, move = true)
        val added = File(source, "added-later.txt").apply { writeText("not in the plan") }
        runCatching { execute(transfer) }
        assertTrue("An unplanned file must not be swept up by recursive move deletion", added.isFile)
        assertEquals("not in the plan", added.readText())
    }

    @Test fun newlyAppearedDestinationIsNotTreatedAsOurCompletedCopy() = runBlocking {
        val source = temporary.newFile("raced.txt").apply { writeText("AAAA") }
        val destination = temporary.newFolder("destination")
        val transfer = plan(listOf(source), destination, move = true, policy = ConflictPolicy.KEEP_BOTH)
        val appeared = File(destination, source.name).apply { writeText("BBBB") }
        val failure = runCatching { execute(transfer) }.exceptionOrNull()
        assertTrue("An unrelated same-size destination must produce a conflict", failure != null)
        assertTrue(source.isFile)
        assertEquals("AAAA", source.readText())
        assertEquals("BBBB", appeared.readText())
    }

    @Test fun sameLengthSourceEditedAfterCopyIsNotDeleted() = runBlocking {
        val source = temporary.newFile("edited.txt").apply { writeText("AAAA") }
        val destination = temporary.newFolder("destination")
        val transfer = plan(listOf(source), destination, move = true)
        val failure = runCatching {
            execute(transfer) { state ->
                if (state.phase == TransferPhase.DELETE_SOURCES) {
                    source.writeText("BBBB")
                    assertTrue(source.setLastModified(transfer.items.single().modifiedAtMillis + 10_000))
                }
            }
        }.exceptionOrNull()
        assertTrue("An edited source must stop deletion even in size verification mode", failure != null)
        assertTrue(source.isFile)
        assertEquals("BBBB", source.readText())
        assertEquals("AAAA", File(destination, source.name).readText())
    }

    @Test fun keepBothCanStillCopyAFileWithinItsOwnFolder() = runBlocking {
        val source = temporary.newFile("original.txt").apply { writeText("keep both") }
        val destination = requireNotNull(source.parentFile)
        execute(plan(listOf(source), destination, policy = ConflictPolicy.KEEP_BOTH))
        assertEquals("keep both", source.readText())
        assertEquals("keep both", File(destination, "original (1).txt").readText())
    }

    @Test fun destinationCreatedDuringStreamingIsNeverOverwritten() = runBlocking {
        val source = temporary.newFile("raced-during-write.txt").apply { writeText("original") }
        val destination = temporary.newFolder("destination")
        val transfer = plan(listOf(source), destination, move = true, policy = ConflictPolicy.KEEP_BOTH)
        val target = File(destination, source.name)
        var progress = OperationSnapshot(transfer.id, "test", OperationStatus.RUNNING)
        val context = OperationContext(transfer.id, MutableStateFlow(false)) { update ->
            progress = update(progress)
            if (progress.completedBytes > 0 && !target.exists()) target.writeText("unrelated new file")
        }
        val failure = runCatching {
            engine.execute(transfer, DurableTransferState(planId = transfer.id), RecordingWriter(), context)
        }.exceptionOrNull()
        assertTrue("A destination created while copying must be reported as a conflict", failure != null)
        assertEquals("unrelated new file", target.readText())
        assertEquals("original", source.readText())
    }

    @Test fun cancellationAfterCheckpointAndRetryDoesNotReuseRestoredOldTargets() = runBlocking {
        val sources = (0 until 34).map { index -> temporary.newFile("source-$index.txt").apply { writeText("NEW-$index") } }
        val destination = temporary.newFolder("destination")
        sources.forEachIndexed { index, source -> File(destination, source.name).writeText("OLD-$index") }
        val transfer = plan(sources, destination, move = true)
        val writer = RecordingWriter()
        var progress = OperationSnapshot(transfer.id, "test", OperationStatus.RUNNING)
        val context = OperationContext(transfer.id, MutableStateFlow(false)) { update ->
            progress = update(progress)
            if (progress.completedItems == 33) throw CancellationException("test cancellation after checkpoint")
        }
        val failure = runCatching {
            engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, context)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(32, writer.latest.nextItemIndex)
        engine.restoreBackupsAfterCopyCancellation(transfer, writer.latest, writer)
        val retry = engine.prepareRetry(writer.latest.copy(status = DurableTransferStatus.CANCELLED))
        engine.execute(transfer, retry, writer, OperationContext.background())
        sources.forEachIndexed { index, source ->
            assertEquals("NEW-$index", File(destination, source.name).readText())
            assertFalse(source.exists())
        }
    }

    @Test fun retryAfterResolvedConflictFinishesMoveWithoutStaleFailure() = runBlocking {
        val source = temporary.newFile("retry.txt").apply { writeText("original") }
        val destination = temporary.newFolder("destination")
        val transfer = plan(listOf(source), destination, move = true, policy = ConflictPolicy.KEEP_BOTH)
        val target = File(destination, source.name).apply { writeText("conflict") }
        val writer = RecordingWriter()
        val failure = runCatching {
            engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, OperationContext.background())
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals(DurableTransferStatus.FAILED, writer.latest.status)
        assertTrue(target.delete()) // The user resolves the conflict before retrying.
        val result = engine.execute(transfer, engine.prepareRetry(writer.latest), writer, OperationContext.background())
        assertEquals(DurableTransferStatus.COMPLETED, result.status)
        assertEquals(emptyList<Int>(), result.failedItemIndices)
        assertEquals("original", target.readText())
        assertFalse(source.exists())
    }

    private class RecordingWriter : DurableTransferStateWriter {
        lateinit var latest: DurableTransferState
        override fun saveState(state: DurableTransferState) { latest = state }
    }

    @Test fun cancellationNeverErasesAChangedDestinationOrItsRecoveryBackup() = runBlocking {
        val source = temporary.newFile("cancel-conflict.txt").apply { writeText("new version") }
        val destination = temporary.newFolder("destination")
        val target = File(destination, source.name).apply { writeText("old version") }
        val transfer = plan(listOf(source), destination, move = true)
        val writer = RecordingWriter()
        var progress = OperationSnapshot(transfer.id, "test", OperationStatus.RUNNING)
        val context = OperationContext(transfer.id, MutableStateFlow(false)) { update ->
            progress = update(progress)
            if (progress.completedItems == 1) {
                target.writeText("written by someone else")
                throw CancellationException("cancel after concurrent change")
            }
        }
        assertTrue(runCatching {
            engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, context)
        }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { engine.restoreBackupsAfterCopyCancellation(transfer, writer.latest, writer) }.isFailure)
        assertEquals(0, writer.latest.nextItemIndex)
        assertEquals("written by someone else", target.readText())
        assertEquals("new version", source.readText())
        assertEquals("old version", File(destination, ".af-backup-${transfer.id}-0").readText())
    }
}
