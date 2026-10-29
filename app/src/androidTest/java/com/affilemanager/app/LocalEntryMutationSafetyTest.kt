package com.affilemanager.app

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.operations.BatchRenameEngine
import com.affilemanager.app.operations.BatchRenameSpec
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

class LocalEntryMutationSafetyTest {
    @Test fun renameDoesNotResolveTheSelectedLinkIntoAnUnselectedFile() = fixture { app, root ->
        val target = root.resolve("not-selected.txt").apply { writeText("keep") }
        val link = root.resolve("selected-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val result = LocalFileRepository(app).rename(link.path, "renamed.txt")
        assertTrue("Rename changed a link's unselected target", target.isFile)
        assertEquals("keep", target.readText())
        assertTrue(result.isFailure)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun batchRenameCannotPreviewTheReferencedFileAsIfItWasSelected() = fixture { _, root ->
        val target = root.resolve("not-selected.txt").apply { writeText("keep") }
        val link = root.resolve("selected-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val result = runCatching { BatchRenameEngine().preview(listOf(link.path), BatchRenameSpec(prefix = "renamed-")) }
        assertTrue("Batch rename planned an unselected referenced file", result.isFailure || !result.getOrThrow().canExecute)
        assertEquals("keep", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun keepBothTreatsABrokenLinkAsAnOccupiedOriginalName() = fixture { _, root ->
        val occupied = root.resolve("report.txt")
        Files.createSymbolicLink(occupied.toPath(), root.resolve("absent").toPath())
        val chosen = FileSystemRules.keepBothTarget(occupied)
        assertEquals("report (1).txt", chosen.name)
        assertTrue(Files.isSymbolicLink(occupied.toPath()))
    }

    @Test fun keepBothSkipsABrokenLinkAtANumberedCandidate() = fixture { _, root ->
        val occupied = root.resolve("report.txt").apply { writeText("existing") }
        val candidate = root.resolve("report (1).txt")
        Files.createSymbolicLink(candidate.toPath(), root.resolve("absent").toPath())
        assertEquals("report (2).txt", FileSystemRules.keepBothTarget(occupied).name)
        assertTrue(Files.isSymbolicLink(candidate.toPath()))
    }

    @Test fun renameCannotSilentlyReplaceAnExistingBrokenLink() = fixture { app, root ->
        val source = root.resolve("source.txt").apply { writeText("original") }
        val occupied = root.resolve("occupied.txt")
        Files.createSymbolicLink(occupied.toPath(), root.resolve("absent").toPath())
        val result = LocalFileRepository(app).rename(source.path, occupied.name)
        assertTrue(result.isFailure)
        assertEquals("original", source.readText())
        assertTrue(Files.isSymbolicLink(occupied.toPath()))
    }

    @Test fun batchRenameCannotReplaceABrokenLinkCreatedAfterItsPreview() = fixture { _, root ->
        val source = root.resolve("source.txt").apply { writeText("original") }
        val engine = BatchRenameEngine()
        val preview = engine.preview(listOf(source.path), BatchRenameSpec(prefix = "new-"))
        assertTrue(preview.canExecute)
        val occupied = root.resolve("new-source.txt")
        Files.createSymbolicLink(occupied.toPath(), root.resolve("absent").toPath())
        val failure = runCatching { engine.execute(preview, OperationContext.background()) }.exceptionOrNull()
        assertNotNull("A late conflict was overwritten", failure)
        assertEquals("original", source.readText())
        assertTrue(Files.isSymbolicLink(occupied.toPath()))
    }

    private fun fixture(block: suspend (AFFileManagerApplication, File) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "entry-safety-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { block(app, root) } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    private fun removeFixtureWithoutFollowingLinks(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeFixtureWithoutFollowingLinks)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
