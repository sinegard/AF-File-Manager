package com.affilemanager.app.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePasswordTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun encryptedZipChallengesBeforeBrowsingAndRejectsWrongPassword() = runBlocking {
        val source = temporary.newFile("text.txt").apply { writeText("private text") }
        val zip = temporary.root.resolve("protected.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, zip, listOf(source), "fixture-password".toCharArray())
        assertTrue(runCatching { engine.listForBrowsing(zip) }.exceptionOrNull() is ArchivePasswordRequiredException)
        assertTrue(runCatching { engine.listForBrowsing(zip, "wrong".toCharArray()) }.isFailure)
        assertEquals("text.txt", engine.listForBrowsing(zip, "fixture-password".toCharArray()).single().name)
        val preview = temporary.root.resolve("preview.txt")
        engine.extractEntry(zip, "text.txt", preview, password = "fixture-password".toCharArray())
        assertEquals("private text", preview.readText())
        assertEquals("private text", source.readText())
    }

    @Test fun unprotectedArchiveDoesNotNeedPassword() = runBlocking {
        val source = temporary.newFile("plain.txt")
        val zip = temporary.root.resolve("plain.zip")
        val engine = ArchiveEngine()
        engine.create(ArchiveFormat.ZIP, zip, listOf(source))
        assertEquals("plain.txt", engine.listForBrowsing(zip).single().name)
    }

    @Test fun archiveThatCannotMeetTheSizeLimitFailsWithoutLeavingOutputOrChangingTheSource() = runBlocking {
        val bytes = ByteArray(2 * 1024 * 1024).also { java.util.Random(123).nextBytes(it) }
        val source = temporary.newFile("original.bin").apply { writeBytes(bytes) }
        val target = temporary.root.resolve("too-small.zip")
        val failure = runCatching { ArchiveEngine().create(ArchiveFormat.ZIP, target, listOf(source), maxOutputBytes = 1024 * 1024) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Archyvo nepavyko suspausti iki pasirinkto dydžio", failure?.message)
        assertFalse(target.exists())
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun encryptedSevenZWithAndWithoutEncryptedNamesChallengesAndExtracts() = runBlocking {
        for (fixture in listOf("protected-headers.7z", "protected-content.7z")) {
            val archive = temporary.root.resolve(fixture)
            requireNotNull(javaClass.getResourceAsStream("/archives/$fixture")).use { input -> archive.outputStream().use(input::copyTo) }
            val engine = ArchiveEngine()
            assertTrue(fixture, runCatching { engine.listForBrowsing(archive) }.exceptionOrNull() is ArchivePasswordRequiredException)
            assertTrue(fixture, runCatching { engine.listForBrowsing(archive, "wrong".toCharArray()) }.isFailure)
            assertEquals("fixture.txt", engine.listForBrowsing(archive, "fixture-password".toCharArray()).single().name)
            val target = temporary.root.resolve("$fixture.txt")
            engine.extractEntry(archive, "fixture.txt", target, password = "fixture-password".toCharArray())
            assertEquals("AF encrypted archive regression fixture.\r\n".replace("\r\n", "\n"), target.readText().replace("\r\n", "\n"))
        }
    }

    @Test fun passwordCopiesAreIndependentAndClosingOrChangedRevisionInvalidatesSession() {
        val file = temporary.newFile("source.zip").apply { writeText("revision") }
        val session = ArchivePasswordSession()
        val supplied = "password".toCharArray()
        session.remember(file, supplied)
        supplied.fill('\u0000')
        val borrowed = session.borrow(file)!!
        assertArrayEquals("password".toCharArray(), borrowed)
        borrowed.fill('\u0000')
        assertArrayEquals("password".toCharArray(), session.borrow(file))
        file.appendText("changed")
        assertNull(session.borrow(file))
        session.remember(file, "again".toCharArray())
        session.clear()
        assertNull(session.borrow(file))
    }

    @Test fun anOlderPreviewRequestCannotClearTheNewPreviewPassword() {
        val older = temporary.newFile("older.zip")
        val current = temporary.newFile("current.zip")
        val session = ArchivePasswordSession()
        session.remember(current, "current-password".toCharArray())
        assertNull(session.borrow(older))
        val borrowed = requireNotNull(session.borrow(current))
        try { assertArrayEquals("current-password".toCharArray(), borrowed) }
        finally { borrowed.fill('\u0000'); session.clear() }
    }
}
