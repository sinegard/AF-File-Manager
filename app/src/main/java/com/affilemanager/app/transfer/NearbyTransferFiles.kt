package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import org.json.JSONArray
import org.json.JSONObject

enum class TransferFileStatus { WAITING, TRANSFERRING, COMPLETED, FAILED, CANCELLED }

/** Session-only metadata. A receiver sets localPath only after atomic publication. */
data class TransferFileProgress(
    val relativePath: String,
    val sizeBytes: Long,
    val transferredBytes: Long = 0,
    val status: TransferFileStatus = TransferFileStatus.WAITING,
    val localPath: String? = null,
    val modifiedAtMillis: Long = 0,
    val batchId: String = "",
    val failure: TransferFailure? = null,
    /** Session-local guard. It is deliberately absent from the wire manifest. */
    val retrySubmitted: Boolean = false,
    val notAttempted: Boolean = false,
    /** Receiver-local name after keep-both publication, absent from the wire manifest. */
    val savedName: String? = null,
) {
    val name: String get() = relativePath.substringAfterLast('/')
}

/** Optional, versioned metadata; never sends source absolute paths or thumbnails. */
internal object NearbyTransferManifest {
    const val MAX_BYTES = 8 * 1_048_576

    fun encode(files: List<TransferFileProgress>): ByteArray {
        require(files.size <= NearbySourcePreparer.MAX_FILES) { "Netinkamas siunčiamų failų skaičius" }
        NearbyTransferMemory.requireAvailable(files.size, files.sumOf { it.relativePath.length })
        val rows = JSONArray()
        files.forEach { rows.put(JSONObject().put("path", it.relativePath).put("size", it.sizeBytes)) }
        val bytes = JSONObject().put("version", 1).put("files", rows).toString().toByteArray(Charsets.UTF_8)
        decode(bytes) // Apply identical sender and receiver admission rules.
        return bytes
    }

    fun decode(bytes: ByteArray): List<TransferFileProgress> {
        require(bytes.size in 1..MAX_BYTES) { "Siuntimo rinkinio kelių aprašas per didelis" }
        requireShallowJson(bytes)
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (failure: Exception) { throw IllegalArgumentException("Netinkamas santykinis siuntimo kelias", failure) }
        require(json.optInt("version") == 1) { "Netinkamas santykinis siuntimo kelias" }
        val rows = json.optJSONArray("files") ?: throw IllegalArgumentException("Netinkamas siunčiamų failų skaičius")
        require(rows.length() <= NearbySourcePreparer.MAX_FILES) { "Netinkamas siunčiamų failų skaičius" }
        NearbyTransferMemory.requireAvailable(rows.length(), bytes.size.coerceAtMost(NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS))
        var total = 0L
        var pathChars = 0
        return List(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: throw IllegalArgumentException("Netinkamas santykinis siuntimo kelias")
            val path = row.opt("path") as? String ?: throw IllegalArgumentException("Netinkamas santykinis siuntimo kelias")
            require(path.length in 1..4_096 && '\\' !in path) { "Netinkamas santykinis siuntimo kelias" }
            val parts = path.split('/')
            require(parts.size <= 65 && parts.none(String::isBlank)) { "Netinkamas santykinis siuntimo kelias" }
            parts.forEach { FileSystemRules.validateFileName(it).getOrThrow() }
            pathChars += path.length
            require(pathChars <= NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS) { "Siuntimo rinkinio kelių aprašas per didelis" }
            val size = (row.opt("size") as? Number)?.toString()?.toLongOrNull()
            require(size != null && size in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija saugyklos ribą" }
            total = Math.addExact(total, size)
            require(total <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 60 GB ribą" }
            TransferFileProgress(path, size)
        }
    }

    private fun requireShallowJson(bytes: ByteArray) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 4) { "Siuntimo rinkinio kelių aprašas per didelis" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Netinkamas santykinis siuntimo kelias" } }
            }
        }
        require(depth == 0 && !quoted) { "Netinkamas santykinis siuntimo kelias" }
    }
}

