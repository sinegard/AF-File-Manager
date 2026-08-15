package com.affilemanager.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellCommandRulesTest {
    @Test
    fun quotesSpacesAndSingleQuotesWithoutCommandInjection() {
        val command = ShellCommandRules.changeDirectory("/data/client's files").toString(Charsets.UTF_8)

        assertEquals("cd '/data/client'\"'\"'s files'\n", command)
    }

    @Test
    fun rejectsLineBreaksAndOversizedPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRules.changeDirectory("/safe\nrm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRules.changeDirectory("/" + "a".repeat(4_096))
        }
    }
}
