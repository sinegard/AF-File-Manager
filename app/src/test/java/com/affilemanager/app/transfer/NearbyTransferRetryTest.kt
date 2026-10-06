package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class NearbyTransferRetryTest {
    @Test fun retriesOnlyWhenReceiverProvesThatTheFileWasNotCommitted() {
        assertEquals(
            NearbyUploadRecovery.RETRY,
            NearbyTransferRetry.decide(IOException("broken pipe"), TransferFileStatus.FAILED, 1),
        )
        assertEquals(
            NearbyUploadRecovery.RETRY,
            NearbyTransferRetry.decide(IOException("reset"), TransferFileStatus.WAITING, 2),
        )
        assertEquals(
            NearbyUploadRecovery.FAIL,
            NearbyTransferRetry.decide(IOException("reset"), TransferFileStatus.FAILED, 3),
        )
    }

    @Test fun lostAcknowledgementDoesNotCreateADuplicate() {
        assertEquals(
            NearbyUploadRecovery.COMPLETE,
            NearbyTransferRetry.decide(IOException("response lost"), TransferFileStatus.COMPLETED, 1),
        )
        assertEquals(
            NearbyUploadRecovery.WAIT,
            NearbyTransferRetry.decide(IOException("unknown"), null, 1),
        )
        assertEquals(
            NearbyUploadRecovery.WAIT,
            NearbyTransferRetry.decide(IOException("still receiving"), TransferFileStatus.TRANSFERRING, 1),
        )
    }

    @Test fun cancellationAndLocalSourceErrorsAreNotRetried() {
        assertEquals(
            NearbyUploadRecovery.CANCEL,
            NearbyTransferRetry.decide(IOException("closed"), TransferFileStatus.CANCELLED, 1),
        )
        assertEquals(
            NearbyUploadRecovery.FAIL,
            NearbyTransferRetry.decide(FileNotFoundException("gone"), TransferFileStatus.FAILED, 1),
        )
        assertEquals(
            NearbyUploadRecovery.FAIL,
            NearbyTransferRetry.decide(IllegalStateException("rejected"), TransferFileStatus.FAILED, 1),
        )
    }

    @Test fun httpFailuresUseAuthoritativeStateWithoutReplayingCommittedFiles() {
        val transient = NearbyUploadException(500, null, "Server error")
        assertEquals(NearbyUploadRecovery.RETRY, NearbyTransferRetry.decide(transient, TransferFileStatus.FAILED, 1))
        assertEquals(NearbyUploadRecovery.WAIT, NearbyTransferRetry.decide(transient, TransferFileStatus.TRANSFERRING, 1))
        assertEquals(NearbyUploadRecovery.WAIT, NearbyTransferRetry.decide(transient, null, 1))
        assertEquals(NearbyUploadRecovery.COMPLETE, NearbyTransferRetry.decide(transient, TransferFileStatus.COMPLETED, 1))
        assertEquals(NearbyUploadRecovery.FAIL, NearbyTransferRetry.decide(transient, TransferFileStatus.FAILED, 3))
        for (code in listOf(400, 401, 403, 404, 410, 413)) {
            assertEquals("$code", NearbyUploadRecovery.FAIL,
                NearbyTransferRetry.decide(NearbyUploadException(code, null, "rejected"), TransferFileStatus.FAILED, 1))
        }
    }

    @Test fun knownStorageAndSourceFailuresAreNeverBlindlyRetried() {
        for (failure in TransferFailure.entries.filterNot { it.retryable }) {
            val error = NearbyUploadException(500, failure, "Server error")
            assertEquals(failure.code, NearbyUploadRecovery.FAIL, NearbyTransferRetry.decide(error, TransferFileStatus.FAILED, 1))
            assertEquals(failure.code, NearbyUploadRecovery.COMPLETE, NearbyTransferRetry.decide(error, TransferFileStatus.COMPLETED, 1))
        }
        assertEquals(NearbyUploadRecovery.FAIL, NearbyTransferRetry.decide(
            NearbySourceException(IOException("provider closed")), TransferFileStatus.FAILED, 1))
    }

    @Test fun aBusyReceiverGetsItsReadDeadlineButCannotExtendTheHardBudget() {
        val window = NearbyRecoveryWindow(1_000)
        for (offset in 0L..130_000L step 2_000L) {
            window.observe(1_000 + offset, available = true)
            org.junit.Assert.assertFalse(window.expired(1_000 + offset))
        }
        window.observe(136_000, available = true)
        org.junit.Assert.assertTrue(window.expired(136_000))
        assertEquals(0L, window.remainingMillis(136_000))
    }

    @Test fun unavailableStatusHasAShorterFiniteBudgetAndNeverAuthorisesReplay() {
        val window = NearbyRecoveryWindow(5_000)
        assertEquals(15_000L, window.remainingMillis(5_000))
        window.observe(19_999, available = false)
        org.junit.Assert.assertFalse(window.expired(19_999))
        assertEquals(1L, window.remainingMillis(19_999))
        org.junit.Assert.assertTrue(window.expired(20_000))
        val recovered = NearbyRecoveryWindow(5_000)
        recovered.observe(19_000, available = true)
        org.junit.Assert.assertFalse(recovered.expired(33_999))
        org.junit.Assert.assertTrue(recovered.expired(34_000))
        assertEquals(NearbyUploadRecovery.WAIT, NearbyTransferRetry.decide(IOException(), null, 1))
        assertEquals(2_000L, NearbyTransferRetry.statusDelayMillis(Int.MAX_VALUE))
    }
}
