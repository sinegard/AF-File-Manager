package com.affilemanager.app.data

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import org.junit.Assert.*
import org.junit.Test

class TransferPreferencesTest {
    @Test fun existingDefaultsStillAskAndKeepBothWithStrongerMoveVerification() {
        val preferences = TransferPreferences()
        assertEquals(TransferVerification.SIZE, preferences.optionsFor(false).verification)
        assertEquals(TransferVerification.SHA256, preferences.optionsFor(true).verification)
        for (moving in listOf(false, true)) {
            assertTrue(preferences.optionsFor(moving).askBeforeTransfer)
            assertEquals(ConflictPolicy.KEEP_BOTH, preferences.optionsFor(moving).conflictPolicy)
            assertEquals(TransferFailurePolicy.STOP, preferences.optionsFor(moving).failurePolicy)
        }
    }

    @Test fun copyAndMoveChoicesAreIndependent() {
        val original = TransferPreferences()
        val copy = TransferOptions(ConflictPolicy.SKIP, TransferVerification.SHA256, TransferFailurePolicy.SKIP_AND_CONTINUE, false)
        val changed = original.withOptions(false, copy)
        assertEquals(copy, changed.optionsFor(false))
        assertEquals(original.optionsFor(true), changed.optionsFor(true))
        val move = TransferOptions(verification = TransferVerification.SIZE, askBeforeTransfer = false)
        val both = changed.withOptions(true, move)
        assertEquals(copy, both.optionsFor(false))
        assertEquals(move, both.optionsFor(true))
        assertTrue(original.optionsFor(false).askBeforeTransfer)
    }

    @Test fun noMoveCanInheritSkipAndContinueFromCopyOrPersistedData() {
        val input = TransferOptions(failurePolicy = TransferFailurePolicy.SKIP_AND_CONTINUE, askBeforeTransfer = false)
        assertEquals(TransferFailurePolicy.STOP, input.normalized(true).failurePolicy)
        assertEquals(TransferFailurePolicy.SKIP_AND_CONTINUE, input.normalized(false).failurePolicy)
    }

    @Test fun destructiveOrAskPoliciesCannotDisableConfirmation() {
        for (policy in listOf(ConflictPolicy.REPLACE, ConflictPolicy.MERGE, ConflictPolicy.ASK)) {
            for (moving in listOf(false, true)) {
                val value = TransferOptions(policy, askBeforeTransfer = false).normalized(moving)
                assertTrue("$policy/$moving", value.askBeforeTransfer)
            }
        }
    }

    @Test fun safePoliciesSkipOnlyAfterAnExplicitChoice() {
        for (policy in listOf(ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP)) {
            assertTrue(TransferOptions(policy).normalized(false).askBeforeTransfer)
            assertFalse(TransferOptions(policy, askBeforeTransfer = false).normalized(true).askBeforeTransfer)
        }
    }

    @Test fun normalizationIsStableAndDoesNotChangeChosenVerification() {
        for (moving in listOf(false, true)) for (policy in ConflictPolicy.entries) {
            for (verification in TransferVerification.entries) {
                val normalized = TransferOptions(policy, verification, TransferFailurePolicy.SKIP_AND_CONTINUE, false).normalized(moving)
                assertEquals(normalized, normalized.normalized(moving))
                assertEquals(verification, normalized.verification)
            }
        }
    }
}
