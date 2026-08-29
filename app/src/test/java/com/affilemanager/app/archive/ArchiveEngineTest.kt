package com.affilemanager.app.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun zipRoundTripPreservesNestedContent() = runBlocking {
        val source = temporary.newFolder("source")
        File(source, "nested").mkdirs()
        File(source, "nested/duomenys.txt").writeText("AF File Manager – archyvo testas")
        val archive = File(temporary.root, "backup.zip")
        val engine = ArchiveEngine()

        engine.create(ArchiveFormat.ZIP, archive, listOf(source))
        val entries = engine.list(archive)
        assertTrue(entries.any { it.name.endsWith("duomenys.txt") })

        val output = temporary.newFolder("output")
        engine.extract(archive, output)
        val restored = output.walkTopDown().first { it.name == "duomenys.txt" }
        assertEquals("AF File Manager – archyvo testas", restored.readText())
    }

    @Test
    fun encryptedZipRejectsWrongPasswordAndAcceptsCorrectPassword() = runBlocking {
        val source = temporary.newFile("slaptas.txt").apply { writeText("tikras turinys") }
        val archive = File(temporary.root, "slaptas.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(source), "teisingas-123".toCharArray())

        val wrongOutput = temporary.newFolder("wrong")
        val failure = runCatching { engine.extract(archive, wrongOutput, "neteisingas".toCharArray()) }
        assertTrue(failure.isFailure)

        val output = temporary.newFolder("correct")
        engine.extract(archive, output, "teisingas-123".toCharArray())
        assertEquals("tikras turinys", File(output, "slaptas.txt").readText())
    }

    @Test
    fun selectedNestedEntryIsMaterializedWithoutExtractingItsSiblings() = runBlocking {
        val source = temporary.newFolder("selective-source")
        File(source, "nested").mkdirs()
        File(source, "nested/selected.txt").writeText("selected")
        File(source, "nested/ignored.txt").writeText("ignored")
        val archive = File(temporary.root, "selective.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(source))
        val selectedEntry = engine.list(archive).single { it.name.endsWith("nested/selected.txt") }
        val output = File(temporary.newFolder("selective-output"), "result.txt")

        engine.extractEntry(archive, selectedEntry.name, output)

        assertEquals("selected", output.readText())
        assertEquals(listOf("result.txt"), requireNotNull(output.parentFile).list()?.toList())
    }

    @Test
    fun createNeverOverwritesAnExistingArchive() = runBlocking {
        val source = temporary.newFile("new.txt").apply { writeText("new content") }
        val archive = temporary.newFile("existing.zip").apply { writeText("original content") }

        val result = runCatching {
            ArchiveEngine().create(ArchiveFormat.ZIP, archive, listOf(source))
        }

        assertTrue(result.isFailure)
        assertEquals("original content", archive.readText())
    }

    @Test
    fun emptyArchivesAreValidForEveryWritableFormat() = runBlocking {
        val engine = ArchiveEngine()
        val formats = listOf(
            ArchiveFormat.ZIP to "empty.zip",
            ArchiveFormat.SEVEN_Z to "empty.7z",
            ArchiveFormat.TAR to "empty.tar",
            ArchiveFormat.TAR_GZ to "empty.tar.gz",
        )

        formats.forEach { (format, name) ->
            val archive = File(temporary.root, name)
            engine.create(format, archive, emptyList())
            assertTrue(archive.isFile && archive.length() > 0L)
            assertTrue(engine.list(archive).isEmpty())
        }
    }
}
