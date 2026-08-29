package com.affilemanager.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext

data class FileSelectionSummary(
    val selectedItems: Int,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val scannedNodes: Int,
    val complete: Boolean,
)

/** Bounded, non-symlink-following scanner used by the file information dialog. */
class FileSelectionInfoScanner(
    private val maxScannedNodes: Int = MAX_SCANNED_NODES,
    private val maxRoots: Int = MAX_ROOTS,
) {
    companion object {
        const val MAX_SCANNED_NODES = 200_000
        const val MAX_ROOTS = 10_000
        private const val MAX_DEPTH = 64
    }

    suspend fun scan(sourcePaths: Collection<String>): FileSelectionSummary = withContext(Dispatchers.IO) {
        require(maxScannedNodes > 0 && maxRoots > 0) { "Netinkama skenavimo riba" }
        val requested = sourcePaths.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { Paths.get(it).toAbsolutePath().normalize() }
            .distinct()
            .take(maxRoots + 1)
            .toList()
        require(requested.isNotEmpty()) { "Nepasirinkta failų" }
        var complete = requested.size <= maxRoots
        val selected = compactRoots(requested.take(maxRoots))
        val pending = ArrayDeque<PendingPath>()
        selected.asReversed().forEach { pending.add(PendingPath(it, depth = 0, selectedRoot = true)) }
        var files = 0
        var folders = 0
        var bytes = 0L
        var scanned = 0

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (scanned >= maxScannedNodes) {
                complete = false
                break
            }
            val current = pending.removeLast()
            scanned += 1
            try {
                when {
                    Files.isSymbolicLink(current.path) -> {
                        complete = false
                    }
                    Files.isDirectory(current.path, LinkOption.NOFOLLOW_LINKS) -> {
                        if (!current.selectedRoot) folders += 1
                        if (current.depth >= MAX_DEPTH) {
                            complete = false
                            continue
                        }
                        Files.newDirectoryStream(current.path).use { stream ->
                            val iterator = stream.iterator()
                            while (iterator.hasNext()) {
                                coroutineContext.ensureActive()
                                if (scanned + pending.size >= maxScannedNodes) {
                                    complete = false
                                    break
                                }
                                pending.add(PendingPath(iterator.next(), current.depth + 1, selectedRoot = false))
                            }
                        }
                    }
                    Files.isRegularFile(current.path, LinkOption.NOFOLLOW_LINKS) -> {
                        files += 1
                        bytes = saturatedAdd(bytes, Files.size(current.path).coerceAtLeast(0L))
                    }
                    else -> complete = false
                }
            } catch (_: SecurityException) {
                complete = false
            } catch (_: IOException) {
                complete = false
            }
        }

        FileSelectionSummary(
            selectedItems = requested.take(maxRoots).size,
            fileCount = files,
            folderCount = folders,
            totalBytes = bytes,
            scannedNodes = scanned,
            complete = complete,
        )
    }

    private fun compactRoots(paths: List<Path>): List<Path> {
        val ordered = paths.sortedWith(compareBy<Path>({ it.nameCount }, { it.toString() }))
        val result = ArrayList<Path>(ordered.size)
        ordered.forEach { candidate ->
            if (result.none(candidate::startsWith)) result.add(candidate)
        }
        return result
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class PendingPath(
        val path: Path,
        val depth: Int,
        val selectedRoot: Boolean,
    )
}