/** Receiver-bounded payloads within one active batch, plus separately identified pending batches. */
internal class NearbyReceiveFiles(private val maximumConcurrentUploads: Int = 1) {
    init { require(maximumConcurrentUploads in 1..5) }
    private class Batch(val files: MutableList<TransferFileProgress>) {
        var pending = files.count { !it.status.isTerminal() }
        var active = files.count { it.status == TransferFileStatus.TRANSFERRING }
        var receivedBytes = files.sumOf { it.transferredBytes }
        var totalBytes = files.sumOf { it.sizeBytes }
        val pathCharacters = files.sumOf { it.relativePath.length }
        fun replace(index: Int, next: TransferFileProgress) {
            val previous = files[index]
            pending += (if (next.status.isTerminal()) 0 else 1) - (if (previous.status.isTerminal()) 0 else 1)
            active += (if (next.status == TransferFileStatus.TRANSFERRING) 1 else 0) -
                (if (previous.status == TransferFileStatus.TRANSFERRING) 1 else 0)
            receivedBytes += next.transferredBytes - previous.transferredBytes
            totalBytes += next.sizeBytes - previous.sizeBytes
            files[index] = next
        }
    }
    data class Totals(val receivedBytes: Long, val totalBytes: Long, val finished: Boolean, val files: Int)
    private val batches = linkedMapOf<String?, Batch>()
    private val seenBatches = mutableSetOf<String>()
    @Synchronized fun hasManifest(): Boolean = batches.isNotEmpty()

    // Rows are immutable. Copy only list references when publishing, not every row per file.
    @Synchronized fun snapshot(): List<TransferFileProgress> = batches.values.flatMap { it.files }
    @Synchronized fun totals(): Totals = Totals(batches.values.sumOf { it.receivedBytes },
        batches.values.sumOf { it.totalBytes }, batches.values.all { it.pending == 0 }, batches.values.sumOf { it.files.size })

