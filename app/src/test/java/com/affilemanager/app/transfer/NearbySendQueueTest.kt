package com.affilemanager.app.transfer

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
}
