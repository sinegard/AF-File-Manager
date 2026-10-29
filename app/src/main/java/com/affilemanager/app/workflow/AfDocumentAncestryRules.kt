package com.affilemanager.app.workflow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque

/** Public-API path proofs, with a bounded graph fallback for older providers. */
internal object AfDocumentAncestryRules {
    const val MAX_PATH_BYTES = 32L * 1024

    fun verifiedPath(path: List<String>, expectedLast: String): List<String> {
        require(path.size in 1..65) { "Document source tree exceeds its safe limit" }
        var bytes = 0L
        val snapshot = ArrayList<String>(path.size)
        path.forEach { id ->
            require(snapshot.size < 65 && id.isNotEmpty()) { "Document source changed" }
            bytes += 64L + id.length * 2L
            require(bytes <= MAX_PATH_BYTES) { "Document tree metadata exceeds its safe memory limit" }
            snapshot += id
        }
        require(snapshot.last() == expectedLast) { "Document source changed" }
        return snapshot
    }

    fun pathRelation(parent: String, child: String, childPath: List<String>?): Boolean? {
        if (parent == child) return false
        if (childPath != null && parent in childPath) return true
        // A grant can truncate a path, and a document can have multiple parents.
        // Absence from one returned path is never proof of disjointness.
        return null
    }

    suspend fun <T> reachable(
        parent: T,
        target: String,
        id: (T) -> String,
        directory: (T) -> Boolean,
        estimate: (T) -> Long,
        children: suspend (T, Int) -> Collection<T>,
        maximumEntries: Int = 100_000,
        maximumDepth: Int = 64,
        maximumBytes: Long = AfWorkflowLimits.MAX_METADATA_BYTES * 3 / 4 - 2 * MAX_PATH_BYTES,
    ): Boolean {
        require(maximumEntries in 1..100_000 && maximumDepth in 1..64 &&
            maximumBytes in 1..(AfWorkflowLimits.MAX_METADATA_BYTES * 3 / 4 - 2 * MAX_PATH_BYTES))
        data class Pending<T>(val node: T, val depth: Int, val bytes: Long)
        val pending = ArrayDeque<Pending<T>>()
        val visited = hashSetOf(id(parent))
        val initial = estimate(parent)
        require(initial in 1..maximumBytes) { "Document tree metadata exceeds its safe memory limit" }
        var metadataBytes = initial
        var pendingBytes = initial
        var examined = 1
        pending.add(Pending(parent, 0, initial))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = pending.removeLast()
            pendingBytes -= current.bytes
            require(examined < maximumEntries) { "Document source tree exceeds its safe limit" }
            for (entry in children(current.node, maximumEntries - examined)) {
                currentCoroutineContext().ensureActive()
                require(++examined <= maximumEntries && current.depth + 1 <= maximumDepth) { "Document source tree exceeds its safe limit" }
                val key = id(entry)
                if (key == target) return true
                if (!visited.add(key)) continue
                val bytes = estimate(entry)
                require(bytes in 1..maximumBytes) { "Document tree metadata exceeds its safe memory limit" }
                metadataBytes += bytes
                if (directory(entry)) {
                    pendingBytes += bytes
                    pending.add(Pending(entry, current.depth + 1, bytes))
                }
                require(metadataBytes + pendingBytes <= maximumBytes) { "Document tree metadata exceeds its safe memory limit" }
            }
        }
        return false
    }
}
