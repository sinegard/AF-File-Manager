package com.affilemanager.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellCommandRulesTest {
    @Test
    fun quotesSpacesAndSingleQuotesWithoutCommandInjection() {
        val command = ShellCommandRules.changeDirectory("/data/client's files").toString(Charsets.UTF_8)

        assertEquals("cd '/data/client'\"'\"'s files'\r", command)
    }

    @Test
    fun recognizesWindowsOpenSshPathsAndUsesCmdDriveSwitching() {
        assertEquals(
            RemoteShellPathStyle.WINDOWS_OPENSSH,
            ShellCommandRules.inferPathStyle("/", listOf("C:")),
        )
        assertEquals(
            RemoteShellPathStyle.WINDOWS_OPENSSH,
            ShellCommandRules.inferPathStyle("/D:/Shared files", emptyList()),
        )
        assertEquals(
            "cd /d \"D:\\Shared files\"\r",
            ShellCommandRules.changeDirectory(
                "/D:/Shared files",
                RemoteShellPathStyle.WINDOWS_OPENSSH,
            ).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun leavesTheSyntheticWindowsDriveRootAtTheServersDefaultDirectory() {
        assertEquals(
            "",
            ShellCommandRules.changeDirectory(
                "/",
                RemoteShellPathStyle.WINDOWS_OPENSSH,
            ).toString(Charsets.UTF_8),
        )
        assertEquals(
            RemoteShellPathStyle.POSIX,
            ShellCommandRules.inferPathStyle("/", listOf("home", "var")),
        )
    }

    @Test
    fun rejectsLineBreaksAndOversizedPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRules.changeDirectory("/safe\nrm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRules.changeDirectory("/" + "a".repeat(4_096))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRules.changeDirectory(
                "/C:/unsafe\" & whoami",
                RemoteShellPathStyle.WINDOWS_OPENSSH,
            )
        }
    }
}
