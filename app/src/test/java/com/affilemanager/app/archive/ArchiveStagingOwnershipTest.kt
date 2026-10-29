package com.affilemanager.app.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveStagingOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractingDoesNotOverwriteOrRemoveAnUnrelatedHiddenFile() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("archive payload") }
        val archive = File(temporary.root, "source.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(source))
        val destination = temporary.newFolder("destination")
        val unrelated = File(destination, ".document.txt.af-partial").apply { writeText("an unrelated user's hidden file") }
        engine.extract(archive, destination)
        assertTrue("Extraction removed an unowned hidden file", unrelated.isFile)
        assertEquals("an unrelated user's hidden file", unrelated.readText())
        assertEquals("archive payload", File(destination, source.name).readText())
    }

    @Test fun creatingAnArchiveDoesNotBorrowAnExistingHiddenPartialName() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("archive payload") }
        val archive = File(temporary.root, "source.zip")
        val unrelated = temporary.newFile(".source.zip.partial").apply { writeText("unrelated") }
        val result = runCatching { ArchiveEngine().create(ArchiveFormat.ZIP, archive, listOf(source)) }
        assertTrue("A rejected archive deleted an unowned hidden file", unrelated.isFile)
        assertEquals("unrelated", unrelated.readText())
        result.getOrThrow()
        assertTrue(archive.isFile)
        assertEquals(listOf(source.name), ArchiveEngine().list(archive).map { it.name })
    }
}
