package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AfPlanEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun oneSourceCopiesToTwoIndependentDestinationsWithStrongReceipts() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/report.txt", "content", "phone")
        val first = storage.directory("/first", "server-a")
        val second = storage.directory("/second", "server-b")
        val plan = plan(
            listOf(AfSourceRef(sourceLocation, "report.txt")),
            listOf(AfDestinationRef(first), AfDestinationRef(second)),
            verification = TransferVerification.SHA256,
        )
        val result = execute(plan, storage)

        assertEquals(AfExecutionStatus.COMPLETED, result.first.status)
        assertArrayEquals("content".toByteArray(), storage.bytes(child(first, "report.txt")))
        assertArrayEquals("content".toByteArray(), storage.bytes(child(second, "report.txt")))
        assertEquals(2, result.second.items.count { it.status == AfReceiptItemStatus.COPIED })
        assertTrue(result.second.items.all { it.directory || it.sha256?.length == 64 })
    }

    @Test
    fun moveDeletesSourceOnlyAfterEveryRequiredDestinationPassesSha256() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/photo.bin", byteArrayOf(1, 2, 3), "phone")
        val first = storage.directory("/one", "server-a")
        val second = storage.directory("/two", "server-b")
        val corruptTarget = child(second, "photo.bin")
        storage.corruptInstallAt = corruptTarget.identityKey()
        val plan = plan(
            listOf(AfSourceRef(sourceLocation, "photo.bin")),
            listOf(AfDestinationRef(first), AfDestinationRef(second)),
            verification = TransferVerification.SIZE,
            deleteSources = true,
        )
        val preflight = AfPlanPreflight().preview(plan, storage)
        val writer = RecordingWriter()

        val failure = runCatching {
            AfPlanEngine(temporary.newFolder()).execute(
                preflight,
                AfExecutionState(executionId = preflight.executionId),
                emptyList(),
                storage,
                writer,
                OperationContext.background(),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(storage.exists(sourceLocation))
        assertFalse(writer.states.last().sourcesDeleted)
        assertEquals(AfExecutionStatus.FAILED, writer.states.last().status)
    }

    @Test
    fun moveDeletesSourceAfterAllRequiredCopiesAreStronglyRechecked() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/photo.bin", byteArrayOf(1, 2, 3), "phone")
        val first = storage.directory("/one", "server-a")
        val second = storage.directory("/two", "server-b")
        val result = execute(
            plan(
                listOf(AfSourceRef(sourceLocation, "photo.bin")),
                listOf(AfDestinationRef(first), AfDestinationRef(second)),
                verification = TransferVerification.SIZE,
                deleteSources = true,
            ),
            storage,
        )

        assertEquals(AfExecutionStatus.COMPLETED, result.first.status)
        assertTrue(result.first.sourcesDeleted)
        assertFalse(storage.exists(sourceLocation))
        assertFalse(result.second.undoAvailable)
    }

    @Test
    fun failedReplacementRestoresThePreviousFileAndClearsRecoveryCheckpoint() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/report.txt", "new", "phone")
        val destination = storage.directory("/target", "server")
        val target = storage.file("/target/report.txt", "old", "server")
        storage.failInstallAt = target.identityKey()
        val plan = plan(
            listOf(AfSourceRef(sourceLocation, "report.txt")),
            listOf(AfDestinationRef(destination)),
            conflict = ConflictPolicy.REPLACE,
            verification = TransferVerification.SHA256,
        )
        val preflight = AfPlanPreflight().preview(plan, storage)
        val writer = RecordingWriter()

        val failure = runCatching {
            AfPlanEngine(temporary.newFolder()).execute(
                preflight,
                AfExecutionState(executionId = preflight.executionId),
                emptyList(),
                storage,
                writer,
                OperationContext.background(),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertArrayEquals("old".toByteArray(), storage.bytes(target))
        assertEquals(null, writer.states.last().activeBackup)
        assertEquals(AfExecutionStatus.FAILED, writer.states.last().status)
    }

    @Test
    fun undoRefusesAChangedCopyAndRemovesAnUnchangedCopy() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/note.txt", "original", "phone")
        val destination = storage.directory("/target", "server")
        val (_, receipt) = execute(
            plan(
                listOf(AfSourceRef(sourceLocation, "note.txt")),
                listOf(AfDestinationRef(destination)),
                verification = TransferVerification.SHA256,
            ),
            storage,
        )
        val target = child(destination, "note.txt")
        val undo = AfTimelineUndoEngine()
        val safePreview = undo.preview(receipt, storage)
        assertTrue(safePreview.canRun)

        storage.file("/target/note.txt", "changed", "server")
        val changedPreview = undo.preview(receipt, storage)
        assertFalse(changedPreview.canRun)
        assertEquals(AfUndoDisposition.CHANGED, changedPreview.items.single().disposition)

        storage.file("/target/note.txt", "original", "server")
        undo.execute(receipt, undo.preview(receipt, storage), storage, OperationContext.background())
        assertFalse(storage.exists(target))
        assertTrue(storage.exists(sourceLocation))
    }

    @Test
    fun undoRefusesToRemoveACopiedFolderAfterAnUnrecordedChildAppears() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/phone/project", "phone")
        storage.file("/phone/project/copied.txt", "copy", "phone")
        val destination = storage.directory("/target", "server")
        val (_, receipt) = execute(
            plan(
                listOf(AfSourceRef(sourceRoot, "project")),
                listOf(AfDestinationRef(destination)),
                verification = TransferVerification.SHA256,
            ),
            storage,
        )
        val targetRoot = child(destination, "project")
        val unexpected = storage.file("/target/project/created-later.txt", "keep", "server")

        val preview = AfTimelineUndoEngine().preview(receipt, storage)

        assertFalse(preview.canRun)
        assertTrue(preview.items.any {
            it.location.identityKey() == targetRoot.identityKey() &&
                it.disposition == AfUndoDisposition.CHANGED &&
                it.detail == "The folder contains items that were not created by this operation"
        })
        assertTrue(storage.exists(child(targetRoot, "copied.txt")))
        assertTrue(storage.exists(unexpected))
    }

    @Test
    fun interruptedExecutionContinuesFromItsDurableCheckpointWithoutCopyingCompletedFilesAgain() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/phone/project", "phone")
        val firstSource = storage.file("/phone/project/first.txt", "first", "phone")
        storage.file("/phone/project/second.txt", "second", "phone")
        val destination = storage.directory("/server", "server")
        val plan = plan(
            sources = listOf(AfSourceRef(sourceRoot, "project")),
            destinations = listOf(AfDestinationRef(destination)),
            verification = TransferVerification.SHA256,
        )
        val preview = AfPlanPreflight().preview(plan, storage)
        val targetRoot = child(destination, "project")
        val firstTarget = child(targetRoot, "first.txt")
        storage.createDirectory(targetRoot)
        storage.file(firstTarget.path, "first", "server")
        val firstSha = storage.sha256(firstSource)
        val initialJournal = listOf(
            AfReceiptItem(
                source = sourceRoot,
                destination = targetRoot,
                directory = true,
                status = AfReceiptItemStatus.COPIED,
                sizeBytes = 0,
            ),
            AfReceiptItem(
                source = firstSource,
                destination = firstTarget,
                directory = false,
                status = AfReceiptItemStatus.COPIED,
                sizeBytes = 5,
                sha256 = firstSha,
            ),
        )
        val state = AfExecutionState(
            executionId = preview.executionId,
            status = AfExecutionStatus.INTERRUPTED,
            nextProjectionIndex = 0,
            nextEntryIndex = 2,
            completedFiles = 1,
            completedBytes = 5,
            attempt = 1,
            startedAtMillis = 10,
        )

        val result = AfPlanEngine(temporary.newFolder()).execute(
            preview,
            state,
            initialJournal,
            storage,
            RecordingWriter(),
            OperationContext.background(),
        )

        assertEquals(AfExecutionStatus.COMPLETED, result.first.status)
        assertEquals(2, result.first.attempt)
        assertEquals(2, result.first.completedFiles)
        assertEquals(listOf(child(targetRoot, "second.txt").identityKey()), storage.installed)
        assertArrayEquals("first".toByteArray(), storage.bytes(firstTarget))
        assertArrayEquals("second".toByteArray(), storage.bytes(child(targetRoot, "second.txt")))
        assertEquals(3, result.second.items.size)
    }

    @Test
    fun replacementUndoRestoresThePreviousVersionOnlyWhileTheNewVersionStillMatches() = runTest {
        val storage = FakeAfStorage()
        val sourceLocation = storage.file("/phone/report.txt", "new", "phone")
        val destination = storage.directory("/target", "server")
        val target = storage.file("/target/report.txt", "old", "server")
        val (_, receipt) = execute(
            plan(
                listOf(AfSourceRef(sourceLocation, "report.txt")),
                listOf(AfDestinationRef(destination)),
                conflict = ConflictPolicy.REPLACE,
                verification = TransferVerification.SHA256,
            ),
            storage,
        )
        val replacement = receipt.items.single()
        assertEquals(AfReceiptItemStatus.REPLACED, replacement.status)
        assertNotNull(replacement.backup)

        val undo = AfTimelineUndoEngine()
        val preview = undo.preview(receipt, storage)
        assertTrue(preview.canRun)
        undo.execute(receipt, preview, storage, OperationContext.background())

        assertArrayEquals("old".toByteArray(), storage.bytes(target))
        assertFalse(storage.exists(requireNotNull(replacement.backup)))
    }

    @Test
    fun interruptedMoveResumesItsItemizedSourceDeletionWithoutNeedingDeletedSources() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/phone/folder", "phone")
        val first = storage.file("/phone/folder/a.txt", "a", "phone")
        val second = storage.file("/phone/folder/b.txt", "b", "phone")
        val destination = storage.directory("/server", "server")
        val plan = plan(
            sources = listOf(AfSourceRef(sourceRoot, "folder")),
            destinations = listOf(AfDestinationRef(destination)),
            verification = TransferVerification.SIZE,
            deleteSources = true,
        )
        val preview = AfPlanPreflight().preview(plan, storage)
        val writer = RecordingWriter()
        storage.failDeleteAt = first.identityKey()

        val failure = runCatching {
            AfPlanEngine(temporary.newFolder()).execute(
                preview,
                AfExecutionState(executionId = preview.executionId),
                emptyList(),
                storage,
                writer,
                OperationContext.background(),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(writer.states.last().sourceDeletionPrepared)
        assertTrue(storage.exists(sourceRoot))
        assertTrue(storage.exists(first))
        assertFalse(storage.exists(second))
        assertTrue(writer.journals.last().any { it.status == AfReceiptItemStatus.DELETED && it.source == second })

        storage.failDeleteAt = null
        val resumeState = writer.states.last().copy(
            status = AfExecutionStatus.INTERRUPTED,
            failedItems = 0,
            errors = emptyList(),
            finishedAtMillis = null,
        )
        val resumed = AfPlanEngine(temporary.newFolder()).execute(
            preview,
            resumeState,
            writer.journals.last(),
            storage,
            RecordingWriter(),
            OperationContext.background(),
        )

        assertEquals(AfExecutionStatus.COMPLETED, resumed.first.status)
        assertTrue(resumed.first.sourcesDeleted)
        assertFalse(storage.exists(sourceRoot))
        assertEquals(3, resumed.second.items.count { it.status == AfReceiptItemStatus.DELETED })
        assertTrue(resumed.second.items.filter { it.status == AfReceiptItemStatus.DELETED && !it.directory }.all {
            it.sha256?.length == 64
        })
    }

    @Test
    fun retryAfterContinueOnErrorVerifiesCompletedFilesAndCopiesOnlyTheFailedFile() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/phone/project", "phone")
        storage.file("/phone/project/first.txt", "first", "phone")
        storage.file("/phone/project/second.txt", "second", "phone")
        val destination = storage.directory("/server", "server")
        val targetRoot = child(destination, "project")
        val failedTarget = child(targetRoot, "second.txt")
        storage.failInstallAt = failedTarget.identityKey()
        val plan = plan(
            sources = listOf(AfSourceRef(sourceRoot, "project")),
            destinations = listOf(AfDestinationRef(destination)),
            verification = TransferVerification.SHA256,
            failure = TransferFailurePolicy.SKIP_AND_CONTINUE,
        )
        val preview = AfPlanPreflight().preview(plan, storage)
        val writer = RecordingWriter()
        val first = AfPlanEngine(temporary.newFolder()).execute(
            preview,
            AfExecutionState(executionId = preview.executionId),
            emptyList(),
            storage,
            writer,
            OperationContext.background(),
        )

        assertEquals(AfExecutionStatus.COMPLETED_WITH_ERRORS, first.first.status)
        assertEquals(1, first.first.failedItems)
        val firstTarget = child(targetRoot, "first.txt")
        assertArrayEquals("first".toByteArray(), storage.bytes(firstTarget))
        val installsBeforeRetry = storage.installed.toList()

        storage.failInstallAt = null
        val prepared = AfExecutionRetry.prepare(AfExecutionRecord(preview, first.first, first.second.items))
        val retried = AfPlanEngine(temporary.newFolder()).execute(
            prepared.preflight,
            prepared.state,
            prepared.journal,
            storage,
            RecordingWriter(),
            OperationContext.background(),
        )

        assertEquals(AfExecutionStatus.COMPLETED, retried.first.status)
        assertEquals(0, retried.first.failedItems)
        assertArrayEquals("first".toByteArray(), storage.bytes(firstTarget))
        assertArrayEquals("second".toByteArray(), storage.bytes(failedTarget))
        assertEquals(installsBeforeRetry + failedTarget.identityKey(), storage.installed)
        assertFalse(retried.second.items.any { it.status == AfReceiptItemStatus.FAILED })
    }

    private suspend fun execute(
        plan: AfPlanDefinition,
        storage: FakeAfStorage,
    ): Pair<AfExecutionState, AfOperationReceipt> {
        val preview = AfPlanPreflight().preview(plan, storage)
        assertTrue(preview.blockers.toString(), preview.canRun)
        return AfPlanEngine(temporary.newFolder()).execute(
            preview,
            AfExecutionState(executionId = preview.executionId),
            emptyList(),
            storage,
            RecordingWriter(),
            OperationContext.background(),
        )
    }

    private fun plan(
        sources: List<AfSourceRef>,
        destinations: List<AfDestinationRef>,
        conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        verification: TransferVerification = TransferVerification.SIZE,
        failure: TransferFailurePolicy = TransferFailurePolicy.STOP,
        deleteSources: Boolean = false,
    ) = AfPlanDefinition(
        id = "engine-plan",
        name = "Engine test",
        sources = sources,
        destinations = destinations,
        conflictPolicy = conflict,
        verification = verification,
        failurePolicy = failure,
        deleteSourcesAfterVerifiedCopies = deleteSources,
    )

    private class RecordingWriter : AfExecutionWriter {
        val states = mutableListOf<AfExecutionState>()
        val journals = mutableListOf<List<AfReceiptItem>>()
        override fun saveState(state: AfExecutionState) {
            states += state
        }

        override fun saveJournal(executionId: String, items: List<AfReceiptItem>) {
            journals += items.toList()
        }
    }
}
