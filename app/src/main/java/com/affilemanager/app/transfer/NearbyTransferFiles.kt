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
) {
    val name: String get() = relativePath.substringAfterLast('/')
}

/** Optional, versioned metadata; never sends source absolute paths or thumbnails. */
internal object NearbyTransferManifest {
    const val MAX_BYTES = 1_048_576

    fun encode(files: List<TransferFileProgress>): ByteArray {
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
            require(size != null && size in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą" }
            total = Math.addExact(total, size)
            require(total <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 5 GB ribą" }
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

/** One upload at a time, with separately identified pending batches and bounded visible history. */
internal class NearbyReceiveFiles {
    private val batches = linkedMapOf<String?, List<TransferFileProgress>>()
    private val seenBatches = mutableSetOf<String>()
    @Synchronized fun hasManifest(): Boolean = batches.isNotEmpty()

    @Synchronized fun snapshot(): List<TransferFileProgress> = batches.flatMap { (id, files) ->
        files.map { it.copy(batchId = id.orEmpty()) }
    }

    @Synchronized fun announce(files: List<TransferFileProgress>, id: String? = null): List<TransferFileProgress> {
        if (id != null) require(id.length == 36 && runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Gavimo sesija nepatvirtinta" }
        batches[id]?.let { current ->
            // An HTTP retry after a lost acknowledgement must not reset progress.
            require(current.map { it.relativePath to it.sizeBytes } == files.map { it.relativePath to it.sizeBytes }) {
                "Gavimo sesija nepatvirtinta"
            }
            return snapshot()
        }
        require(id == null || (id !in seenBatches && seenBatches.size < 128)) { "Gavimo sesija nepatvirtinta" }
        require(files.size <= NearbySourcePreparer.MAX_FILES && files.sumOf { it.sizeBytes } <= NearbySourcePreparer.MAX_TOTAL_BYTES)
        // Drop only finished history when a new explicit batch needs the finite metadata budget.
        while (batches.isNotEmpty() && (batches.size >= 16 || batches.values.sumOf { it.size } + files.size > NearbySourcePreparer.MAX_FILES ||
                batches.values.flatten().sumOf { it.sizeBytes } + files.sumOf { it.sizeBytes } > NearbySourcePreparer.MAX_TOTAL_BYTES)) {
            val finished = batches.entries.firstOrNull { it.value.all { file -> file.status.isTerminal() } }
            require(finished != null) { "Siuntimo eilė pilna" }
            batches.remove(finished.key)
        }
        batches[id] = files
        if (id != null) seenBatches.add(id)
        return snapshot()
    }

    @Synchronized fun requireBatch(id: String?) {
        require((id == null && batches.isEmpty()) || batches.containsKey(id)) { "Siuntimo rinkinio keliai nesutampa" }
    }

    @Synchronized fun validate(index: Int, path: String, size: Long, id: String? = null) {
        requireBatch(id)
        val files = batches[id] ?: return // Older senders have no manifest.
        require(batches.entries.takeWhile { it.key != id }.all { it.value.all { file -> file.status.isTerminal() } } &&
            batches.values.all { it.none { file -> file.status == TransferFileStatus.TRANSFERRING } }) {
            "Gavimo sesija nepatvirtinta"
        }
        val item = files.getOrNull(index - 1)
        require(item != null && item.relativePath == path && item.sizeBytes == size &&
            item.status in setOf(TransferFileStatus.WAITING, TransferFileStatus.FAILED)) {
            "Siuntimo rinkinio keliai nesutampa"
        }
        batches[id] = files.toMutableList().apply { this[index - 1] = item.copy(status = TransferFileStatus.TRANSFERRING) }
    }

    @Synchronized fun update(index: Int, item: TransferFileProgress, id: String? = null): List<TransferFileProgress> {
        requireBatch(id)
        val files = batches[id] ?: return listOf(item)
        val changed = files.toMutableList()
        val previous = changed[index - 1]
        changed[index - 1] = if (previous.status == TransferFileStatus.CANCELLED && item.status != TransferFileStatus.COMPLETED) {
            item.copy(status = TransferFileStatus.CANCELLED)
        } else item
        batches[id] = changed
        return snapshot()
    }

    @Synchronized fun cancel(id: String?): List<TransferFileProgress> {
        requireBatch(id)
        batches[id]?.let { files -> batches[id] = files.map { if (it.status == TransferFileStatus.COMPLETED) it else it.copy(status = TransferFileStatus.CANCELLED) } }
        return snapshot()
    }

    @Synchronized fun isCancelled(id: String?, index: Int): Boolean = batches[id]?.getOrNull(index - 1)?.status == TransferFileStatus.CANCELLED
}

internal fun TransferFileStatus.isTerminal(): Boolean = this in setOf(TransferFileStatus.COMPLETED, TransferFileStatus.FAILED, TransferFileStatus.CANCELLED)
