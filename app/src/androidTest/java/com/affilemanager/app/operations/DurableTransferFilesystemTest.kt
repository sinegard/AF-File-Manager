package com.affilemanager.app.operations

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DurableTransferFilesystemTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val planner = DurableTransferPlanner()
    private val engine = DurableTransferEngine()
    private val writer = object : DurableTransferStateWriter { override fun saveState(state: DurableTransferState) = Unit }

    private fun plan(source: List<File>, destination: File, policy: ConflictPolicy) = planner.create(
        source.map(File::getPath), destination.path, true, policy, TransferVerification.SIZE, TransferFailurePolicy.STOP,
    )

    @Test fun sharedStorageMovePreservesSkippedFilesAndCopiesEmptyFiles() = runBlocking {
        val root = File(requireNotNull(context.getExternalFilesDir(null)), "transfer-safety-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { mkdir() }
            val destination = File(root, "destination").apply { mkdir() }
            val skipped = File(source, "same.txt").apply { writeText("source") }
            val old = File(destination, "same.txt").apply { writeText("target") }
            val empty = File(source, "empty.bin").apply { createNewFile() }
            val transfer = plan(listOf(skipped, empty), destination, ConflictPolicy.SKIP)
            val result = engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, OperationContext.background())
            assertEquals(DurableTransferStatus.COMPLETED, result.status)
            assertEquals("source", skipped.readText())
            assertEquals("target", old.readText())
            assertFalse(empty.exists())
            assertTrue(File(destination, "empty.bin").isFile)
            assertEquals(0L, File(destination, "empty.bin").length())
        } finally { root.deleteRecursively() }
    }

    @Test fun sharedStorageDestinationCreatedDuringCopyIsNotReplaced() = runBlocking {
        val root = File(requireNotNull(context.getExternalFilesDir(null)), "transfer-race-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "source.bin").apply { writeBytes(ByteArray(512 * 1024) { 42 }) }
            val destination = File(root, "destination").apply { mkdir() }
            val target = File(destination, source.name)
            val transfer = plan(listOf(source), destination, ConflictPolicy.KEEP_BOTH)
            var progress = OperationSnapshot(transfer.id, "fixture", OperationStatus.RUNNING)
            val operation = OperationContext(transfer.id, MutableStateFlow(false)) { update ->
                progress = update(progress)
                if (progress.completedBytes > 0 && !target.exists()) target.writeText("unrelated")
            }
            assertTrue(runCatching {
                engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, operation)
            }.isFailure)
            assertEquals("unrelated", target.readText())
            assertArrayEquals(ByteArray(512 * 1024) { 42 }, source.readBytes())
            assertEquals(listOf(source.name), destination.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun aSourceSymlinkIsRejectedBeforeCanonicalization() {
        val root = File(context.cacheDir, "transfer-link-${System.nanoTime()}").apply { mkdirs() }
        val link = File(root, "link.txt")
        try {
            val source = File(root, "source.txt").apply { writeText("preserve") }
            val destination = File(root, "destination").apply { mkdir() }
            Os.symlink(source.path, link.path)
            assertTrue(runCatching { plan(listOf(link), destination, ConflictPolicy.KEEP_BOTH) }.isFailure)
            assertEquals("preserve", source.readText())
            assertTrue(destination.list()!!.isEmpty())
        } finally {
            Files.deleteIfExists(link.toPath())
            root.deleteRecursively()
        }
    }

    @Test fun redirectingTheDestinationAfterPlanningCannotWriteOutsideIt() = runBlocking {
        val root = File(context.cacheDir, "transfer-redirect-${System.nanoTime()}").apply { mkdirs() }
        val destination = File(root, "destination").apply { mkdir() }
        try {
            val source = File(root, "source.txt").apply { writeText("preserve") }
            val outside = File(root, "outside").apply { mkdir() }
            val transfer = plan(listOf(source), destination, ConflictPolicy.KEEP_BOTH)
            assertTrue(destination.delete())
            Os.symlink(outside.path, destination.path)
            assertTrue(runCatching {
                engine.execute(transfer, DurableTransferState(planId = transfer.id), writer, OperationContext.background())
            }.isFailure)
            assertEquals("preserve", source.readText())
            assertTrue(outside.list()!!.isEmpty())
        } finally {
            Files.deleteIfExists(destination.toPath())
            root.deleteRecursively()
        }
    }
}
