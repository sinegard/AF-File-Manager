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
)

/** Process-local FIFO; only the foreground service owns payloads after admission. */
internal class NearbySendQueue(private val onChanged: (NearbyTransferState) -> Unit) {
    private val batches = linkedMapOf<String, NearbySendBatch>()
    private var activeId: String? = null

    @Synchronized fun enqueue(pairing: NearbyPairing, sources: PreparedNearbyTransfer, returnPairing: NearbyPairing?): NearbySendBatch {
        require(sources.paths.size == sources.relativePaths.size &&
            (sources.paths.isNotEmpty() || sources.directories.isNotEmpty())) { "Netinkamas siunčiamų failų skaičius" }
        val pending = batches.values.filter { !it.state.status.finished() }
        require(pending.all { it.pairing == pairing }) { "Pirmiausia sustabdykite kitą bendrinimo sesiją" }
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
        val files = sources.relativePaths.mapIndexed { index, path -> TransferFileProgress(path,
            sources.fileSizes.getOrElse(index) { 0L }, localPath = sources.paths[index], batchId = id) }
        val batch = NearbySendBatch(id, pairing, sources, returnPairing,
            NearbyTransferState(NearbyTransferStatus.STARTING, pairing.receiverName, files.size,
                totalBytes = files.sumOf { it.sizeBytes }, message = "Eilėje", files = files))
        batches[id] = batch
        changed()
        return batch
    }

    @Synchronized fun get(id: String): NearbySendBatch? = batches[id]
    @Synchronized fun nextToPrepare(): NearbySendBatch? = batches.values.firstOrNull { !it.ready && !it.state.status.finished() }

    @Synchronized fun ready(id: String, files: List<TransferFileProgress>, announced: Boolean) {
        val batch = batches[id] ?: return
        if (batch.state.status.finished()) return
        val otherBytes = batches.values.filter { it.id != id && !it.state.status.finished() }.sumOf { it.state.totalBytes }
        require(otherBytes + files.sumOf { it.sizeBytes } <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo eilė pilna" }
        batches[id] = batch.copy(ready = true, announced = announced, state = batch.state.copy(
            totalBytes = files.sumOf { it.sizeBytes }, files = files.map { it.copy(batchId = id) }))
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
        batches[id] = batch.copy(state = state.copy(files = state.files.map { it.copy(batchId = id) }))
        if (state.status.finished() && activeId == id) activeId = null
        changed()
    }

    @Synchronized fun cancel(ownedIds: Set<String>? = null): List<NearbySendBatch> {
        val cancelled = batches.values.filter { !it.state.status.finished() && (ownedIds == null || it.id in ownedIds) }
        cancelled.forEach { batch -> batches[batch.id] = batch.copy(state = batch.state.copy(status = NearbyTransferStatus.CANCELLED,
            message = "Siuntimas atšauktas", files = batch.state.files.map { if (it.status == TransferFileStatus.COMPLETED) it
                else it.copy(status = TransferFileStatus.CANCELLED, localPath = if (batch.sources.cleanupRootPath != null) null else it.localPath) })) }
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
        val status = when {
            states.any { it.status == NearbyTransferStatus.RUNNING } -> NearbyTransferStatus.RUNNING
            states.any { it.status == NearbyTransferStatus.STARTING } -> NearbyTransferStatus.STARTING
            states.any { it.status == NearbyTransferStatus.ERROR } -> NearbyTransferStatus.ERROR
            states.any { it.status == NearbyTransferStatus.CANCELLED } -> NearbyTransferStatus.CANCELLED
            else -> NearbyTransferStatus.COMPLETED
        }
        onChanged(active.copy(status = status, files = files, fileCount = files.size,
            completedFiles = files.count { it.status == TransferFileStatus.COMPLETED },
            totalBytes = files.sumOf { it.sizeBytes }, sentBytes = files.sumOf { it.transferredBytes }))
    }
}

internal fun NearbyTransferStatus.finished() = this in setOf(NearbyTransferStatus.COMPLETED, NearbyTransferStatus.CANCELLED, NearbyTransferStatus.ERROR)
private fun PreparedNearbyTransfer.payloadCharacters() = paths.sumOf { it.length } + relativePaths.sumOf { it.length } + directories.sumOf { it.length }
