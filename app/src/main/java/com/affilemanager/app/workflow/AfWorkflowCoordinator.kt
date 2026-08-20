package com.affilemanager.app.workflow

import com.affilemanager.app.operations.FileOperationManager
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AfWorkflowSnapshot(
    val plans: List<AfPlanDefinition> = emptyList(),
    val timeline: List<AfOperationReceipt> = emptyList(),
    val automations: List<AfAutomationRule> = emptyList(),
)

class AfWorkflowCoordinator(
    private val scope: CoroutineScope,
    private val operations: FileOperationManager,
    private val planRepository: AfPlanRepository,
    private val executionRepository: AfExecutionRepository,
    private val timelineRepository: AfTimelineRepository,
    private val automationRepository: AfAutomationRepository,
    private val automationScheduler: AfAutomationScheduler,
    private val storageFactory: AfStorageSessionFactory,
    stagingDirectory: File,
) {
    private val preflightEngine = AfPlanPreflight()
    private val executionEngine = AfPlanEngine(stagingDirectory)
    private val undoEngine = AfTimelineUndoEngine()
    private val automationMutex = Mutex()
    private val writer = object : AfExecutionWriter {
        override fun saveState(state: AfExecutionState) = executionRepository.save(state)
        override fun saveJournal(executionId: String, items: List<AfReceiptItem>) =
            executionRepository.saveJournal(executionId, items)
    }
    private val _snapshot = MutableStateFlow(AfWorkflowSnapshot())
    val snapshot: StateFlow<AfWorkflowSnapshot> = _snapshot.asStateFlow()

    suspend fun refresh() {
        _snapshot.value = AfWorkflowSnapshot(
            plans = planRepository.list(),
            timeline = timelineRepository.list(),
            automations = automationRepository.list(),
        )
    }

    suspend fun restore() {
        refresh()
        automationScheduler.restore(_snapshot.value.automations)
        executionRepository.listRecoverable().forEach { record ->
            val interrupted = record.state.copy(
                status = AfExecutionStatus.INTERRUPTED,
                updatedAtMillis = System.currentTimeMillis(),
            )
            executionRepository.save(interrupted)
            submitRecord(record.copy(state = interrupted))
        }
    }

    suspend fun savePlan(plan: AfPlanDefinition): AfPlanDefinition = planRepository.save(plan).also { refresh() }

    suspend fun removePlan(id: String): Boolean = planRepository.remove(id).also { removed ->
        if (removed) {
            automationRepository.list().filter { it.planId == id }.forEach { automationRepository.remove(it.id) }
            refresh()
        }
    }

    suspend fun preview(plan: AfPlanDefinition): AfPreflightSummary {
        val storage = storageFactory.open()
        return try {
            preflightEngine.preview(plan, storage)
        } finally {
            storage.close()
        }
    }

    suspend fun submit(preflight: AfPreflightSummary): String {
        val state = executionRepository.create(preflight).copy(status = AfExecutionStatus.QUEUED)
        executionRepository.save(state)
        val record = executionRepository.load(preflight.executionId)
        return submitRecord(record).getOrThrow()
    }

    suspend fun retryPlan(planId: String): String {
        val plan = planRepository.find(planId) ?: throw IllegalArgumentException("AF Plan is no longer available")
        val fresh = preview(plan)
        require(fresh.canRun) { fresh.blockers.firstOrNull() ?: "AF Plan preview contains blockers" }
        return submit(fresh)
    }

    suspend fun retryExecution(executionId: String): String {
        val record = executionRepository.load(executionId)
        if (record.state.sourceDeletionPrepared && !record.state.sourcesDeleted) {
            val resumable = record.state.copy(
                status = AfExecutionStatus.INTERRUPTED,
                failedItems = 0,
                errors = emptyList(),
                finishedAtMillis = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
            executionRepository.save(resumable)
            return submitRecord(record.copy(state = resumable)).getOrThrow()
        }
        val prepared = AfExecutionRetry.prepare(record)
        executionRepository.saveJournal(executionId, prepared.journal)
        executionRepository.save(prepared.state)
        return submitRecord(prepared).getOrThrow()
    }

    suspend fun saveAutomation(rule: AfAutomationRule): AfAutomationRule = automationRepository.save(rule).also { saved ->
        automationScheduler.synchronize(saved)
        refresh()
    }

    suspend fun removeAutomation(id: String): Boolean = automationRepository.remove(id).also { removed ->
        if (removed) {
            automationScheduler.cancel(id)
            refresh()
        }
    }

    suspend fun approveAutomationPreview(ruleId: String, preview: AfPreflightSummary): AfAutomationRule {
        val rule = automationRepository.list().firstOrNull { it.id == ruleId }
            ?: throw IllegalArgumentException("Automation rule is unavailable")
        require(rule.planId == preview.plan.id && preview.canRun) { "Preview does not match this automation rule" }
        val approved = rule.copy(
            lastPreviewFingerprint = AfPreflightFingerprint.create(preview),
            lastPreviewAtMillis = System.currentTimeMillis(),
            lastStatus = "Preview approved",
        )
        return saveAutomation(approved)
    }

    suspend fun runApprovedAutomation(ruleId: String) = automationMutex.withLock {
        val rule = automationRepository.list().firstOrNull { it.id == ruleId }
            ?: return@withLock
        if (!rule.enabled || rule.schedule == AfAutomationSchedule.MANUAL_ONLY) return@withLock
        val plan = planRepository.find(rule.planId) ?: run {
            automationRepository.save(rule.copy(lastStatus = "AF Plan is unavailable", lastRunAtMillis = System.currentTimeMillis()))
            refresh()
            return@withLock
        }
        // Scheduled changes always use a strong source fingerprint even when the
        // interactive version of the saved plan uses the faster size check.
        val preflight = preview(plan.copy(verification = TransferVerification.SHA256))
        AfAutomationPolicy.runBlocker(rule, preflight)?.let { status ->
            automationRepository.save(rule.copy(lastStatus = status, lastRunAtMillis = System.currentTimeMillis()))
            refresh()
            return@withLock
        }

        val initial = executionRepository.create(preflight).copy(status = AfExecutionStatus.RUNNING)
        executionRepository.save(initial)
        val storage = storageFactory.open()
        try {
            val (_, receipt) = executionEngine.execute(
                preflight = preflight,
                initialState = initial,
                initialJournal = emptyList(),
                storage = storage,
                writer = writer,
                operation = com.affilemanager.app.operations.OperationContext.background(),
            )
            timelineRepository.add(receipt)
            automationRepository.save(
                rule.copy(
                    lastRunAtMillis = System.currentTimeMillis(),
                    lastStatus = if (receipt.errorCount == 0) "Completed" else "Completed with ${receipt.errorCount} errors",
                ),
            )
            refresh()
        } catch (error: Throwable) {
            runCatching {
                val failed = executionRepository.load(preflight.executionId)
                timelineRepository.add(executionEngine.buildReceipt(failed.preflight, failed.state, failed.journal))
            }
            automationRepository.save(
                rule.copy(lastRunAtMillis = System.currentTimeMillis(), lastStatus = "Failed: ${safeAutomationCode(error)}"),
            )
            refresh()
            throw error
        } finally {
            storage.close()
        }
    }

    suspend fun previewUndo(receiptId: String): AfUndoPreview {
        val receipt = timelineRepository.get(receiptId) ?: throw IllegalArgumentException("Operation receipt is unavailable")
        val storage = storageFactory.open()
        return try {
            undoEngine.preview(receipt, storage)
        } finally {
            storage.close()
        }
    }

    fun submitUndo(receiptId: String, preview: AfUndoPreview): Result<String> {
        val receipt = timelineRepository.get(receiptId)
            ?: return Result.failure(IllegalArgumentException("Operation receipt is unavailable"))
        return operations.submit("Undo: ${receipt.planName}") {
            val storage = storageFactory.open()
            try {
                val fresh = undoEngine.preview(receipt, storage)
                require(fresh == preview && fresh.canRun) { "Files changed after the undo preview" }
                val undoReceipt = undoEngine.execute(receipt, fresh, storage, this)
                timelineRepository.add(undoReceipt)
                check(timelineRepository.markUndoConsumed(receipt.id)) { "Original operation receipt is unavailable" }
                refresh()
            } finally {
                storage.close()
            }
        }
    }

    fun findInTimeline(query: String): List<AfOperationReceipt> = timelineRepository.findPath(query)
    fun exportReceiptJson(receiptId: String): String = timelineRepository.get(receiptId)?.let(timelineRepository::exportJson)
        ?: throw IllegalArgumentException("Operation receipt is unavailable")
    fun exportReceiptText(receiptId: String): String = timelineRepository.get(receiptId)?.let(timelineRepository::exportText)
        ?: throw IllegalArgumentException("Operation receipt is unavailable")

    private fun submitRecord(record: AfExecutionRecord): Result<String> = operations.submitExisting(
        id = record.preflight.executionId,
        title = "AF Plan: ${record.preflight.plan.name}",
        retryable = true,
    ) {
        val storage = storageFactory.open()
        try {
            val latest = executionRepository.load(record.preflight.executionId)
            val (state, receipt) = executionEngine.execute(
                preflight = latest.preflight,
                initialState = latest.state,
                initialJournal = latest.journal,
                storage = storage,
                writer = writer,
                operation = this,
            )
            require(state.status in setOf(AfExecutionStatus.COMPLETED, AfExecutionStatus.COMPLETED_WITH_ERRORS)) {
                "AF Plan did not reach a terminal state"
            }
            timelineRepository.add(receipt)
            refresh()
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    val failed = executionRepository.load(record.preflight.executionId)
                    val state = if (failed.state.status in setOf(AfExecutionStatus.FAILED, AfExecutionStatus.CANCELLED)) {
                        failed.state
                    } else {
                        failed.state.copy(
                            status = AfExecutionStatus.FAILED,
                            finishedAtMillis = System.currentTimeMillis(),
                            updatedAtMillis = System.currentTimeMillis(),
                        ).also(executionRepository::save)
                    }
                    timelineRepository.add(executionEngine.buildReceipt(failed.preflight, state, failed.journal))
                    refresh()
                }
            }
            throw error
        } finally {
            storage.close()
        }
    }

    private fun safeAutomationCode(error: Throwable): String = error::class.java.simpleName
        .uppercase()
        .replace(Regex("[^A-Z0-9]+"), "_")
        .trim('_')
        .ifBlank { "AUTOMATION_FAILED" }
        .take(80)
}

internal object AfExecutionRetry {
    private val RETRYABLE = setOf(
        AfExecutionStatus.FAILED,
        AfExecutionStatus.COMPLETED_WITH_ERRORS,
        AfExecutionStatus.CANCELLED,
        AfExecutionStatus.INTERRUPTED,
    )

    fun prepare(record: AfExecutionRecord): AfExecutionRecord {
        require(record.state.status in RETRYABLE) { "This AF execution cannot be retried" }
        val reachedEnd = record.state.nextProjectionIndex >= record.preflight.projections.size
        val state = record.state.copy(
            status = AfExecutionStatus.INTERRUPTED,
            nextProjectionIndex = if (reachedEnd) 0 else record.state.nextProjectionIndex,
            nextEntryIndex = if (reachedEnd) 0 else record.state.nextEntryIndex,
            failedItems = 0,
            errors = emptyList(),
            finishedAtMillis = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
        return record.copy(
            state = state,
            journal = record.journal.filterNot { it.status == AfReceiptItemStatus.FAILED },
        )
    }
}
