package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AfWorkflowRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun plansRoundTripWithoutEmbeddingRemoteCredentials() {
        val root = temporary.newFolder("plans")
        val repository = AfPlanRepository(root)
        val plan = AfPlanDefinition(
            id = "portable-plan",
            name = "Phone to server",
            sources = listOf(
                AfSourceRef(AfLocationRef.remote("source-profile", "Source", "/in/report.txt"), "report.txt"),
            ),
            destinations = listOf(
                AfDestinationRef(AfLocationRef.remote("target-profile", "Target", "/archive")),
            ),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )

        val saved = repository.save(plan)
        val restored = repository.find(plan.id)
        val persisted = File(root, "af_workflows_v1/plans.json").readText()

        assertEquals(saved, restored)
        assertTrue(persisted.contains("source-profile"))
        assertTrue(persisted.contains("target-profile"))
        assertFalse(persisted.contains("password", ignoreCase = true))
        assertFalse(persisted.contains("credential", ignoreCase = true))
    }

    @Test
    fun malformedMetadataIsQuarantinedInsteadOfPartiallyLoaded() {
        val root = temporary.newFolder("corrupt")
        val repository = AfPlanRepository(root)
        val storageDirectory = File(root, "af_workflows_v1")
        File(storageDirectory, "plans.json").writeText("{not-json")

        assertTrue(repository.list().isEmpty())
        assertFalse(File(storageDirectory, "plans.json").exists())
        assertEquals(1, File(storageDirectory, "corrupt").listFiles()?.size)
    }

    @Test
    fun interruptedExecutionPersistsItsRecoveryBackupAndJournal() = runTest {
        val root = temporary.newFolder("execution")
        val storage = FakeAfStorage()
        val source = storage.file("/phone/file.txt", "content", "phone")
        val destination = storage.directory("/server", "server")
        val plan = AfPlanDefinition(
            id = "recovery-plan",
            name = "Recovery",
            sources = listOf(AfSourceRef(source, "file.txt")),
            destinations = listOf(AfDestinationRef(destination)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val preview = AfPlanPreflight().preview(plan, storage, nowMillis = 50)
        val repository = AfExecutionRepository(root)
        repository.create(preview)
        val backup = AfLocationRef.remote("server", "server", "/.file.af-backup")
        val state = AfExecutionState(
            executionId = preview.executionId,
            status = AfExecutionStatus.INTERRUPTED,
            nextEntryIndex = 1,
            activeBackup = backup,
            attempt = 1,
            startedAtMillis = 60,
        )
        val journal = listOf(
            AfReceiptItem(
                source = source,
                destination = child(destination, "file.txt"),
                directory = false,
                status = AfReceiptItemStatus.COPIED,
                sizeBytes = 7,
                sha256 = storage.sha256(source),
            ),
        )
        repository.saveJournal(preview.executionId, journal)
        repository.save(state)

        val restored = repository.load(preview.executionId)

        assertEquals(preview, restored.preflight)
        assertEquals(state, restored.state)
        assertEquals(journal, restored.journal)
        assertEquals(backup, restored.state.activeBackup)
        assertNotNull(repository.listRecoverable().singleOrNull())
    }

    @Test
    fun consumedUndoIsDurablyRemovedFromTheOriginalReceipt() {
        val root = temporary.newFolder("timeline")
        val repository = AfTimelineRepository(root)
        val source = AfLocationRef.local(File(root, "source.txt").path)
        val destination = AfLocationRef.local(File(root, "destination.txt").path)
        val receipt = AfOperationReceipt(
            id = "receipt-1",
            planId = "plan-1",
            planName = "Copy",
            startedAtMillis = 10,
            finishedAtMillis = 20,
            status = AfExecutionStatus.COMPLETED,
            verification = TransferVerification.SHA256,
            items = listOf(
                AfReceiptItem(
                    source = source,
                    destination = destination,
                    directory = false,
                    status = AfReceiptItemStatus.COPIED,
                    sizeBytes = 1,
                    sha256 = "a".repeat(64),
                ),
            ),
            sourceDeletionRequested = false,
            sourcesDeleted = false,
            undoAvailable = true,
            errorCount = 0,
        )
        repository.add(receipt)

        assertTrue(repository.markUndoConsumed(receipt.id))
        assertFalse(requireNotNull(repository.get(receipt.id)).undoAvailable)
    }

    @Test
    fun timelineSearchFollowsTheFileAcrossSeveralAfOperations() {
        val root = temporary.newFolder("trace")
        val first = receipt(
            id = "receipt-first",
            source = AfLocationRef.local(File(root, "original/report.txt").path),
            destination = AfLocationRef.remote("server", "Server", "/incoming/report.txt"),
            finished = 20,
        )
        val second = receipt(
            id = "receipt-second",
            source = AfLocationRef.remote("server", "Server", "/incoming/report.txt"),
            destination = AfLocationRef.remote("server", "Server", "/archive/report.txt"),
            finished = 30,
        )
        val unrelated = receipt(
            id = "receipt-other",
            source = AfLocationRef.local(File(root, "photo.jpg").path),
            destination = AfLocationRef.remote("server", "Server", "/photos/photo.jpg"),
            finished = 40,
        )

        val traced = AfTimelineSearch.trace(listOf(unrelated, second, first), "original/report.txt")

        assertEquals(listOf("receipt-second", "receipt-first"), traced.map(AfOperationReceipt::id))
    }

    private fun receipt(
        id: String,
        source: AfLocationRef,
        destination: AfLocationRef,
        finished: Long,
    ) = AfOperationReceipt(
        id = id,
        planId = "plan-trace",
        planName = "Trace",
        startedAtMillis = finished - 1,
        finishedAtMillis = finished,
        status = AfExecutionStatus.COMPLETED,
        verification = TransferVerification.SHA256,
        items = listOf(
            AfReceiptItem(
                source = source,
                destination = destination,
                directory = false,
                status = AfReceiptItemStatus.COPIED,
                sizeBytes = 1,
                sha256 = "b".repeat(64),
            ),
        ),
        sourceDeletionRequested = false,
        sourcesDeleted = false,
        undoAvailable = true,
        errorCount = 0,
    )
}
