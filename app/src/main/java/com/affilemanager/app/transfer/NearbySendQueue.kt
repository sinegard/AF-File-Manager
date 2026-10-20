package com.affilemanager.app.transfer

import java.util.UUID

internal data class NearbySendBatch(
    val id: String,
    val pairing: NearbyPairing,
    val sources: PreparedNearbyTransfer,
    val returnPairing: NearbyPairing?,
    val state: NearbyTransferState,
    val ready: Boolean = false,
    val announced: Boolean = false,
    val groupId: String? = null,
)

/** Process-local FIFO; only the foreground service owns payloads after admission. */
internal class NearbySendQueue(private val onChanged: (NearbyTransferState) -> Unit) {
    private val batches = linkedMapOf<String, NearbySendBatch>()
    private var activeId: String? = null

    @Synchronized fun enqueue(pairing: NearbyPairing, sources: PreparedNearbyTransfer, returnPairing: NearbyPairing?, groupId: String? = null): NearbySendBatch {
        require(sources.paths.size == sources.relativePaths.size &&
            sources.paths.size == sources.sourceUris.size) { "Netinkamas siunčiamų failų skaičius" }
        val pending = batches.values.filter { !it.state.status.finished() }
        require(pending.all { it.pairing == pairing || groupId != null && it.groupId == groupId }) { "Pirmiausia sustabdykite kitą bendrinimo sesiją" }
        require(pending.size < 16 && pending.sumOf { it.sources.paths.size } + sources.paths.size <= NearbySourcePreparer.MAX_FILES &&
            pending.sumOf { it.sources.directories.size } + sources.directories.size <= NearbySourcePreparer.MAX_DIRECTORIES &&
            pending.sumOf { it.sources.payloadCharacters() } + sources.payloadCharacters() <= NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS &&
            pending.sumOf { it.sources.totalBytes } + sources.totalBytes <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo eilė pilna" }
        // Completed history is finite; active and waiting rows are never evicted.
        if (pending.isEmpty() && batches.values.any { it.pairing != pairing }) batches.clear()
        while (batches.isNotEmpty() && (batches.size >= 16 || batches.values.sumOf { it.state.files.size } + sources.paths.size > NearbySourcePreparer.MAX_FILES)) {
            val oldest = batches.entries.firstOrNull { it.value.state.status.finished() } ?: break
            batches.remove(oldest.key)
        }
        val id = UUID.randomUUID().toString()
        val files = sources.relativePaths.mapIndexed { index, path -> TransferFileProgress(
            path,
            sources.fileSizes.getOrElse(index) { 0L },
            localPath = sources.paths[index].takeIf { sources.sourceUris[index] == null && it.isNotBlank() },
            batchId = id,
        ) }
        val batch = NearbySendBatch(id, pairing, sources, returnPairing,
            NearbyTransferState(NearbyTransferStatus.STARTING, pairing.receiverName, files.size,
                totalBytes = files.sumOf { it.sizeBytes }, message = "Eilėje", files = files), groupId = groupId)
        batches[id] = batch
        changed()
        return batch
    }

    @Synchronized fun get(id: String): NearbySendBatch? = batches[id]

    /** Claims the original row once; only that row's existing source may be retried. */
    @Synchronized fun claimRetry(id: String, fileIndex: Int) {
        val batch = requireNotNull(batches[id]) { "Select the source again" }
        val index = fileIndex - 1
        val file = requireNotNull(batch.state.files.getOrNull(index)) { "Select the source again" }
        require(file.status in setOf(TransferFileStatus.FAILED, TransferFileStatus.CANCELLED) && !file.retrySubmitted) { "File is not available for retry" }
        require(batch.sources.retainsRetrySource(index)) { "The temporary source has been removed. Select it again." }
        batches[id] = batch.copy(state = batch.state.copy(files = batch.state.files.toMutableList().apply {
            this[index] = file.copy(retrySubmitted = true)
        }))
        changed()
    }

    @Synchronized fun finishRetryClaim(id: String, fileIndex: Int, error: String? = null, received: Boolean = false) {
        val batch = batches[id] ?: return
        val index = fileIndex - 1
        val file = batch.state.files.getOrNull(index) ?: return
        val updated = when {
            received -> file.copy(status = TransferFileStatus.COMPLETED, transferredBytes = file.sizeBytes, failure = null, notAttempted = false)
            error != null -> file.copy(retrySubmitted = false)
            else -> file
        }
        val files = batch.state.files.toMutableList().apply { this[index] = updated }
        val reconciled = received && files.all { it.status == TransferFileStatus.COMPLETED }
        batches[id] = batch.copy(state = batch.state.copy(files = files, message = error ?: if (reconciled) "Baigta" else batch.state.message,
            status = if (reconciled) NearbyTransferStatus.COMPLETED else batch.state.status,
            completedFiles = files.count { it.status == TransferFileStatus.COMPLETED }, sentBytes = files.sumOf { it.transferredBytes }))
        changed()
    }
    @Synchronized fun enqueueGroup(peers: List<NearbyPairing>, sources: PreparedNearbyTransfer, returnPairing: NearbyPairing?): List<NearbySendBatch> {
        require(peers.size in 1..9 && peers.distinct().size == peers.size) { "Pasirinkite grupės gavėjus" }
        require(!hasPending()) { "Pirmiausia užbaikite vykstantį siuntimą" }
        val groupId = UUID.randomUUID().toString()
        val added = mutableListOf<NearbySendBatch>()
        try {
            peers.forEach { added += enqueue(it, sources, returnPairing, groupId) }
            return added
        } catch (failure: Exception) {
            added.forEach { batches.remove(it.id) }
            changed()
            throw failure
        }
    }

    /** A shared staged payload is removed only after its final admitted recipient is done. */
    @Synchronized fun stageInUse(path: String, excludingId: String? = null): Boolean = batches.values.any {
        it.id != excludingId && it.sources.cleanupRootPath == path && !it.state.status.finished()
    }
    @Synchronized fun nextToPrepare(): NearbySendBatch? = batches.values.firstOrNull { !it.ready && !it.state.status.finished() }

    @Synchronized fun ready(id: String, files: List<TransferFileProgress>, announced: Boolean) {
        val batch = batches[id] ?: return
        if (batch.state.status.finished()) return
        val otherBytes = batches.values.filter { it.id != id && !it.state.status.finished() }.sumOf { it.state.totalBytes }
        require(otherBytes + files.sumOf { it.sizeBytes } <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo eilė pilna" }
        val prior = batch.state.files
        val merged = files.mapIndexed { index, file ->
            if (prior.getOrNull(index)?.status == TransferFileStatus.CANCELLED) {
                file.copy(status = TransferFileStatus.CANCELLED, batchId = id)
            } else file.copy(batchId = id, retrySubmitted = file.retrySubmitted || prior.getOrNull(index)?.retrySubmitted == true)
        }
        batches[id] = batch.copy(ready = true, announced = announced, state = batch.state.copy(
            totalBytes = merged.sumOf { it.sizeBytes }, files = merged))
        changed()
    }

    @Synchronized fun takeNext(): NearbySendBatch? {
        if (activeId != null) return null
        val next = batches.values.firstOrNull { !it.state.status.finished() } ?: return null
        if (!next.ready) return null
        activeId = next.id
        return next
    }

    @Synchronized fun update(id: String, state: NearbyTransferState) {
        val batch = batches[id] ?: return
        if (batch.state.status.finished() && !state.status.finished()) return
        val previous = batch.state.files
        val merged = state.files.mapIndexed { index, file ->
            val prior = previous.getOrNull(index)
            if (prior?.status == TransferFileStatus.CANCELLED && file.status !in setOf(
                    TransferFileStatus.CANCELLED,
                    TransferFileStatus.COMPLETED,
                )
            ) {
                prior.copy(batchId = id)
            } else file.copy(batchId = id, retrySubmitted = file.retrySubmitted || prior?.retrySubmitted == true)
        }
        batches[id] = batch.copy(state = state.copy(files = merged))
        if (state.status.finished() && activeId == id) activeId = null
        changed()
    }

    /** Marks one row only. The service still owns batch cleanup and terminal state. */
    @Synchronized fun cancelFile(id: String, fileIndex: Int): Boolean {
        val batch = batches[id] ?: return false
        if (batch.state.status.finished()) return false
        val index = fileIndex - 1
        val current = batch.state.files.getOrNull(index) ?: return false
        if (current.status !in setOf(TransferFileStatus.WAITING, TransferFileStatus.TRANSFERRING)) return false
        val files = batch.state.files.toMutableList().apply {
            this[index] = current.copy(status = TransferFileStatus.CANCELLED)
        }
        batches[id] = batch.copy(state = batch.state.copy(
            files = files,
            sentBytes = files.sumOf(TransferFileProgress::transferredBytes),
            completedFiles = files.count { it.status == TransferFileStatus.COMPLETED },
        ))
        changed()
        return true
    }

    @Synchronized fun cancel(ownedIds: Set<String>? = null): List<NearbySendBatch> {
        val cancelled = batches.values.filter { !it.state.status.finished() && (ownedIds == null || it.id in ownedIds) }
        cancelled.forEach { batch -> batches[batch.id] = batch.copy(state = batch.state.copy(status = NearbyTransferStatus.CANCELLED,
            message = "Siuntimas atšauktas", files = batch.state.files.map {
                it.stopPending(TransferFileStatus.CANCELLED).let { stopped ->
                    if (batch.sources.cleanupRootPath != null && stopped.status != TransferFileStatus.COMPLETED) stopped.copy(localPath = null) else stopped
                }
            })) }
        if (cancelled.any { it.id == activeId }) activeId = null
        changed()
        return cancelled
    }

    @Synchronized fun rollback(id: String) { batches.remove(id); changed() }
    @Synchronized fun hasPending(): Boolean = batches.values.any { !it.state.status.finished() }
    @Synchronized fun clearFinished() { if (!hasPending()) { batches.clear(); activeId = null; changed() } }

    private fun changed() {
        if (batches.isEmpty()) { onChanged(NearbyTransferState()); return }
        val states = batches.values.map { it.state }
        val active = states.firstOrNull { it.status == NearbyTransferStatus.RUNNING }
            ?: states.firstOrNull { it.status == NearbyTransferStatus.STARTING } ?: states.last()
        val files = states.flatMap { it.files }
        val totalBytes = files.sumOf { it.sizeBytes }
        val sentBytes = files.sumOf { it.transferredBytes }
        val status = when {
            states.any { it.status == NearbyTransferStatus.RUNNING } -> NearbyTransferStatus.RUNNING
            states.any { it.status == NearbyTransferStatus.STARTING } -> NearbyTransferStatus.STARTING
            states.any { it.status == NearbyTransferStatus.ERROR } -> NearbyTransferStatus.ERROR
            states.any { it.status == NearbyTransferStatus.CANCELLED } -> NearbyTransferStatus.CANCELLED
            else -> NearbyTransferStatus.COMPLETED
        }
        onChanged(active.copy(status = status, files = files, fileCount = files.size,
            completedFiles = files.count { it.status == TransferFileStatus.COMPLETED },
            totalBytes = totalBytes,
            sentBytes = sentBytes,
            remainingMillis = if (status == NearbyTransferStatus.COMPLETED) 0L else
                TransferProgressEstimator.remainingMillis(
                    (totalBytes - sentBytes).coerceAtLeast(0L),
                    active.bytesPerSecond,
                ),
        ))
    }
}

internal fun NearbyTransferStatus.finished() = this in setOf(NearbyTransferStatus.COMPLETED, NearbyTransferStatus.CANCELLED, NearbyTransferStatus.ERROR)
private fun PreparedNearbyTransfer.payloadCharacters() = paths.sumOf { it.length } + sourceUris.filterNotNull().sumOf { it.length } +
    relativePaths.sumOf { it.length } + directories.sumOf { it.length }

/** A mixed batch may contain original files/URIs alongside already-discarded staging copies. */
internal fun PreparedNearbyTransfer.retainsRetrySource(index: Int): Boolean {
    val path = paths.getOrNull(index) ?: return false
    if (sourceUris.getOrNull(index) != null) return true
    if (path.isBlank()) return false
    val stage = cleanupRootPath ?: return true
    return runCatching {
        !java.io.File(path).canonicalFile.toPath().startsWith(java.io.File(stage).canonicalFile.toPath())
    }.getOrDefault(false)
}
