package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrashPathRulesTest {
    @Test
    fun normalizesSeparatorsAndNavigatesBetweenLevels() {
        assertEquals("", TrashPathRules.normalize(""))
        assertEquals("vaizdai/kelione", TrashPathRules.normalize("/vaizdai/kelione/"))
        assertEquals("vaizdai\\kelione", TrashPathRules.normalize("vaizdai\\kelione"))
        assertEquals("vaizdai/kelione", TrashPathRules.child("vaizdai", "kelione"))
        assertEquals("vaizdai", TrashPathRules.parent("vaizdai/kelione"))
        assertEquals("", TrashPathRules.parent("vaizdai"))
    }

    @Test
    fun rejectsTraversalAndExcessiveDepth() {
        assertThrows(IllegalArgumentException::class.java) { TrashPathRules.normalize("../slaptas") }
        assertThrows(IllegalArgumentException::class.java) { TrashPathRules.normalize("./failas") }
        assertThrows(IllegalArgumentException::class.java) {
            TrashPathRules.normalize((1..65).joinToString("/") { "k$it" })
        }
    }
}
