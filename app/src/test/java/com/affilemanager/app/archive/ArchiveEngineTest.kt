package com.affilemanager.app.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun extractionFolderNameRemovesCompoundArchiveSuffix() {
        assertEquals("backup", ArchiveMutationRules.extractionBaseName("backup.tar.gz", "extracted"))
        assertEquals("backup", ArchiveMutationRules.extractionBaseName("backup.TGZ", "extracted"))
        assertEquals("backup", ArchiveMutationRules.extractionBaseName("backup.zip", "extracted"))
    }

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
    fun selectedDirectoryIsExtractedInOnePassWithoutItsSiblingTree() = runBlocking {
        val source = temporary.newFolder("selected-tree-source")
        File(source, "wanted/nested").mkdirs()
        File(source, "wanted/nested/data.txt").writeText("wanted")
        File(source, "other").mkdirs()
        File(source, "other/ignored.txt").writeText("ignored")
        val archive = File(temporary.root, "selected-tree.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, source.listFiles().orEmpty().toList())
        val output = temporary.newFolder("selected-tree-output")

        val extracted = engine.extractEntries(archive, setOf("wanted"), output)

        assertTrue(extracted >= 2)
        assertEquals("wanted", File(output, "wanted/nested/data.txt").readText())
        assertFalse(File(output, "other/ignored.txt").exists())
    }

    @Test
    fun selectedEmptyDirectoryIsPreserved() = runBlocking {
        val empty = temporary.newFolder("empty-selected")
        val archive = File(temporary.root, "empty-selected.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(empty))
        val directoryEntry = engine.list(archive).first { it.directory }
        val output = temporary.newFolder("empty-selected-output")

        assertEquals(1, engine.extractEntries(archive, setOf(directoryEntry.name), output))
        assertTrue(File(output, directoryEntry.name.trimEnd('/')).isDirectory)
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
    fun createRejectsAnOversizedSourceTreeBeforeWritingOutput() = runBlocking {
        val source = temporary.newFolder("bounded-source")
        File(source, "one.txt").writeText("one")
        File(source, "two.txt").writeText("two")
        val archive = File(temporary.root, "bounded.zip")
        val engine = ArchiveEngine(ArchiveLimits(maxEntries = 2))

        val result = runCatching { engine.create(ArchiveFormat.ZIP, archive, listOf(source)) }

        assertTrue(result.isFailure)
        assertFalse(archive.exists())
        assertFalse(File(temporary.root, ".bounded.zip.partial").exists())
    }

    @Test
    fun createRejectsOutputInsideTheSourceAndClearsThePassword() = runBlocking {
        val source = temporary.newFolder("self-containing-source")
        File(source, "data.txt").writeText("data")
        val archive = File(source, "inside.zip")
        val password = "temporary-secret".toCharArray()

        val result = runCatching { ArchiveEngine().create(ArchiveFormat.ZIP, archive, listOf(source), password) }

        assertTrue(result.isFailure)
        assertFalse(archive.exists())
        assertTrue(password.all { it == '\u0000' })
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

    @Test
    fun selectedDirectoryDeletionRewritesZipAndKeepsUnrelatedContent() = runBlocking {
        val source = temporary.newFolder("delete-source")
        File(source, "remove/nested").mkdirs()
        File(source, "remove/nested/gone.txt").writeText("gone")
        File(source, "keep.txt").writeText("keep")
        val archive = File(temporary.root, "delete.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, source.listFiles().orEmpty().toList())

        val after = engine.deleteZipEntries(archive, listOf("remove"))

        assertFalse(after.any { it.name.replace('\\', '/').startsWith("remove/") })
        assertTrue(after.any { it.name == "keep.txt" })
        val output = temporary.newFolder("delete-output")
        engine.extract(archive, output)
        assertEquals("keep", File(output, "keep.txt").readText())
    }

    @Test
    fun syntheticDirectoryRenameMovesEveryDescendant() = runBlocking {
        val source = temporary.newFolder("rename-source")
        File(source, "old/sub").mkdirs()
        File(source, "old/sub/data.txt").writeText("data")
        val archive = File(temporary.root, "rename.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, File(source, "old").listFiles().orEmpty().toList())

        val listed = engine.list(archive)
        val actualRoot = listed.single { it.name.endsWith("data.txt") }.name.substringBefore('/')
        val after = engine.renameZipEntry(archive, actualRoot, "renamed")

        assertTrue(after.any { it.name.replace('\\', '/').startsWith("renamed/") })
        assertFalse(after.any { it.name.replace('\\', '/').startsWith("$actualRoot/") })
    }

    @Test
    fun renameCollisionLeavesOriginalArchiveUnchanged() = runBlocking {
        val first = temporary.newFile("first.txt").apply { writeText("first") }
        val second = temporary.newFile("second.txt").apply { writeText("second") }
        val archive = File(temporary.root, "collision.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, archive, listOf(first, second))
        val before = archive.readBytes()

        val result = runCatching { engine.renameZipEntry(archive, "first.txt", "second.txt") }

        assertTrue(result.isFailure)
        assertTrue(before.contentEquals(archive.readBytes()))
    }
}
