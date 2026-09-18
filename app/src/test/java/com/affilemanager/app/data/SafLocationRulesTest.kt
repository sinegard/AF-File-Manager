package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafLocationRulesTest {
    @Test
    fun customTitleIsTrimmedAndBounded() {
        assertEquals("Work Drive", SafLocationRules.normalizeTitle("  Work Drive  "))
        assertThrows(IllegalArgumentException::class.java) { SafLocationRules.normalizeTitle("   ") }
        assertThrows(IllegalArgumentException::class.java) { SafLocationRules.normalizeTitle("a".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { SafLocationRules.normalizeTitle("Drive\nother") }
    }
}
