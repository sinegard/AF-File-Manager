package com.affilemanager.app.operations

import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableTransferTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val planner = DurableTransferPlanner()
    private val engine = DurableTransferEngine()

    @Test
    fun shaVerifiedCopyCompletesAndPreservesSource() = runBlocking {
        val source = temporary.newFile("source.bin").apply { writeBytes(ByteArray(1024) { (it % 251).toByte() }) }
        val destination = temporary.newFolder("destination")
        val plan = planner.create(
            listOf(source.absolutePath), destination.absolutePath, move = false,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val writer = RecordingWriter()

        val state = engine.execute(plan, DurableTransferState(planId = plan.id), writer, OperationContext.background())

        assertEquals(DurableTransferStatus.COMPLETED, state.status)
        assertTrue(source.exists())
        assertArrayEquals(source.readBytes(), File(destination, source.name).readBytes())
        assertFalse(destination.listFiles().orEmpty().any { it.name.contains(".af-") })
    }

    @Test
    fun interruptedMoveResumesFromCheckpointThenDeletesSources() = runBlocking {
        val sourceRoot = temporary.newFolder("move-source")
        val first = File(sourceRoot, "one.txt").apply { writeText("one") }
        File(sourceRoot, "two.txt").writeText("two")
        val destination = temporary.newFolder("move-destination")
        val plan = planner.create(
            listOf(sourceRoot.absolutePath), destination.absolutePath, move = true,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val targetRoot = File(destination, sourceRoot.name).apply { mkdir() }
        File(targetRoot, first.name).writeBytes(first.readBytes())
        val firstFileIndex = plan.items.indexOfFirst { it.sourcePath == first.canonicalPath }
        val checkpoint = DurableTransferState(
            planId = plan.id,
            status = DurableTransferStatus.INTERRUPTED,
            nextItemIndex = firstFileIndex + 1,
        )

        val state = engine.execute(plan, checkpoint, RecordingWriter(), OperationContext.background())

        assertEquals(DurableTransferStatus.COMPLETED, state.status)
        assertFalse(sourceRoot.exists())
        assertEquals("one", File(targetRoot, "one.txt").readText())
        assertEquals("two", File(targetRoot, "two.txt").readText())
    }

    @Test
    fun changedTargetBlocksMoveDeletionAndLeavesSource() = runBlocking {
        val source = temporary.newFile("important.txt").apply { writeText("original") }
        val destination = temporary.newFolder("changed-target")
        val plan = planner.create(
            listOf(source.absolutePath), destination.absolutePath, move = true,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        File(destination, source.name).writeText("tampered")
        val deleteCheckpoint = DurableTransferState(
            planId = plan.id,
            status = DurableTransferStatus.INTERRUPTED,
            phase = TransferPhase.DELETE_SOURCES,
            nextItemIndex = plan.items.size,
        )

        val failure = runCatching {
            engine.execute(plan, deleteCheckpoint, RecordingWriter(), OperationContext.background())
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(source.exists())
        assertEquals("original", source.readText())
    }

    @Test
    fun skipAndContinueRecordsStaleSourceWithoutWritingTarget() = runBlocking {
        val source = temporary.newFile("stale.txt").apply { writeText("before") }
        val destination = temporary.newFolder("skip-errors")
        val plan = planner.create(
            listOf(source.absolutePath), destination.absolutePath, move = false,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SIZE,
            failurePolicy = TransferFailurePolicy.SKIP_AND_CONTINUE,
        )
        source.writeText("after-plan-and-longer")

        val state = engine.execute(plan, DurableTransferState(planId = plan.id), RecordingWriter(), OperationContext.background())

        assertEquals(DurableTransferStatus.COMPLETED_WITH_ERRORS, state.status)
        assertEquals(listOf(0), state.failedItemIndices)
        assertFalse(File(destination, source.name).exists())
    }

    @Test
    fun keepBothTargetsAreFrozenDuringPlanning() {
        val firstRoot = temporary.newFolder("first")
        val secondRoot = temporary.newFolder("second")
        val first = File(firstRoot, "same.txt").apply { writeText("one") }
        val second = File(secondRoot, "same.txt").apply { writeText("two") }
        val destination = temporary.newFolder("keep-both")
        File(destination, "same.txt").writeText("existing")

        val plan = planner.create(
            listOf(first.absolutePath, second.absolutePath), destination.absolutePath, move = false,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SIZE,
            failurePolicy = TransferFailurePolicy.STOP,
        )

        assertEquals(listOf("same (1).txt", "same (2).txt"), plan.items.map { File(it.targetPath).name })
    }

    @Test
    fun moveRejectsSkipAndContinuePolicy() {
        val source = temporary.newFile("unsafe.txt")
        val destination = temporary.newFolder("unsafe-destination")

        val failure = runCatching {
            planner.create(
                listOf(source.absolutePath), destination.absolutePath, move = true,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                verification = TransferVerification.SIZE,
                failurePolicy = TransferFailurePolicy.SKIP_AND_CONTINUE,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(source.exists())
    }

    private class RecordingWriter : DurableTransferStateWriter {
        val states = mutableListOf<DurableTransferState>()
        override fun saveState(state: DurableTransferState) {
            states += state
        }
    }
}
