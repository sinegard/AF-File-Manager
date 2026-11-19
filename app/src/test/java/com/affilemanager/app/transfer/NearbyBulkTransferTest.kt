package com.affilemanager.app.transfer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NearbyBulkTransferTest {
    @Test fun combinedPathMetadataCannotGrowAcrossPendingBatches() {
        val path = List(11) { "d".repeat(200) }.joinToString("/") + "/file.txt"
        val files = List(1_000) { TransferFileProgress(path, 0) }
        val tracker = NearbyReceiveFiles()
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        tracker.announce(files, first)
        assertThrows(IllegalArgumentException::class.java) { tracker.announce(files, second) }
        assertEquals(1_000, tracker.snapshot().size)
        assertEquals(TransferFileStatus.WAITING, tracker.status(first, 1))
        tracker.cancel(first)
        assertEquals(1_000, tracker.announce(files, second).size)
        assertNull(tracker.status(first, 1))
    }

    @Test fun receiverUpdatesAllThirtyThousandRowsWithoutMutatingPublishedSnapshots() {
        val tracker = NearbyReceiveFiles()
        val input = List(30_000) { TransferFileProgress("$it.txt", 1) }
        val original = tracker.announce(input)
        val started = System.nanoTime()
        input.forEachIndexed { index, file ->
            tracker.validate(index + 1, file.relativePath, 1)
            tracker.record(index + 1, file.copy(transferredBytes = 1, status = TransferFileStatus.COMPLETED))
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Bulk row updates took ${elapsedMillis}ms", elapsedMillis < 10_000)
        assertEquals(30_000L, tracker.totals().receivedBytes)
        assertEquals(30_000L, tracker.totals().totalBytes)
        assertTrue(tracker.totals().finished)
        assertTrue(tracker.snapshot().all { it.status == TransferFileStatus.COMPLETED })
        assertTrue(original.all { it.status == TransferFileStatus.WAITING && it.transferredBytes == 0L })
        println("AF_BULK_30000_ROW_UPDATES_MS=$elapsedMillis")
    }

    @Test fun metadataAdmissionLeavesOneQuarterOfTheAvailableHeapUntouched() {
        NearbyTransferMemory.requireAvailable(30_000, 600_000, available = 128L * 1_048_576)
        assertThrows(IllegalArgumentException::class.java) {
            NearbyTransferMemory.requireAvailable(30_000, 600_000, available = 48L * 1_048_576)
        }
        assertThrows(IllegalArgumentException::class.java) { NearbyTransferMemory.requireAvailable(30_001, 1) }
    }

    @Test fun absentOrBrokenCapabilitiesStayLegacyAndUntrustedLimitsCannotWidenOurs() {
        listOf(null, "", "not-a-number", "0", "-1", "9999999999999999").forEach {
            assertEquals(8_000, NearbyTransferCapacity.advertised(it).files)
        }
        assertEquals(30_000, NearbyTransferCapacity.advertised("1000000").files)
        assertEquals(15_000, NearbyTransferCapacity.advertised("15000").files)
        assertThrows(IllegalArgumentException::class.java) { NearbyTransferCapacity().requireFiles(8_001) }
        NearbyTransferCapacity(30_000).requireFiles(30_000)
    }

    @Test fun fullRequestedFileCountFitsTheSameVersionOneManifest() {
        val files = List(30_000) { TransferFileProgress("folder/file-$it.txt", 1) }
        val decoded = NearbyTransferManifest.decode(NearbyTransferManifest.encode(files))
        assertEquals(30_000, decoded.size)
        assertEquals(files.first(), decoded.first())
        assertEquals(files.last(), decoded.last())
    }

    @Test fun oneRequestedBatchCannotConsumeTheEntireSessionRequestBudget() {
        // Login, manifest and peer announcement, every folder, then every file.
        assertTrue(LanHttpServer.MAX_REQUESTS_PER_SESSION > 30_000 + 8_000 + 3)
        assertTrue(NearbySourcePreparer.MAX_DIRECTORIES >= 8_000)
    }

    @Test fun largerFileCountStillUsesOnlyTheFiveExistingWorkers() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val visited = BooleanArray(30_000)
        forEachFileBounded(visited.size, 5, abort = { fail("Valid bulk loop aborted") }) { index ->
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            try {
                assertFalse(visited[index])
                visited[index] = true
                if (index < 10) delay(1)
            } finally { active.decrementAndGet() }
        }
        assertTrue(visited.all { it })
        assertTrue(maximum.get() in 1..5)
        assertEquals(0, active.get())
    }
}
