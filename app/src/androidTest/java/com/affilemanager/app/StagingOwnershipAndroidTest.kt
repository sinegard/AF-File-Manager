package com.affilemanager.app

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

class StagingOwnershipAndroidTest {
    @Test fun archiveCreationDoesNotFollowAnUnrelatedBrokenStagingLink() = fixture { root ->
        val source = root.resolve("source.txt").apply { writeText("archive input") }
        val archive = root.resolve("output.zip")
        val outside = root.resolve("not-selected.txt")
        val unowned = root.resolve(".output.zip.partial")
        Files.createSymbolicLink(unowned.toPath(), outside.toPath())
        val result = runCatching { ArchiveEngine().create(ArchiveFormat.ZIP, archive, listOf(source)) }
        assertFalse("Archive staging created an unselected link target", outside.exists())
        assertTrue("An unowned staging link was moved or deleted", Files.isSymbolicLink(unowned.toPath()))
        result.getOrThrow()
        assertFalse(Files.isSymbolicLink(archive.toPath()))
        assertEquals("archive input", source.readText())
    }

    @Test fun extractionDoesNotOverwriteTheTargetOfAnUnrelatedStagingLink() = fixture { root ->
        val source = root.resolve("source.txt").apply { writeText("archive input") }
        val archive = root.resolve("output.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(source))
        val destination = root.resolve("destination").apply { check(mkdir()) }
        val outside = root.resolve("not-selected.txt").apply { writeText("not selected") }
        val unowned = destination.resolve(".source.txt.af-partial")
        Files.createSymbolicLink(unowned.toPath(), outside.toPath())
        engine.extract(archive, destination)
        assertEquals("Extraction overwrote an unselected link target", "not selected", outside.readText())
        assertTrue(Files.isSymbolicLink(unowned.toPath()))
        assertFalse(Files.isSymbolicLink(destination.resolve(source.name).toPath()))
        assertEquals("archive input", destination.resolve(source.name).readText())
    }

    @Test fun legacyLocalTextWriteDoesNotBorrowAnUnrelatedHiddenFile() = fixture { root ->
        val source = root.resolve("source.txt").apply { writeText("original") }
        val unowned = root.resolve(".source.txt.af.tmp").apply { writeText("unrelated hidden file") }
        val context = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        LocalFileRepository(context).writeText(source.path, "edited").getOrThrow()
        assertTrue("The legacy writer removed an unowned file", unowned.isFile)
        assertEquals("unrelated hidden file", unowned.readText())
        assertEquals("edited", source.readText())
    }

    @Test fun durableCopyAndMoveRejectBrokenPartialLinksWithoutCreatingTheirTargets() = fixture { root ->
        for (moving in listOf(false, true)) {
            val source = root.resolve("source-$moving.txt").apply { writeText("original remains") }
            val destination = root.resolve("destination-$moving").apply { check(mkdir()) }
            val plan = DurableTransferPlanner().create(listOf(source.path), destination.path, moving,
                ConflictPolicy.KEEP_BOTH, TransferVerification.SHA256, TransferFailurePolicy.STOP)
            val item = plan.items.single()
            val unowned = destination.resolve(".af-part-${plan.id}-${item.index}.tmp")
            val outside = root.resolve("not-selected-$moving.txt")
            Files.createSymbolicLink(unowned.toPath(), outside.toPath())
            val result = runCatching { DurableTransferEngine().execute(plan, DurableTransferState(planId = plan.id),
                object : DurableTransferStateWriter { override fun saveState(state: DurableTransferState) = Unit }, OperationContext.background()) }
            assertFalse("Durable transfer followed a broken staging link (move=$moving)", outside.exists())
            assertTrue(Files.isSymbolicLink(unowned.toPath()))
            assertEquals("original remains", source.readText())
            assertTrue(result.isFailure || result.getOrThrow().status != DurableTransferStatus.COMPLETED)
        }
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(Build.MODEL.contains("sdk", true) && context.packageName.endsWith(".debug"))
        val root = File(context.cacheDir, "staging-audit-${UUID.randomUUID()}").apply { check(mkdir()) }.canonicalFile
        try { block(root) } finally { removeOwnedFixture(root) }
    }
    private fun removeOwnedFixture(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeOwnedFixture)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
