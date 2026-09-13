package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NearbyTransferHistoryRulesTest {
    @Test
    fun `progress-only updates are not retained but terminal files are`() {
        val session = session()
        val merged = NearbyTransferHistoryRules.mergeFiles(
            session,
            listOf(
                TransferFileProgress("waiting.txt", 10, 2, TransferFileStatus.TRANSFERRING),
                TransferFileProgress("done.txt", 20, 20, TransferFileStatus.COMPLETED, batchId = "batch"),
            ),
            outgoing = true,
            nowMillis = 2,
        )

        assertEquals(listOf("done.txt"), merged.files.map(NearbyTransferHistoryFile::relativePath))
        assertEquals(2, merged.totalFileCount)
        assertEquals(30, merged.totalBytes)
        assertFalse(merged.filesTruncated)
    }

    @Test
    fun `same transfer row is updated instead of duplicated`() {
        val first = NearbyTransferHistoryRules.mergeFiles(
            session(),
            listOf(TransferFileProgress("done.txt", 20, 10, TransferFileStatus.FAILED, batchId = "batch")),
            outgoing = false,
            nowMillis = 2,
        )
        val retried = NearbyTransferHistoryRules.mergeFiles(
            first,
            listOf(TransferFileProgress("done.txt", 20, 20, TransferFileStatus.COMPLETED, batchId = "batch")),
            outgoing = false,
            nowMillis = 3,
        )

        assertEquals(1, retried.files.size)
        assertEquals(TransferFileStatus.COMPLETED, retried.files.single().status)
        assertFalse(retried.files.single().outgoing)
    }

    @Test
    fun `history keeps only bounded newest sessions`() {
        val sessions = (0..NearbyTransferHistoryRules.MAX_SESSIONS + 4).map { index ->
            session(id = index.toString(), updated = index.toLong() + 1)
        }

        val normalized = NearbyTransferHistoryRules.normalize(sessions)

        assertEquals(NearbyTransferHistoryRules.MAX_SESSIONS, normalized.size)
        assertEquals((NearbyTransferHistoryRules.MAX_SESSIONS + 4).toString(), normalized.first().id)
    }

    private fun session(id: String = "session", updated: Long = 1) = NearbyTransferHistorySession(
        id = id,
        peerName = "Peer",
        startedAtMillis = 1,
        updatedAtMillis = updated,
    )
}
