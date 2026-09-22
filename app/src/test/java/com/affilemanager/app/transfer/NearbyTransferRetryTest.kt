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
}
