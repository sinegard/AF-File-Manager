package com.affilemanager.app.operations

import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DurableTransferCoordinator(
    private val operationManager: FileOperationManager,
    private val repository: DurableTransferRepository,
    private val planner: DurableTransferPlanner = DurableTransferPlanner(),
    private val engine: DurableTransferEngine = DurableTransferEngine(),
) {
    companion object {
        private const val MAX_AUTO_RESUME = 32
    }

    suspend fun createAndSubmit(
        sourcePaths: List<String>,
        destinationPath: String,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        verification: TransferVerification,
        failurePolicy: TransferFailurePolicy,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val plan = planner.create(
                sourcePaths = sourcePaths,
                destinationDirectoryPath = destinationPath,
                move = move,
                conflictPolicy = conflictPolicy,
                verification = verification,
                failurePolicy = if (move) TransferFailurePolicy.STOP else failurePolicy,
            )
            val state = repository.create(plan)
            submit(DurableTransferRecord(plan, state)).getOrThrow()
        }
    }

    fun restore() {
        val records = repository.list()
        operationManager.restore(records.map(::snapshot))
        records.filter { it.state.status in setOf(DurableTransferStatus.QUEUED, DurableTransferStatus.RUNNING, DurableTransferStatus.INTERRUPTED) }
            .take(MAX_AUTO_RESUME)
            .forEach { record ->
                val interrupted = if (record.state.status == DurableTransferStatus.RUNNING) {
                    engine.markInterrupted(record.state, "Procesas nutrūko; planas tikrinamas ir tęsiamas")
                        .also(repository::saveState)
                } else record.state
                submit(DurableTransferRecord(record.plan, interrupted))
            }
    }

    fun retry(id: String): Result<String> = runCatching {
        val record = repository.load(id)
        val retry = engine.prepareRetry(record.state)
        repository.saveState(retry)
        submit(DurableTransferRecord(record.plan, retry)).getOrThrow()
    }

    fun cancel(id: String) = operationManager.cancel(id)

    private fun submit(record: DurableTransferRecord): Result<String> {
        val plan = record.plan
        val queued = record.state.copy(
            status = DurableTransferStatus.QUEUED,
            updatedAtMillis = System.currentTimeMillis(),
        )
        repository.saveState(queued)
        return operationManager.submitExisting(
            id = plan.id,
            title = if (plan.move) "Patikimai perkeliama" else "Patikimai kopijuojama",
            retryable = true,
        ) {
            try {
                val latest = repository.load(plan.id).state
                engine.execute(plan, latest, repository, this)
            } catch (cancelled: CancellationException) {
                val latest = runCatching { repository.load(plan.id).state }.getOrDefault(queued)
                runCatching { engine.restoreBackupsAfterCopyCancellation(plan, latest) }
                repository.saveState(
                    latest.copy(
                        status = DurableTransferStatus.CANCELLED,
                        lastMessage = "Atšaukta naudotojo; užbaigtos kopijos gali likti paskirties vietoje",
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                throw cancelled
            } catch (error: Throwable) {
                val latest = runCatching { repository.load(plan.id).state }.getOrDefault(queued)
                if (latest.status != DurableTransferStatus.FAILED) {
                    repository.saveState(
                        latest.copy(
                            status = DurableTransferStatus.FAILED,
                            lastMessage = (error.message ?: error::class.java.simpleName).take(500),
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
                throw error
            }
        }
    }

    private fun snapshot(record: DurableTransferRecord): OperationSnapshot {
        val plan = record.plan
        val state = record.state
        val copiedItems = state.nextItemIndex.coerceIn(0, plan.items.size)
        val copiedBytes = plan.items.take(copiedItems).sumOf(PlannedTransferItem::sizeBytes)
        val deletedItems = if (state.phase in setOf(TransferPhase.FINALIZE_BACKUPS, TransferPhase.COMPLETE)) {
            if (plan.move) plan.items.size else 0
        } else 0
        val status = when (state.status) {
            DurableTransferStatus.QUEUED -> OperationStatus.QUEUED
            DurableTransferStatus.RUNNING -> OperationStatus.INTERRUPTED
            DurableTransferStatus.FAILED -> OperationStatus.FAILED
            DurableTransferStatus.COMPLETED -> OperationStatus.SUCCEEDED
            DurableTransferStatus.COMPLETED_WITH_ERRORS -> OperationStatus.COMPLETED_WITH_ERRORS
            DurableTransferStatus.CANCELLED -> OperationStatus.CANCELLED
            DurableTransferStatus.INTERRUPTED -> OperationStatus.INTERRUPTED
        }
        return OperationSnapshot(
            id = plan.id,
            title = if (plan.move) "Patikimai perkeliama" else "Patikimai kopijuojama",
            status = status,
            completedItems = copiedItems + deletedItems,
            totalItems = plan.items.size * if (plan.move) 2 else 1,
            completedBytes = copiedBytes,
            totalBytes = plan.totalBytes,
            message = state.lastMessage,
            startedAtMillis = plan.createdAtMillis,
            finishedAtMillis = state.updatedAtMillis.takeIf { status in setOf(
                OperationStatus.SUCCEEDED,
                OperationStatus.COMPLETED_WITH_ERRORS,
                OperationStatus.FAILED,
                OperationStatus.CANCELLED,
            ) },
            retryable = status in setOf(
                OperationStatus.COMPLETED_WITH_ERRORS,
                OperationStatus.FAILED,
                OperationStatus.CANCELLED,
                OperationStatus.INTERRUPTED,
            ),
            errorCount = state.failedItemIndices.size,
        )
    }
}
