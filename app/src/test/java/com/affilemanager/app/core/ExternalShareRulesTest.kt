package com.affilemanager.app.core

import org.junit.Assert.*
import org.junit.Test

class ExternalShareRulesTest {
    @Test fun allThousandSurviveDuplicatesBetweenStreamsAndClipData() {
        val files = List(1_000) { "content://af/shared/$it.txt" }
        assertEquals(files, ExternalShareRules.collect((files + files).asSequence(), { it }) { true })
    }

    @Test fun neverReturnAnApparentlySuccessfulPartialSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            ExternalShareRules.collect((0..1_000).asSequence(), { "content://af/$it" }) { true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalShareRules.collect(List(100) { "content://af/${"x".repeat(4_096)}/$it" }.asSequence(), { it }) { true }
        }
    }

    @Test fun invalidSchemesDoNotAcquireReadAccessAndLimitsAreIndependent() {
        assertEquals(listOf("content://af/ok"), ExternalShareRules.collect(
            sequenceOf("https://remote", "content://af/ok"), { it }, { it.startsWith("content://") }))
        assertEquals(1_000, ExternalShareRules.MAX_FILES)
    }
}
