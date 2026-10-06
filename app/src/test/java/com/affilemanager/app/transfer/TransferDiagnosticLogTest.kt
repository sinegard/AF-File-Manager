package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class TransferDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun event(id: String? = UUID.randomUUID().toString()) = TransferDiagnosticEvent(
        TransferRole.RECEIVE, TransferPhase.COMMIT, id, 1, 5_242_880, 5_242_880,
        failure = TransferFailure.CONFLICT, storage = TransferStorage.REMOVABLE,
    )

    @Test fun defaultOffDoesNotCreateFilesAndOnlyTypedOptInDataIsStored() {
        val file = File(temporary.root, "private/events.log")
        val log = TransferDiagnosticLog(file)
        log.record(event())
        assertFalse(file.exists())
        log.enabled = true
        log.record(event("secret-password /personal/video.mp4 192.168.1.7"))
        val report = log.report()
        listOf("secret", "personal", "video", "192.168", "password").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("AF-XFER-CONFLICT"))
        assertTrue(report.contains("receive\tcommit\t-\t1\t5242880"))
        assertEquals(report, TransferDiagnosticLog(file).report())
    }

    @Test fun recordsAreBoundedAndClearingDoesNotTouchUserFiles() {
        val file = File(temporary.root, "private/events.log")
        val original = temporary.newFile("original.txt").apply { writeText("unchanged") }
        val log = TransferDiagnosticLog(file).apply { enabled = true }
        repeat(300) { log.record(event()) }
        assertEquals(TransferDiagnosticLog.MAX_EVENTS, file.readLines().size)
        assertTrue(file.length() <= TransferDiagnosticLog.MAX_BYTES)
        log.enabled = false
        val before = file.readText()
        log.record(event())
        assertEquals(before, file.readText())
        log.clear()
        assertEquals(0, file.length())
        assertEquals("unchanged", original.readText())
    }

    @Test fun invalidSavedRowsAndOversizedJournalCannotBeExported() {
        val file = temporary.newFile("events.log")
        file.writeText("/private/file password=secret\n" + event().line())
        val report = TransferDiagnosticLog(file).report()
        assertFalse(report.contains("private/file"))
        assertFalse(report.contains("secret"))
        assertTrue(report.contains("AF-XFER-CONFLICT"))
        file.writeText("x".repeat(TransferDiagnosticLog.MAX_BYTES + 1))
        val log = TransferDiagnosticLog(file)
        assertTrue(log.report().contains("diagnostic_storage_failed=true"))
        assertTrue(log.storageFailed)
    }

    @Test fun failedDiagnosticStorageIsVisibleButDoesNotThrowIntoTheTransfer() {
        val blockedParent = temporary.newFile("not-a-directory")
        val log = TransferDiagnosticLog(File(blockedParent, "events.log")).apply { enabled = true }
        log.record(event())
        assertTrue(log.storageFailed)
        assertTrue(log.report().contains("diagnostic_storage_failed=true"))
        assertEquals(TransferFailure.CALLBACK, TransferFailure.classify(IOException("/private path"), TransferPhase.NOTIFY))
        assertEquals(TransferFailure.CONNECTION, TransferFailure.classify(IOException("reply lost"), TransferPhase.RESPONSE))
        assertEquals(TransferFailure.SOURCE, TransferFailure.classify(NearbySourceException(IOException("provider")), TransferPhase.SOURCE))
        assertEquals(TransferFailure.PERMISSION, TransferFailure.classify(java.nio.file.AccessDeniedException("secret"), TransferPhase.OPEN))
        assertEquals(TransferFailure.SPACE, TransferFailure.classify(java.nio.file.FileSystemException("secret", null, "No space left on device"), TransferPhase.WRITE))
    }
}
