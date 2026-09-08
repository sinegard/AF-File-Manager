package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class LanTransferHistoryTest {
    @Test fun stoppingRemovesSessionSecretsButPreservesCompletedFilesAndCancelsUnfinishedOnes() {
        val complete = TransferFileProgress("ready.txt", 20, 20, TransferFileStatus.COMPLETED, "/private/received/ready.txt")
        val progress = LanUploadProgress("pending.txt", 2, 3, 5, 10, 25, 40, files = listOf(complete,
            TransferFileProgress("pending.txt", 10, 5, TransferFileStatus.TRANSFERRING),
            TransferFileProgress("waiting.txt", 10)))
        try {
            LanTransferController.publish(LanTransferState(status = LanTransferStatus.RUNNING,
                url = "http://192.168.1.2:8080", code = "fixture-only", username = "fixture", expiresAtMillis = 12345,
                incomingUpload = progress))
            LanTransferController.publishStopped("Session ended")
            val state = LanTransferController.state.value
            assertEquals(LanTransferStatus.STOPPED, state.status)
            assertEquals("Session ended", state.message)
            assertNull(state.url); assertNull(state.code); assertNull(state.username); assertNull(state.expiresAtMillis)
            assertEquals(complete, state.incomingUpload!!.files.first())
            assertEquals(listOf(TransferFileStatus.COMPLETED, TransferFileStatus.CANCELLED, TransferFileStatus.CANCELLED),
                state.incomingUpload!!.files.map { it.status })
            assertTrue(state.incomingUpload!!.files.drop(1).all { it.localPath == null })
            LanTransferController.publishUpload(progress)
            assertEquals("A late socket callback cannot reactivate a closed session", state, LanTransferController.state.value)
            LanTransferController.publishStopped("Service ended")
            assertEquals(state.incomingUpload, LanTransferController.state.value.incomingUpload)
        } finally { LanTransferController.publish(LanTransferState()) }
    }
}
