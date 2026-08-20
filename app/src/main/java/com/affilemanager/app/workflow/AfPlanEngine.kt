package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface AfExecutionWriter {
    fun saveState(state: AfExecutionState)
    fun saveJournal(executionId: String, items: List<AfReceiptItem>)
}

class AfPlanEngine(private val stagingDirectory: File) {
    suspend fun execute(
        preflight: AfPreflightSummary,
        initialState: AfExecutionState,
        initialJournal: List<AfReceiptItem>,
        storage: AfStorageSession,
        writer: AfExecutionWriter,
        operation: OperationContext,
    ): Pair<AfExecutionState, AfOperationReceipt> {
        require(preflight.canRun) { "AF Plan preview contains blockers" }
        require(preflight.executionId == initialState.executionId) { "AF execution identity mismatch" }
        require(initialState.nextProjectionIndex in 0..preflight.projections.size) { "Invalid AF projection checkpoint" }
        require(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "Private AF workflow cache is unavailable" }

        val journal = initialJournal.toMutableList()
        val entriesBySource = preflight.entries.groupBy(AfPlannedEntry::sourceRootIndex)
        var state = initialState.copy(
            status = AfExecutionStatus.RUNNING,
            attempt = Math.addExact(initialState.attempt, 1),
            startedAtMillis = initialState.startedAtMillis ?: System.currentTimeMillis(),
            finishedAtMillis = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writer.saveState(state)
        operation.setTotals(
            items = preflight.projections.sumOf { projection ->
                if (projection.disposition in NON_COPYING_DISPOSITIONS) 1
                else entriesBySource[projection.sourceRootIndex].orEmpty().size
            } + if (preflight.plan.deleteSourcesAfterVerifiedCopies) preflight.entries.size else 0,
            bytes = null,
        )

        try {
            for (projectionIndex in state.nextProjectionIndex until preflight.projections.size) {
                currentCoroutineContext().ensureActive()
                operation.checkpoint()
                val projection = preflight.projections[projectionIndex]
                val rootEntries = entriesBySource[projection.sourceRootIndex].orEmpty()
                require(rootEntries.isNotEmpty()) { "AF projection source is empty" }
                if (projection.disposition in NON_COPYING_DISPOSITIONS) {
                    val root = rootEntries.first()
                    val previous = journal.lastOrNull { item ->
                        item.source.identityKey() == root.source.location.identityKey() &&
                            item.destination?.identityKey() == projection.targetRoot.identityKey() &&
                            item.status in NON_COPYING_RECEIPT_STATUSES
                    }
                    if (previous != null) {
                        if (previous.status == AfReceiptItemStatus.VERIFIED_IDENTICAL) {
                            requirePriorResultStillValid(previous, storage)
                        }
                        operation.progress(itemDelta = 1, currentName = root.source.name)
                        state = state.copy(
                            nextProjectionIndex = projectionIndex + 1,
                            nextEntryIndex = 0,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        checkpoint(writer, state, journal)
                        continue
                    }
                    journal += AfReceiptItem(
                        source = root.source.location,
                        destination = projection.targetRoot,
                        directory = root.source.directory,
                        status = when (projection.disposition) {
                            AfPreflightDisposition.VERIFIED_IDENTICAL,
                            AfPreflightDisposition.POSSIBLY_IDENTICAL,
                            -> AfReceiptItemStatus.VERIFIED_IDENTICAL
                            else -> AfReceiptItemStatus.SKIPPED
                        },
                        sizeBytes = root.source.sizeBytes,
                        sha256 = root.source.sha256,
                    )
                    operation.progress(itemDelta = 1, currentName = root.source.name)
                    state = state.copy(
                        nextProjectionIndex = projectionIndex + 1,
                        nextEntryIndex = 0,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    checkpoint(writer, state, journal)
                    continue
                }

                var backup: AfLocationRef? = state.activeBackup
                if (projection.disposition == AfPreflightDisposition.REPLACE) {
                    val expectedBackup = backupLocation(projection.targetRoot, preflight.executionId)
                    if (backup != null) {
                        require(backup.identityKey() == expectedBackup.identityKey() && storage.stat(backup) != null) {
                            "The interrupted replacement recovery backup is unavailable"
                        }
                    } else if (state.nextEntryIndex == 0) {
                        val existing = storage.stat(projection.targetRoot)
                        val recoveredBackup = storage.stat(expectedBackup)
                        when {
                            recoveredBackup != null && existing == null -> backup = expectedBackup
                            recoveredBackup != null -> error("The interrupted replacement needs a fresh safety review")
                            existing != null -> {
                                storage.rename(projection.targetRoot, expectedBackup)
                                backup = expectedBackup
                            }
                        }
                        if (backup != null) {
                            state = state.copy(activeBackup = backup, updatedAtMillis = System.currentTimeMillis())
                            checkpoint(writer, state, journal)
                        }
                    }
                }

                val startEntry = if (projectionIndex == state.nextProjectionIndex) state.nextEntryIndex else 0
                for (entryPosition in startEntry until rootEntries.size) {
                    currentCoroutineContext().ensureActive()
                    operation.checkpoint()
                    val planned = rootEntries[entryPosition]
                    val sourceRef = preflight.plan.sources[planned.sourceRootIndex]
                    val target = if (planned.relativePath.isEmpty()) {
                        projection.targetRoot
                    } else {
                        child(projection.targetRoot, planned.relativePath)
                    }
                    val previous = journal.lastOrNull { item ->
                        item.source.identityKey() == planned.source.location.identityKey() &&
                            item.destination?.identityKey() == target.identityKey() &&
                            item.status in COPY_SUCCESS_STATUSES
                    }
                    if (previous != null) {
                        requirePriorResultStillValid(previous, storage)
                        operation.progress(itemDelta = 1, currentName = planned.source.name)
                        state = state.copy(
                            nextProjectionIndex = projectionIndex,
                            nextEntryIndex = entryPosition + 1,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        if ((entryPosition + 1) % CHECKPOINT_ITEMS == 0 || entryPosition == rootEntries.lastIndex) {
                            checkpoint(writer, state, journal)
                        }
                        continue
                    }
                    try {
                        val result = transferOne(
                            preflight = preflight,
                            projection = projection,
                            sourceRef = sourceRef,
                            planned = planned,
                            target = target,
                            rootBackup = backup.takeIf { entryPosition == 0 },
                            storage = storage,
                            operation = operation,
                        )
                        journal += result
                        state = state.copy(
                            nextProjectionIndex = projectionIndex,
                            nextEntryIndex = entryPosition + 1,
                            completedFiles = state.completedFiles + if (planned.source.directory) 0 else 1,
                            completedBytes = Math.addExact(
                                state.completedBytes,
                                if (planned.source.directory) 0 else planned.source.sizeBytes,
                            ),
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        operation.progress(itemDelta = 1, currentName = planned.source.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val code = safeErrorCode(error)
                        journal += AfReceiptItem(
                            source = planned.source.location,
                            destination = target,
                            directory = planned.source.directory,
                            status = AfReceiptItemStatus.FAILED,
                            sizeBytes = planned.source.sizeBytes,
                            errorCode = code,
                        )
                        val errors = (state.errors + "${planned.source.name}: $code")
                            .take(AfWorkflowLimits.MAX_RECORDED_ERRORS)
                        state = state.copy(
                            failedItems = state.failedItems + 1,
                            errors = errors,
                            nextEntryIndex = if (preflight.plan.failurePolicy == TransferFailurePolicy.STOP) {
                                entryPosition
                            } else {
                                entryPosition + 1
                            },
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        checkpoint(writer, state, journal)
                        if (preflight.plan.failurePolicy == TransferFailurePolicy.STOP) {
                            state = state.copy(
                                status = AfExecutionStatus.FAILED,
                                finishedAtMillis = System.currentTimeMillis(),
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                            writer.saveState(state)
                            throw error
                        }
                        operation.note("Skipped ${planned.source.name}: $code")
                    }
                    if ((entryPosition + 1) % CHECKPOINT_ITEMS == 0 || entryPosition == rootEntries.lastIndex) {
                        checkpoint(writer, state, journal)
                    }
                }
                state = state.copy(
                    nextProjectionIndex = projectionIndex + 1,
                    nextEntryIndex = 0,
                    activeBackup = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                checkpoint(writer, state, journal)
            }

            var sourcesDeleted = false
            if (preflight.plan.deleteSourcesAfterVerifiedCopies && state.failedItems == 0) {
                if (!state.sourceDeletionPrepared) {
                    verifyRequiredCopies(preflight, storage, operation, journal)
                    state = state.copy(
                        sourceDeletionPrepared = true,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    checkpoint(writer, state, journal)
                } else {
                    verifyRequiredCopies(preflight, storage, operation, journal)
                }
                deleteSources(preflight, storage, operation, journal, state, writer)
                sourcesDeleted = true
                state = state.copy(sourcesDeleted = true, updatedAtMillis = System.currentTimeMillis())
                checkpoint(writer, state, journal)
            }

            val finalStatus = if (state.failedItems == 0) AfExecutionStatus.COMPLETED else AfExecutionStatus.COMPLETED_WITH_ERRORS
            state = state.copy(
                status = finalStatus,
                sourcesDeleted = sourcesDeleted || state.sourcesDeleted,
                finishedAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
            )
            checkpoint(writer, state, journal)
            if (state.failedItems > 0) {
                operation.completeWithErrors(state.failedItems, "${state.failedItems} AF Plan items failed")
            } else {
                operation.setRetryable(false)
            }
            return state to buildReceipt(preflight, state, journal)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                state = rollbackActiveReplacement(preflight, state, journal, entriesBySource, storage)
                state = state.copy(
                    status = AfExecutionStatus.CANCELLED,
                    finishedAtMillis = System.currentTimeMillis(),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                checkpoint(writer, state, journal)
            }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                state = rollbackActiveReplacement(preflight, state, journal, entriesBySource, storage)
                state = state.copy(
                    status = AfExecutionStatus.FAILED,
                    failedItems = state.failedItems.coerceAtLeast(1),
                    errors = (state.errors + safeErrorCode(error)).distinct().take(AfWorkflowLimits.MAX_RECORDED_ERRORS),
                    finishedAtMillis = System.currentTimeMillis(),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                checkpoint(writer, state, journal)
            }
            throw error
        } finally {
            stagingDirectory.listFiles { file -> file.name.startsWith("af-exec-${preflight.executionId.take(12)}-") }
                ?.forEach(File::delete)
        }
    }

    private suspend fun transferOne(
        preflight: AfPreflightSummary,
        projection: AfDestinationProjection,
        sourceRef: AfSourceRef,
        planned: AfPlannedEntry,
        target: AfLocationRef,
        rootBackup: AfLocationRef?,
        storage: AfStorageSession,
        operation: OperationContext,
    ): AfReceiptItem {
        val existing = storage.stat(target)
        if (planned.source.directory) {
            if (existing != null) {
                require(existing.directory) { "A file appeared where a folder is required" }
                val mergeAllowed = preflight.plan.conflictPolicy == ConflictPolicy.MERGE
                require(mergeAllowed || projection.disposition == AfPreflightDisposition.REPLACE) {
                    "Destination folder changed after preview"
                }
                return AfReceiptItem(
                    source = planned.source.location,
                    destination = target,
                    directory = true,
                    status = if (rootBackup == null) AfReceiptItemStatus.VERIFIED_IDENTICAL else AfReceiptItemStatus.REPLACED,
                    sizeBytes = 0,
                    backup = rootBackup,
                )
            }
            storage.createDirectory(target)
            return AfReceiptItem(
                source = planned.source.location,
                destination = target,
                directory = true,
                status = if (rootBackup == null) AfReceiptItemStatus.COPIED else AfReceiptItemStatus.REPLACED,
                sizeBytes = 0,
                backup = rootBackup,
            )
        }

        val staging = File(
            stagingDirectory,
            "af-exec-${preflight.executionId.take(12)}-${UUID.randomUUID()}.tmp",
        )
        try {
            storage.materialize(
                sourceRef,
                AfEnumeratedEntry(planned.relativePath, planned.source, planned.depth),
                staging,
                operation,
            )
            require(staging.length() == planned.source.sizeBytes) { "Source changed while being staged" }
            // The operation may use a faster size check, but undo always needs a strong content proof.
            val sourceSha = sha256(staging)
            planned.source.sha256?.let { expected ->
                require(sourceSha == expected) { "Source content changed after preview" }
            }

            if (existing != null) {
                require(!existing.directory) { "A folder appeared where a file is required" }
                val effectiveVerification = if (rootBackup == null) preflight.plan.verification else TransferVerification.SHA256
                if (sameContent(staging, sourceSha, existing, effectiveVerification, storage)) {
                    return AfReceiptItem(
                        source = planned.source.location,
                        destination = target,
                        directory = false,
                        status = if (rootBackup == null) AfReceiptItemStatus.VERIFIED_IDENTICAL else AfReceiptItemStatus.REPLACED,
                        sizeBytes = planned.source.sizeBytes,
                        sha256 = sourceSha,
                        backup = rootBackup,
                    )
                }
                require(projection.disposition == AfPreflightDisposition.REPLACE || preflight.plan.conflictPolicy == ConflictPolicy.REPLACE) {
                    "Destination changed after preview"
                }
                val backup = rootBackup ?: backupLocation(target, preflight.executionId)
                require(storage.stat(backup) == null) { "A recovery backup already exists at the destination" }
                storage.rename(target, backup)
                try {
                    storage.install(staging, target, replace = false, operation = operation)
                } catch (error: Throwable) {
                    runCatching { storage.rename(backup, target) }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
                verifyInstalled(staging, sourceSha, target, preflight.plan.verification, storage)
                return AfReceiptItem(
                    source = planned.source.location,
                    destination = target,
                    directory = false,
                    status = AfReceiptItemStatus.REPLACED,
                    sizeBytes = planned.source.sizeBytes,
                    sha256 = sourceSha,
                    backup = backup,
                )
            }

            storage.install(staging, target, replace = false, operation = operation)
            try {
                verifyInstalled(staging, sourceSha, target, preflight.plan.verification, storage)
            } catch (error: Throwable) {
                runCatching { storage.delete(target, recursive = false) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            return AfReceiptItem(
                source = planned.source.location,
                destination = target,
                directory = false,
                status = if (rootBackup == null) AfReceiptItemStatus.COPIED else AfReceiptItemStatus.REPLACED,
                sizeBytes = planned.source.sizeBytes,
                sha256 = sourceSha,
                backup = rootBackup,
            )
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private suspend fun sameContent(
        sourceFile: File,
        sourceSha: String?,
        existing: AfNodeSnapshot,
        verification: TransferVerification,
        storage: AfStorageSession,
    ): Boolean {
        if (sourceFile.length() != existing.sizeBytes) return false
        return when (verification) {
            TransferVerification.SIZE -> true
            TransferVerification.SHA256 -> requireNotNull(sourceSha) == storage.sha256(existing.location)
        }
    }

    private suspend fun verifyInstalled(
        sourceFile: File,
        sourceSha: String?,
        target: AfLocationRef,
        verification: TransferVerification,
        storage: AfStorageSession,
    ) {
        val installed = storage.stat(target) ?: error("Installed destination is missing")
        require(!installed.directory && installed.sizeBytes == sourceFile.length()) { "Installed destination size verification failed" }
        if (verification == TransferVerification.SHA256) {
            require(storage.sha256(target) == requireNotNull(sourceSha)) { "Installed destination SHA-256 verification failed" }
        }
    }

    private suspend fun requirePriorResultStillValid(item: AfReceiptItem, storage: AfStorageSession) {
        val destination = requireNotNull(item.destination) { "A completed copy has no destination proof" }
        val current = storage.stat(destination) ?: error("A previously completed destination disappeared")
        require(current.directory == item.directory) { "A previously completed destination changed type" }
        if (!item.directory) {
            require(current.sizeBytes == item.sizeBytes) { "A previously completed destination changed size" }
            item.sha256?.let { proof ->
                require(storage.sha256(destination) == proof) { "A previously completed destination changed content" }
            }
        }
    }

    private suspend fun verifyRequiredCopies(
        preflight: AfPreflightSummary,
        storage: AfStorageSession,
        operation: OperationContext,
        journal: MutableList<AfReceiptItem>,
    ) {
        val entriesBySource = preflight.entries.groupBy(AfPlannedEntry::sourceRootIndex)
        val sourceHashes = HashMap<String, String>()
        journal.asSequence()
            .filter { !it.directory && it.sha256 != null }
            .forEach { item -> sourceHashes.putIfAbsent(item.source.identityKey(), requireNotNull(item.sha256)) }
        val requiredDestinationKeys = preflight.plan.destinations.withIndex()
            .filter { it.value.required }
            .map { it.index }
            .toSet()
        preflight.projections.filter { it.destinationIndex in requiredDestinationKeys }.forEach { projection ->
            require(projection.disposition !in setOf(AfPreflightDisposition.SKIP, AfPreflightDisposition.BLOCKED)) {
                "A required destination did not receive a copy"
            }
            val rootEntries = entriesBySource[projection.sourceRootIndex].orEmpty()
            rootEntries.filterNot { it.source.directory }.forEach { entry ->
                operation.checkpoint()
                val sourceKey = entry.source.location.identityKey()
                val sourceSha = sourceHashes[sourceKey] ?: entry.source.sha256
                    ?: storage.sha256(entry.source.location, operation).also { sourceHashes[sourceKey] = it }
                val target = if (entry.relativePath.isEmpty()) projection.targetRoot else child(projection.targetRoot, entry.relativePath)
                val current = storage.stat(target) ?: error("A required copied file disappeared")
                require(!current.directory && current.sizeBytes == entry.source.sizeBytes) { "A required copied file changed" }
                require(storage.sha256(target, operation) == sourceSha) { "A required copied file changed" }
            }
        }
        journal.indices.forEach { index ->
            val item = journal[index]
            if (!item.directory && item.sha256 == null) {
                sourceHashes[item.source.identityKey()]?.let { proof -> journal[index] = item.copy(sha256 = proof) }
            }
        }
    }

    private suspend fun deleteSources(
        preflight: AfPreflightSummary,
        storage: AfStorageSession,
        operation: OperationContext,
        journal: MutableList<AfReceiptItem>,
        state: AfExecutionState,
        writer: AfExecutionWriter,
    ) {
        require(preflight.plan.sources.none { it.kind == AfSourceKind.ARCHIVE_ENTRY }) {
            "Archive entries cannot be deleted after copying"
        }
        require(state.sourceDeletionPrepared) { "Source deletion proofs were not committed" }
        val deleted = journal.asSequence()
            .filter { it.status == AfReceiptItemStatus.DELETED }
            .map { it.source.identityKey() }
            .toMutableSet()
        val proofs = journal.asSequence()
            .filter { !it.directory && it.sha256 != null }
            .associate { it.source.identityKey() to requireNotNull(it.sha256) }
        val entriesBySource = preflight.entries.groupBy(AfPlannedEntry::sourceRootIndex)
        var sinceCheckpoint = 0
        preflight.plan.sources.indices.reversed().forEach { sourceIndex ->
            entriesBySource[sourceIndex].orEmpty()
                .sortedWith(compareByDescending<AfPlannedEntry> { it.depth }.thenByDescending { it.relativePath })
                .forEach entryLoop@{ entry ->
                    operation.checkpoint()
                    val location = entry.source.location
                    val key = location.identityKey()
                    if (key in deleted) return@entryLoop
                    val current = storage.stat(location)
                    if (current != null) {
                        require(current.directory == entry.source.directory) { "A source changed type before deletion" }
                        if (!current.directory) {
                            val proof = requireNotNull(proofs[key]) { "A strong source deletion proof is missing" }
                            require(current.sizeBytes == entry.source.sizeBytes) { "A source changed before deletion" }
                            require(storage.sha256(location, operation) == proof) { "A source changed before deletion" }
                        }
                        storage.delete(location, recursive = false)
                    }
                    journal += AfReceiptItem(
                        source = location,
                        destination = null,
                        directory = entry.source.directory,
                        status = AfReceiptItemStatus.DELETED,
                        sizeBytes = entry.source.sizeBytes,
                        sha256 = proofs[key],
                    )
                    deleted += key
                    sinceCheckpoint += 1
                    operation.progress(itemDelta = 1, currentName = entry.source.name)
                    if (sinceCheckpoint >= CHECKPOINT_ITEMS) {
                        checkpoint(writer, state.copy(updatedAtMillis = System.currentTimeMillis()), journal)
                        sinceCheckpoint = 0
                    }
                }
        }
        if (sinceCheckpoint > 0) checkpoint(writer, state.copy(updatedAtMillis = System.currentTimeMillis()), journal)
    }

    fun buildReceipt(
        preflight: AfPreflightSummary,
        state: AfExecutionState,
        journal: List<AfReceiptItem>,
    ): AfOperationReceipt = AfOperationReceipt(
        id = preflight.executionId,
        planId = preflight.plan.id,
        planName = preflight.plan.name,
        startedAtMillis = state.startedAtMillis ?: preflight.createdAtMillis,
        finishedAtMillis = state.finishedAtMillis ?: System.currentTimeMillis(),
        status = state.status,
        verification = preflight.plan.verification,
        items = journal,
        sourceDeletionRequested = preflight.plan.deleteSourcesAfterVerifiedCopies,
        sourcesDeleted = state.sourcesDeleted,
        undoAvailable = !state.sourcesDeleted && state.failedItems == 0 && journal.any {
            it.status in setOf(AfReceiptItemStatus.COPIED, AfReceiptItemStatus.REPLACED)
        } && state.status == AfExecutionStatus.COMPLETED,
        errorCount = state.failedItems.coerceAtMost(AfWorkflowLimits.MAX_RECORDED_ERRORS),
        errors = state.errors.take(AfWorkflowLimits.MAX_RECORDED_ERRORS),
    )

    private fun checkpoint(writer: AfExecutionWriter, state: AfExecutionState, journal: List<AfReceiptItem>) {
        writer.saveJournal(state.executionId, journal)
        writer.saveState(state)
    }

    private suspend fun rollbackActiveReplacement(
        preflight: AfPreflightSummary,
        state: AfExecutionState,
        journal: MutableList<AfReceiptItem>,
        entriesBySource: Map<Int, List<AfPlannedEntry>>,
        storage: AfStorageSession,
    ): AfExecutionState {
        val backup = state.activeBackup ?: return state
        val projectionIndex = state.nextProjectionIndex
        val projection = preflight.projections.getOrNull(projectionIndex) ?: return state
        val completedJournalCount = preflight.projections.take(projectionIndex).sumOf { completed ->
            if (completed.disposition in NON_COPYING_DISPOSITIONS) 1
            else entriesBySource[completed.sourceRootIndex].orEmpty().size
        }.coerceAtMost(journal.size)
        val currentItems = journal.drop(completedJournalCount)
        try {
            currentItems.asReversed().forEach { item ->
                val destination = item.destination ?: return@forEach
                val current = storage.stat(destination) ?: return@forEach
                if (current.directory) {
                    storage.delete(destination, recursive = false)
                } else if (item.sha256 != null && current.sizeBytes == item.sizeBytes && storage.sha256(destination) == item.sha256) {
                    storage.delete(destination, recursive = false)
                } else if (item.status != AfReceiptItemStatus.FAILED) {
                    error("Replacement rollback stopped because a copied file changed")
                }
            }
            storage.stat(projection.targetRoot)?.let { remaining ->
                require(remaining.directory) { "Replacement rollback target changed type" }
                storage.delete(projection.targetRoot, recursive = false)
            }
            require(storage.stat(backup) != null) { "Replacement recovery backup is missing" }
            require(storage.stat(projection.targetRoot) == null) { "Replacement rollback target is not empty" }
            storage.rename(backup, projection.targetRoot)
        } catch (rollbackError: Throwable) {
            return state.copy(
                errors = (state.errors + "RECOVERY_REQUIRED:${backup.displayLabel}")
                    .take(AfWorkflowLimits.MAX_RECORDED_ERRORS),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        while (journal.size > completedJournalCount) journal.removeAt(journal.lastIndex)
        val completedProjections = preflight.projections.take(projectionIndex).filter {
            it.disposition !in NON_COPYING_DISPOSITIONS
        }
        val completedFiles = completedProjections.sumOf { completed ->
            entriesBySource[completed.sourceRootIndex].orEmpty().count { !it.source.directory }
        }
        val completedBytes = completedProjections.sumOf { completed ->
            entriesBySource[completed.sourceRootIndex].orEmpty().sumOf { entry ->
                if (entry.source.directory) 0L else entry.source.sizeBytes
            }
        }
        return state.copy(
            nextEntryIndex = 0,
            completedFiles = completedFiles,
            completedBytes = completedBytes,
            activeBackup = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun backupLocation(target: AfLocationRef, executionId: String): AfLocationRef {
        val name = when (target.kind) {
            AfLocationKind.LOCAL -> File(target.path).name
            AfLocationKind.REMOTE -> target.path.substringAfterLast('/')
        }.ifBlank { "root" }
        val backupName = ".${name.take(120)}.af-backup-${executionId.take(12)}"
        return child(parent(target), backupName)
    }

    private fun safeErrorCode(error: Throwable): String {
        val name = error::class.java.simpleName.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return name.ifBlank { "TRANSFER_FAILED" }.take(80)
    }

    private companion object {
        const val CHECKPOINT_ITEMS = 16
        val NON_COPYING_DISPOSITIONS = setOf(
            AfPreflightDisposition.VERIFIED_IDENTICAL,
            AfPreflightDisposition.POSSIBLY_IDENTICAL,
            AfPreflightDisposition.SKIP,
            AfPreflightDisposition.BLOCKED,
        )
        val NON_COPYING_RECEIPT_STATUSES = setOf(AfReceiptItemStatus.VERIFIED_IDENTICAL, AfReceiptItemStatus.SKIPPED)
        val COPY_SUCCESS_STATUSES = setOf(
            AfReceiptItemStatus.COPIED,
            AfReceiptItemStatus.REPLACED,
            AfReceiptItemStatus.VERIFIED_IDENTICAL,
        )
    }
}
