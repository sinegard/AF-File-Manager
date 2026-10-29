package com.affilemanager.app.security

import org.junit.Assert.*
import org.junit.Test

class VaultWorkingSetRulesTest {
    private val mib = 1024L * 1024

    @Test fun twentyFivePercentIsReservedRatherThanBorrowedByTheOperation() {
        assertTrue(VaultWorkingSetRules.permitsStreaming(VaultHeapSnapshot(128 * mib, 96 * mib, 8 * mib)))
        assertFalse(VaultWorkingSetRules.permitsStreaming(VaultHeapSnapshot(128 * mib, 96 * mib, 7 * mib)))
    }

    @Test fun streamingAdmissionDoesNotDependOnTheFileSize() {
        assertTrue(VaultWorkingSetRules.permitsStreaming(VaultHeapSnapshot(128 * mib, 64 * mib, 16 * mib)))
        assertFalse(VaultWorkingSetRules.permitsLegacy(64L * 1024 * mib, 64L * 1024 * mib, VaultHeapSnapshot(128 * mib, 64 * mib, 16 * mib)))
    }

    @Test fun legacyWorkingSetIncludesCiphertextAndIntermediateArrays() {
        val heap = VaultHeapSnapshot(128 * mib, 64 * mib, 16 * mib)
        assertTrue(VaultWorkingSetRules.permitsLegacy(6 * mib + 16, 6 * mib, heap))
        assertFalse(VaultWorkingSetRules.permitsLegacy(8 * mib + 16, 8 * mib, heap))
        assertFalse(VaultWorkingSetRules.permitsLegacy(Long.MAX_VALUE, Long.MAX_VALUE, heap))
        assertFalse(VaultWorkingSetRules.permitsLegacy(15, 0, heap))
    }

    @Test fun invalidAndExhaustedSnapshotsFailClosed() {
        for (heap in listOf(VaultHeapSnapshot(0, 0, 0), VaultHeapSnapshot(10, 11, 1),
            VaultHeapSnapshot(10, 5, 6), VaultHeapSnapshot(128 * mib, 128 * mib, 0))) {
            assertFalse(VaultWorkingSetRules.permitsStreaming(heap))
            assertFalse(VaultWorkingSetRules.permitsLegacy(16, 0, heap))
        }
    }
}
