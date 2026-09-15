package com.affilemanager.app.transfer

import com.affilemanager.app.ui.screens.nearbyFileIndexInBatch
import org.junit.Assert.*
import org.junit.Test

class NearbySendQueueTest {
    private val peer = NearbyPairing("192.168.1.10", 8080, "test-password")
    private fun source(name: String) = PreparedNearbyTransfer(listOf("/test/$name"), fileSizes = listOf(10))

    @Test fun appendPreservesProgressAndSendsInAdmissionOrder() {
        var snapshot = NearbyTransferState()
        val queue = NearbySendQueue { snapshot = it }
        val first = queue.enqueue(peer, source("one.txt"), null)
        queue.ready(first.id, first.state.files, true)
        assertEquals(first.id, queue.takeNext()!!.id)
        queue.update(first.id, first.state.copy(status = NearbyTransferStatus.RUNNING,
            files = first.state.files.map { it.copy(status = TransferFileStatus.TRANSFERRING, transferredBytes = 4) }))
        val second = queue.enqueue(peer, source("two.txt"), null)
        queue.ready(second.id, second.state.files, true)
        assertNull(queue.takeNext())
        assertEquals(listOf("one.txt", "two.txt"), snapshot.files.map { it.relativePath })
        assertEquals(4L, snapshot.files.first().transferredBytes)
        assertEquals(TransferFileStatus.WAITING, snapshot.files.last().status)
        queue.update(first.id, first.state.copy(status = NearbyTransferStatus.COMPLETED,
            files = first.state.files.map { it.copy(status = TransferFileStatus.COMPLETED, transferredBytes = 10) }))
        assertEquals(second.id, queue.takeNext()!!.id)
        assertEquals(2, snapshot.files.size)
    }

    @Test fun oldServiceCleanupCannotCancelNewlySubmittedWorkOrResurrectCancelledProgress() {
        val queue = NearbySendQueue { }
        val old = queue.enqueue(peer, source("old.txt"), null)
        queue.ready(old.id, old.state.files, true)
        queue.takeNext()
        val fresh = queue.enqueue(peer, source("fresh.txt"), null)
        queue.cancel(setOf(old.id))
        queue.update(old.id, old.state.copy(status = NearbyTransferStatus.RUNNING))
        assertEquals(NearbyTransferStatus.CANCELLED, queue.get(old.id)!!.state.status)
        assertEquals(NearbyTransferStatus.STARTING, queue.get(fresh.id)!!.state.status)
        queue.ready(fresh.id, fresh.state.files, true)
        assertEquals(fresh.id, queue.takeNext()!!.id)
    }

    @Test fun queueRejectsUnboundedWorkAndAnotherPeerWithoutReplacingExistingFiles() {
        var snapshot = NearbyTransferState()
        val queue = NearbySendQueue { snapshot = it }
        repeat(16) { queue.enqueue(peer, source("$it.txt"), null) }
        assertTrue(runCatching { queue.enqueue(peer, source("overflow.txt"), null) }.isFailure)
        assertTrue(runCatching { queue.enqueue(peer.copy(host = "192.168.1.11"), source("other.txt"), null) }.isFailure)
        assertEquals(16, snapshot.files.size)
        assertEquals((0..15).map { "$it.txt" }, snapshot.files.map { it.relativePath })
    }

    @Test fun emptyBatchIsAdmittedForPairingWithoutInventingAFile() {
        val queue = NearbySendQueue { }

        val batch = queue.enqueue(peer, PreparedNearbyTransfer.empty(), null)
        queue.ready(batch.id, emptyList(), announced = true)

        assertEquals(batch.id, queue.takeNext()!!.id)
        assertTrue(batch.state.files.isEmpty())
    }

    @Test fun exactFileIndexCancelsOnlyThatDuplicateNameAndProgressCannotResurrectIt() {
        var snapshot = NearbyTransferState()
        val queue = NearbySendQueue { snapshot = it }
        val duplicateNames = PreparedNearbyTransfer(
            paths = listOf("/test/first.txt", "/test/second.txt"),
            relativePaths = listOf("same.txt", "same.txt"),
            fileSizes = listOf(10, 10),
        )
        val batch = queue.enqueue(peer, duplicateNames, null)
        queue.ready(batch.id, batch.state.files, announced = true)

        assertTrue(queue.cancelFile(batch.id, 2))
        assertEquals(listOf(TransferFileStatus.WAITING, TransferFileStatus.CANCELLED), snapshot.files.map { it.status })

        queue.update(
            batch.id,
            snapshot.copy(files = snapshot.files.map { it.copy(status = TransferFileStatus.TRANSFERRING) }),
        )
        assertEquals(listOf(TransferFileStatus.TRANSFERRING, TransferFileStatus.CANCELLED), snapshot.files.map { it.status })
        assertFalse(queue.cancelFile(batch.id, 0))
        assertFalse(queue.cancelFile(batch.id, 3))
    }

    @Test fun combinedQueueRowsMapToTheirOwnBatchIndex() {
        val rows = listOf(
            TransferFileProgress("first/a.txt", 1, batchId = "batch-a"),
            TransferFileProgress("first/b.txt", 1, batchId = "batch-a"),
            TransferFileProgress("second/a.txt", 1, batchId = "batch-b"),
            TransferFileProgress("second/b.txt", 1, batchId = "batch-b"),
        )

        assertEquals(2, nearbyFileIndexInBatch(rows, 1))
        assertEquals(1, nearbyFileIndexInBatch(rows, 2))
        assertEquals(2, nearbyFileIndexInBatch(rows, 3))
        assertEquals(0, nearbyFileIndexInBatch(rows, 8))
    }
}
