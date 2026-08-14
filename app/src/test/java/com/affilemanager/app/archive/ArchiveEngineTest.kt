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
}
