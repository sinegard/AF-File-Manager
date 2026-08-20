package com.affilemanager.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FtpMachineListParserTest {
    @Test
    fun parsesFileFactsAndUtcTimestamp() {
        val entry = requireNotNull(
            FtpMachineListParser.parse(
                "type=file;size=32;modify=20260816120000; report final.txt",
                "/docs",
            ),
        )

        assertEquals("report final.txt", entry.name)
        assertEquals("/docs/report final.txt", entry.path)
        assertEquals(32L, entry.sizeBytes)
        assertFalse(entry.directory)
        assertEquals(Instant.parse("2026-08-16T12:00:00Z").toEpochMilli(), entry.modifiedAtMillis)
    }

    @Test
    fun parsesDirectoryAndIgnoresProtocolPseudoDirectories() {
        val directory = requireNotNull(FtpMachineListParser.parse("type=dir;modify=20260816120000; photos", "/"))
        assertTrue(directory.directory)
        assertEquals(0L, directory.sizeBytes)
        assertNull(FtpMachineListParser.parse("type=cdir;modify=20260816120000; .", "/"))
        assertNull(FtpMachineListParser.parse("type=pdir;modify=20260816120000; ..", "/"))
    }

    @Test
    fun rejectsAPathSeparatorInTheServerSuppliedName() {
        val failure = runCatching {
            FtpMachineListParser.parse("type=file;size=1; nested/file.txt", "/")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun keepsEntryWhenOptionalFactsAreMalformed() {
        val entry = requireNotNull(FtpMachineListParser.parse("type=file;size=nope;modify=invalid; file.txt", "/"))
        assertEquals(0L, entry.sizeBytes)
        assertNull(entry.modifiedAtMillis)
    }
}
