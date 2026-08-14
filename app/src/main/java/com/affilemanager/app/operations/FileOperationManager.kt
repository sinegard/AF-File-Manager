package com.affilemanager.app.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class OperationStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

data class OperationSnapshot(
    val id: String,
    val title: String,
    val status: OperationStatus,
    val completedItems: Int = 0,
    val totalItems: Int? = null,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    val currentName: String? = null,
    val message: String? = null,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val retryable: Boolean = false,
    val errorCount: Int = 0,
)

class OperationContext internal constructor(
    private val id: String,
    private val paused: MutableStateFlow<Boolean>,
    private val publish: (OperationSnapshot.() -> OperationSnapshot) -> Unit,
) {
    internal var completionStatus: OperationStatus = OperationStatus.SUCCEEDED
        private set

    companion object {
        fun background(): OperationContext = OperationContext("background", MutableStateFlow(false)) { }
    }

    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (paused.value) {
            publish { copy(status = OperationStatus.PAUSED) }
            paused.first { value -> !value }
            publish { copy(status = OperationStatus.RUNNING) }
        }
        currentCoroutineContext().ensureActive()
    }

    fun setTotals(items: Int?, bytes: Long?) {
        publish { copy(totalItems = items, totalBytes = bytes) }
    }

    fun progress(itemDelta: Int = 0, byteDelta: Long = 0, currentName: String? = null) {
        publish {
            copy(
                completedItems = completedItems + itemDelta,
                completedBytes = completedBytes + byteDelta,
                currentName = currentName ?: this.currentName,
            )
        }
    }

    fun note(message: String) {
        publish { copy(message = message) }
    }

    fun completeWithErrors(errorCount: Int, message: String) {
        require(errorCount > 0) { "Klaidų skaičius turi būti teigiamas" }
        completionStatus = OperationStatus.COMPLETED_WITH_ERRORS
        publish { copy(errorCount = errorCount, message = message, retryable = true) }
    }

    fun setRetryable(value: Boolean) {
        publish { copy(retryable = value) }
    }
}

class FileOperationManager(private val scope: CoroutineScope) {
    companion object {
        private const val MAX_QUEUED_OPERATIONS = 32
        private const val MAX_VISIBLE_HISTORY = 64
    }

    private data class Request(
        val id: String,
        val title: String,
        val block: suspend OperationContext.() -> Unit,
    )

    private val queue = Channel<Request>(capacity = MAX_QUEUED_OPERATIONS)
    private val pauseSignals = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val cancelledBeforeStart = ConcurrentHashMap.newKeySet<String>()
    private val _operations = MutableStateFlow<List<OperationSnapshot>>(emptyList())
    val operations: StateFlow<List<OperationSnapshot>> = _operations.asStateFlow()

    init {
        scope.launch {
            for (request in queue) {
                if (cancelledBeforeStart.remove(request.id)) continue
                val paused = pauseSignals.getOrPut(request.id) { MutableStateFlow(false) }
                update(request.id) {
                    copy(status = OperationStatus.RUNNING, startedAtMillis = System.currentTimeMillis())
                }
                val operationJob = launch(start = CoroutineStart.LAZY) {
                    try {
                        val context = OperationContext(request.id, paused) { transform -> update(request.id, transform) }
                        request.block(context)
                        update(request.id) {
                            copy(
                                status = context.completionStatus,
                                currentName = null,
                                finishedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    } catch (_: CancellationException) {
                        update(request.id) {
                            copy(status = OperationStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis())
                        }
                    } catch (error: Throwable) {
                        update(request.id) {
                            copy(
                                status = OperationStatus.FAILED,
                                message = error.message ?: error::class.java.simpleName,
                                finishedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    } finally {
                        runningJobs.remove(request.id)
                        pauseSignals.remove(request.id)
                    }
                }
                runningJobs[request.id] = operationJob
                operationJob.start()
                operationJob.join()
            }
        }
    }

    fun submit(title: String, block: suspend OperationContext.() -> Unit): Result<String> {
        val id = UUID.randomUUID().toString()
        return submitExisting(id, title, retryable = false, block = block)
    }

    fun submitExisting(
        id: String,
        title: String,
        retryable: Boolean,
        block: suspend OperationContext.() -> Unit,
    ): Result<String> {
        require(id.isNotBlank() && id.length <= 80) { "Netinkama operacijos tapatybė" }
        if (runningJobs.containsKey(id)) return Result.failure(IllegalStateException("Operacija jau vykdoma"))
        val snapshot = OperationSnapshot(id = id, title = title, status = OperationStatus.QUEUED, retryable = retryable)
        _operations.update { current ->
            (listOf(snapshot) + current.filterNot { it.id == id }).take(MAX_VISIBLE_HISTORY)
        }
        val result = queue.trySend(Request(id, title, block))
        if (result.isFailure) {
            update(id) { copy(status = OperationStatus.FAILED, message = "Operacijų eilė pilna") }
            return Result.failure(IllegalStateException("Operacijų eilė pilna"))
        }
        return Result.success(id)
    }

    fun restore(snapshots: List<OperationSnapshot>) {
        require(snapshots.size <= MAX_VISIBLE_HISTORY) { "Operacijų istorijos riba viršyta" }
        _operations.update { current ->
            (snapshots + current.filterNot { existing -> snapshots.any { it.id == existing.id } })
                .distinctBy(OperationSnapshot::id)
                .take(MAX_VISIBLE_HISTORY)
        }
    }

    fun pause(id: String) {
        pauseSignals[id]?.value = true
    }

    fun resume(id: String) {
        pauseSignals[id]?.value = false
    }

    fun cancel(id: String) {
        val job = runningJobs[id]
        if (job != null) {
            job.cancel()
        } else {
            cancelledBeforeStart.add(id)
            update(id) { copy(status = OperationStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis()) }
        }
    }

    fun dismissFinished() {
        _operations.update { snapshots ->
            snapshots.filter { it.status == OperationStatus.RUNNING || it.status == OperationStatus.PAUSED || it.status == OperationStatus.QUEUED }
        }
    }

    private fun update(id: String, transform: OperationSnapshot.() -> OperationSnapshot) {
        _operations.update { snapshots -> snapshots.map { if (it.id == id) it.transform() else it } }
    }
}
