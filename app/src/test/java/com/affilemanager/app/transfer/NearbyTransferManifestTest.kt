package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NearbyTransferManifestTest {
    @Test fun addingPendingFilesPreservesTheActiveBatchAndBothStayVisible() {
        val tracker = NearbyReceiveFiles()
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        tracker.announce(listOf(TransferFileProgress("first.txt", 8)), first)
        tracker.validate(1, "first.txt", 8, first)
        val combined = tracker.announce(listOf(TransferFileProgress("next.txt", 4)), second)
        assertEquals(listOf("first.txt", "next.txt"), combined.map { it.relativePath })
        assertEquals(TransferFileStatus.TRANSFERRING, combined[0].status)
        assertEquals(TransferFileStatus.WAITING, combined[1].status)
        assertThrows(IllegalArgumentException::class.java) { tracker.validate(1, "next.txt", 4, second) }
    }

    @Test fun newBatchDoesNotResetAnActiveUploadAndStaleRetriesCannotMutateIt() {
        val tracker = NearbyReceiveFiles()
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        val file = TransferFileProgress("first.txt", 3)
        tracker.announce(listOf(file), first)
        tracker.validate(1, file.relativePath, 3, first)
        tracker.announce(listOf(TransferFileProgress("second.txt", 2)), second)
        assertThrows(IllegalArgumentException::class.java) { tracker.validate(1, "second.txt", 2, second) }
        tracker.update(1, file.copy(status = TransferFileStatus.COMPLETED), first)
        tracker.announce(listOf(TransferFileProgress("second.txt", 2)), second)
        assertThrows(IllegalArgumentException::class.java) { tracker.validate(1, "first.txt", 3, first) }
        assertEquals(TransferFileStatus.COMPLETED, tracker.announce(listOf(file), first)[0].status)
        tracker.validate(1, "second.txt", 2, second)
    }
    @Test fun roundTripOnlyExposesRelativeNamesAndSizes() {
        val wire = NearbyTransferManifest.encode(listOf(TransferFileProgress("album/été.jpg", 123, localPath = "/private/source.jpg")))
        assertFalse(wire.toString(Charsets.UTF_8).contains("/private"))
        assertEquals(listOf(TransferFileProgress("album/été.jpg", 123)), NearbyTransferManifest.decode(wire))
    }

    @Test fun rejectsTraversalAbsoluteNamesOversizedSetsAndFractionalSizes() {
        listOf("../secret", "/secret", "one//two", "a/./b", "a\\b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { NearbyTransferManifest.encode(listOf(TransferFileProgress(path, 2))) }
        }
        assertThrows(IllegalArgumentException::class.java) { NearbyTransferManifest.decode(ByteArray(NearbyTransferManifest.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { NearbyTransferManifest.decode(("[".repeat(500) + "]".repeat(500)).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyTransferManifest.encode(List(1001) { TransferFileProgress("$it.txt", 0) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyTransferManifest.encode(List(6) { TransferFileProgress("$it.txt", LanHttpServer.MAX_UPLOAD_BYTES) })
        }
        listOf("-1", "1.5", "1073741825").forEach { size ->
            assertThrows(IllegalArgumentException::class.java) {
                NearbyTransferManifest.decode("""{"version":1,"files":[{"path":"file.txt","size":$size}]}""".toByteArray())
            }
        }
    }

    @Test fun duplicateNamesKeepSeparateIndicesAndOnlyCompletedReceiverFilesCanOpen() {
        val tracker = NearbyReceiveFiles()
        val files = listOf(TransferFileProgress("same.jpg", 5), TransferFileProgress("same.jpg", 5))
        tracker.announce(files)
        tracker.validate(1, "same.jpg", 5)
        assertThrows(IllegalArgumentException::class.java) { tracker.validate(1, "same.jpg", 5) }
        val progress = tracker.update(1, files[0].copy(transferredBytes = 2, status = TransferFileStatus.TRANSFERRING))
        assertNull(progress[0].localPath)
        val completed = tracker.update(1, files[0].copy(transferredBytes = 5, status = TransferFileStatus.COMPLETED, localPath = "/received/same (1).jpg"))
        assertEquals("/received/same (1).jpg", completed[0].localPath)
        assertEquals(TransferFileStatus.WAITING, completed[1].status)
        assertEquals(completed, tracker.announce(files)) // A repeated manifest must not reset progress.
        tracker.validate(2, "same.jpg", 5)
        assertThrows(IllegalArgumentException::class.java) { tracker.announce(listOf(TransferFileProgress("other.txt", 1))) }
    }
}