    @Synchronized fun announce(files: List<TransferFileProgress>, id: String? = null): List<TransferFileProgress> {
        if (id != null) require(id.length == 36 && runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Gavimo sesija nepatvirtinta" }
        batches[id]?.let { current ->
            // An HTTP retry after a lost acknowledgement must not reset progress.
            require(current.files.size == files.size && current.files.indices.all {
                current.files[it].relativePath == files[it].relativePath && current.files[it].sizeBytes == files[it].sizeBytes
            }) {
                "Gavimo sesija nepatvirtinta"
            }
            return snapshot()
        }
        require(id == null || (id !in seenBatches && seenBatches.size < 128)) { "Gavimo sesija nepatvirtinta" }
        require(files.size <= NearbySourcePreparer.MAX_FILES && files.sumOf { it.sizeBytes } <= NearbySourcePreparer.MAX_TOTAL_BYTES)
        val pathCharacters = files.sumOf { it.relativePath.length }
        // Drop only finished history when a new explicit batch needs the finite metadata budget.
        while (batches.isNotEmpty() && (batches.size >= 16 || batches.values.sumOf { it.files.size } + files.size > NearbySourcePreparer.MAX_FILES ||
                batches.values.sumOf { it.totalBytes } + files.sumOf { it.sizeBytes } > NearbySourcePreparer.MAX_TOTAL_BYTES ||
                batches.values.sumOf { it.pathCharacters } + pathCharacters > NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS)) {
            val finished = batches.entries.firstOrNull { it.value.pending == 0 }
            require(finished != null) { "Siuntimo eilė pilna" }
            batches.remove(finished.key)
        }
        NearbyTransferMemory.requireAvailable(batches.values.sumOf { it.files.size } + files.size,
            batches.values.sumOf { it.pathCharacters } + pathCharacters)
        batches[id] = Batch(files.mapTo(ArrayList(files.size)) { it.copy(batchId = id.orEmpty()) })
        if (id != null) seenBatches.add(id)
        return snapshot()
    }

    @Synchronized fun requireBatch(id: String?) {
        require((id == null && batches.isEmpty()) || batches.containsKey(id)) { "Siuntimo rinkinio keliai nesutampa" }
    }

    @Synchronized fun requireReadyBatch(id: String?) {
        requireBatch(id)
        require(batches.entries.takeWhile { it.key != id }.all { it.value.pending == 0 }) { "Gavimo sesija nepatvirtinta" }
    }

    @Synchronized fun validate(index: Int, path: String, size: Long, id: String? = null) {
        requireReadyBatch(id)
        val batch = batches[id] ?: return // Older senders have no manifest.
        require(batches.values.sumOf { it.active } < maximumConcurrentUploads) {
            "Gavimo sesija nepatvirtinta"
        }
        val item = batch.files.getOrNull(index - 1)
        require(item != null && item.relativePath == path && item.sizeBytes == size &&
            item.status in setOf(TransferFileStatus.WAITING, TransferFileStatus.FAILED)) {
            "Siuntimo rinkinio keliai nesutampa"
        }
        batch.replace(index - 1, item.copy(status = TransferFileStatus.TRANSFERRING, failure = null))
    }

    @Synchronized fun update(index: Int, item: TransferFileProgress, id: String? = null): List<TransferFileProgress> {
        requireBatch(id)
        if (!batches.containsKey(id)) return listOf(item)
        record(index, item, id)
        return snapshot()
    }

    /** O(1) row update, with at most16 batch counters. UI snapshots have a separate cadence. */
    @Synchronized fun record(index: Int, item: TransferFileProgress, id: String? = null) {
        requireBatch(id)
        val batch = batches[id] ?: return
        val previous = batch.files[index - 1]
        require(item.relativePath == previous.relativePath && item.sizeBytes == previous.sizeBytes) { "Siuntimo rinkinio keliai nesutampa" }
        val next = if (previous.status == TransferFileStatus.CANCELLED && item.status != TransferFileStatus.COMPLETED) {
            item.copy(status = TransferFileStatus.CANCELLED)
        } else item
        batch.replace(index - 1, next.copy(batchId = id.orEmpty()))
    }

    @Synchronized fun cancel(id: String?): List<TransferFileProgress> {
        requireBatch(id)
        // Stopping the remaining batch must not rewrite an established failure
        // (or its diagnostic) as a cancellation by the user.
        batches[id]?.let { batch ->
            val dueToFailure = batch.files.any { it.status == TransferFileStatus.FAILED }
            batch.files.indices.forEach { batch.replace(it, batch.files[it].stopPending(TransferFileStatus.CANCELLED, dueToFailure)) }
        }
        return snapshot()
    }

    @Synchronized fun cancelFile(id: String?, index: Int): List<TransferFileProgress> {
        requireBatch(id)
        val batch = batches[id] ?: throw IllegalArgumentException("Siuntimo rinkinio keliai nesutampa")
        val current = batch.files.getOrNull(index - 1) ?: throw IllegalArgumentException("Siuntimo rinkinio keliai nesutampa")
        if (!current.status.isTerminal()) {
            batch.replace(index - 1, current.copy(status = TransferFileStatus.CANCELLED, localPath = null))
        }
        return snapshot()
    }

    @Synchronized fun status(id: String?, index: Int): TransferFileStatus? =
        batches[id]?.files?.getOrNull(index - 1)?.status

    @Synchronized fun detail(id: String?, index: Int): TransferFileProgress? =
        batches[id]?.files?.getOrNull(index - 1)

    @Synchronized fun isCancelled(id: String?, index: Int): Boolean = batches[id]?.files?.getOrNull(index - 1)?.status == TransferFileStatus.CANCELLED
}

internal fun TransferFileStatus.isTerminal(): Boolean = this in setOf(TransferFileStatus.COMPLETED, TransferFileStatus.FAILED, TransferFileStatus.CANCELLED)

/** Sender and receiver agree: a later stop cannot replace a terminal result. */
internal fun TransferFileProgress.stopPending(stop: TransferFileStatus, dueToFailure: Boolean = stop == TransferFileStatus.FAILED): TransferFileProgress {
    require(stop == TransferFileStatus.CANCELLED || stop == TransferFileStatus.FAILED)
    return when {
        status.isTerminal() -> this
        status == TransferFileStatus.WAITING && dueToFailure -> copy(status = TransferFileStatus.CANCELLED, notAttempted = true)
        else -> copy(status = stop)
    }
}
