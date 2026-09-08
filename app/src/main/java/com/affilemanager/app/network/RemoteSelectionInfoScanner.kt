package com.affilemanager.app.network

import com.affilemanager.app.data.FileSelectionSummary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RemoteSelectionInfoScanner(private val maxNodes: Int = 100_000, private val maxDepth: Int = 64) {
    suspend fun scan(entries: Collection<RemoteEntry>, client: RemoteClient): FileSelectionSummary {
        require(maxNodes > 0 && maxDepth > 0) { "Netinkama skenavimo riba" }
        val roots = compact(entries.distinctBy { RemotePath.normalize(it.path) })
        require(roots.isNotEmpty() && roots.size <= 10_000) { "Netinkamas pasirinktų elementų skaičius" }
        data class Pending(val entry: RemoteEntry, val depth: Int, val selectedRoot: Boolean)
        val pending = ArrayDeque<Pending>()
        roots.asReversed().forEach { pending.add(Pending(it, 0, true)) }
        val seen = hashSetOf<String>()
        var files = 0
        var folders = 0
        var bytes = 0L
        var scanned = 0
        var complete = true
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (scanned >= maxNodes) { complete = false; break }
            val current = pending.removeLast()
            val path = RemotePath.normalize(current.entry.path)
            if (!seen.add(path)) { complete = false; continue }
            scanned++
            if (!current.entry.directory) {
                files++
                val value = current.entry.sizeBytes.coerceAtLeast(0L)
                bytes = if (value > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + value
                continue
            }
            if (!current.selectedRoot) folders++
            if (current.depth >= maxDepth) { complete = false; continue }
            val children = runCatching { client.list(path) }.getOrElse { complete = false; emptyList() }
            children.forEach { child ->
                val childPath = RemotePath.normalize(child.path)
                if (childPath != path && childPath.startsWith(if (path == "/") "/" else "$path/")) {
                    pending.add(Pending(child.copy(path = childPath), current.depth + 1, false))
                } else complete = false
            }
        }
        return FileSelectionSummary(roots.size, files, folders, bytes, scanned, complete)
    }

    private fun compact(entries: List<RemoteEntry>): List<RemoteEntry> {
        val ordered = entries.sortedWith(compareBy<RemoteEntry>({ RemotePath.normalize(it.path).count { char -> char == '/' } }, { it.path }))
        val result = mutableListOf<RemoteEntry>()
        ordered.forEach { candidate ->
            val path = RemotePath.normalize(candidate.path)
            if (result.none { root ->
                    val rootPath = RemotePath.normalize(root.path)
                    path == rootPath || path.startsWith(if (rootPath == "/") "/" else "$rootPath/")
                }) result += candidate.copy(path = path)
        }
        return result
    }
}
