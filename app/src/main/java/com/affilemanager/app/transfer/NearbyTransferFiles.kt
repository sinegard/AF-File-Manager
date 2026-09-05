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

/** At most one announced batch in the one-time authenticated receiver session. */
internal class NearbyReceiveFiles {
    private var announced: List<TransferFileProgress>? = null
    @Synchronized fun hasManifest(): Boolean = announced != null

    @Synchronized fun announce(files: List<TransferFileProgress>): List<TransferFileProgress> {
        announced?.let { current ->
            // An HTTP retry after a lost acknowledgement must not reset progress.
            require(current.map { it.relativePath to it.sizeBytes } == files.map { it.relativePath to it.sizeBytes }) {
                "Gavimo sesija nepatvirtinta"
            }
            return current
        }
        announced = files
        return files
    }

    @Synchronized fun validate(index: Int, path: String, size: Long) {
        val files = announced ?: return // Older senders have no manifest.
        val item = files.getOrNull(index - 1)
        require(item != null && item.relativePath == path && item.sizeBytes == size &&
            item.status in setOf(TransferFileStatus.WAITING, TransferFileStatus.FAILED)) {
            "Siuntimo rinkinio keliai nesutampa"
        }
        announced = files.toMutableList().apply { this[index - 1] = item.copy(status = TransferFileStatus.TRANSFERRING) }
    }

    @Synchronized fun update(index: Int, item: TransferFileProgress): List<TransferFileProgress> {
        val files = announced ?: return listOf(item)
        val changed = files.toMutableList()
        changed[index - 1] = item
        announced = changed
        return changed
    }
}
