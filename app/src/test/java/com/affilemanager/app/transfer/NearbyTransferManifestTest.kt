package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NearbyTransferManifestTest {
    @Test fun stoppingRemainingBatchPreservesAnAlreadyFailedReceiveAndCompletedFile() {
        val tracker = NearbyReceiveFiles()
        val batch = java.util.UUID.randomUUID().toString()
        val files = listOf(TransferFileProgress("failed.mp4", 5), TransferFileProgress("done.apk", 3),
            TransferFileProgress("waiting.mp4", 7))
        tracker.announce(files, batch)
        tracker.update(1, files[0].copy(transferredBytes = 5, status = TransferFileStatus.FAILED,
            failure = TransferFailure.PERMISSION), batch)
        tracker.update(2, files[1].copy(transferredBytes = 3, status = TransferFileStatus.COMPLETED,
            localPath = "/received/done.apk"), batch)

        val stopped = tracker.cancel(batch)

        assertEquals(listOf(TransferFileStatus.FAILED, TransferFileStatus.COMPLETED, TransferFileStatus.CANCELLED),
            stopped.map { it.status })
        assertEquals(TransferFailure.PERMISSION, stopped[0].failure)
        assertEquals(5L, stopped[0].transferredBytes)
        assertEquals("/received/done.apk", stopped[1].localPath)
    }

    @Test fun cancellingAnAlreadyFailedFileDoesNotEraseItsFailureState() {
        val tracker = NearbyReceiveFiles()
        val batch = java.util.UUID.randomUUID().toString()
        val file = TransferFileProgress("failed.mp4", 5, transferredBytes = 5,
            status = TransferFileStatus.FAILED, failure = TransferFailure.PERMISSION)
        tracker.announce(listOf(file), batch)

        assertEquals(file.copy(batchId = batch), tracker.cancelFile(batch, 1).single())
    }

    @Test fun senderAndReceiverKeepTheSameTerminalResultsWhenTheRestStops() {
        listOf(TransferFileStatus.FAILED, TransferFileStatus.COMPLETED, TransferFileStatus.CANCELLED).forEach { status ->
            val file = TransferFileProgress("same.mp4", 5, transferredBytes = 5,
                status = status, failure = if (status == TransferFileStatus.FAILED) TransferFailure.PERMISSION else null)
            assertEquals(file, file.stopPending(TransferFileStatus.CANCELLED))
            assertEquals(file, file.stopPending(TransferFileStatus.FAILED))
        }
        assertEquals(TransferFileStatus.CANCELLED,
            TransferFileProgress("waiting.mp4", 5).stopPending(TransferFileStatus.CANCELLED).status)
        assertThrows(IllegalArgumentException::class.java) {
            TransferFileProgress("waiting.mp4", 5).stopPending(TransferFileStatus.COMPLETED)
        }
    }

    @Test fun emptyManifestRoundTripSupportsPairingWithoutFiles() {
        assertEquals(emptyList<TransferFileProgress>(), NearbyTransferManifest.decode(NearbyTransferManifest.encode(emptyList())))
    }

    @Test fun multiGigabyteFileSizeRoundTripsWithoutIntegerOverflow() {
        val size = 4L * 1_024 * 1_024 * 1_024

        val decoded = NearbyTransferManifest.decode(
            NearbyTransferManifest.encode(listOf(TransferFileProgress("video-2160p.mp4", size))),
        )

        assertEquals(size, decoded.single().sizeBytes)
    }

    @Test fun transferNotificationLocalizesStatusButNeverTranslatesAFileName() {
        assertEquals(
            "Sending on the same private network",
            nearbyTransferNotificationText(
                NearbyTransferState(message = "Siunčiama tame pačiame privačiame tinkle"),
                "en",
                "Preparing transfer…",
            ),
        )
        assertEquals(
            "Siuntimas nepavyko",
            nearbyTransferNotificationText(
                NearbyTransferState(message = "Transfer failed"),
                "lt",
                "Ruošiamas siuntimas…",
            ),
        )
        assertEquals(
            "Nuotrauka lietuvišku vardu.jpg",
            nearbyTransferNotificationText(
                NearbyTransferState(currentFile = "Nuotrauka lietuvišku vardu.jpg", message = "Siuntimas nepavyko"),
                "en",
                "Preparing transfer…",
            ),
        )
    }

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
            NearbyTransferManifest.encode(List(NearbySourcePreparer.MAX_FILES + 1) { TransferFileProgress("$it.txt", 0) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyTransferManifest.encode(List(9) { TransferFileProgress("$it.txt", LanHttpServer.MAX_UPLOAD_BYTES) })
        }
        listOf("-1", "1.5", (LanHttpServer.MAX_UPLOAD_BYTES + 1).toString()).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) {
                NearbyTransferManifest.decode("""{"version":1,"files":[{"path":"file.txt","size":$size}]}""".toByteArray())
            }
        }
    }

    @Test fun requestedLargeTransferLimitsStayExplicitAndLongBased() {
        assertEquals(8_000, NearbySourcePreparer.MAX_FILES)
        assertEquals(32L * 1_024L * 1_024L * 1_024L, LanHttpServer.MAX_UPLOAD_BYTES)
        assertEquals(60L * 1_024L * 1_024L * 1_024L, NearbySourcePreparer.MAX_TOTAL_BYTES)
        val maximumFile = NearbyTransferManifest.encode(
            listOf(TransferFileProgress("large.bin", LanHttpServer.MAX_UPLOAD_BYTES)),
        )
        assertEquals(LanHttpServer.MAX_UPLOAD_BYTES, NearbyTransferManifest.decode(maximumFile).single().sizeBytes)
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

    @Test fun cancellingOneReceiverIndexLeavesTheOtherDuplicateTransferable() {
        val tracker = NearbyReceiveFiles()
        val batch = java.util.UUID.randomUUID().toString()
        val files = listOf(TransferFileProgress("same.txt", 5), TransferFileProgress("same.txt", 5))
        tracker.announce(files, batch)

        val cancelled = tracker.cancelFile(batch, 1)

        assertEquals(listOf(TransferFileStatus.CANCELLED, TransferFileStatus.WAITING), cancelled.map { it.status })
        tracker.validate(2, "same.txt", 5, batch)
        assertEquals(TransferFileStatus.TRANSFERRING, tracker.status(batch, 2))
    }
}
