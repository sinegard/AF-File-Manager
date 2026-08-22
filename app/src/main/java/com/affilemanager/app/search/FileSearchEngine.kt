package com.affilemanager.app.search

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.DuplicateAnalysisResult
import com.affilemanager.app.model.DirectoryUsage
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.FileSearchResult
import com.affilemanager.app.model.FileTypeUsage
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.StorageAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class FileSearchEngine(
    private val toEntry: (File) -> FileEntry,
    private val maxScannedEntries: Int = MAX_SCANNED_ENTRIES,
    private val maxResults: Int = MAX_RESULTS,
    private val maxDuplicateCandidates: Int = MAX_DUPLICATE_CANDIDATES,
) {
    companion object {
        const val MAX_SCANNED_ENTRIES = 200_000
        const val MAX_RESULTS = 5_000
        const val MAX_DUPLICATE_CANDIDATES = 20_000
        const val MAX_CLEANUP_PACKAGES = 2_000
        const val MAX_SIMILAR_IMAGE_CANDIDATES = 1_000
        private const val HASH_BUFFER = 256 * 1_024
    }

    constructor(localFiles: LocalFileRepository) : this(localFiles::toEntry)

    suspend fun search(
        roots: List<String>,
        filters: SearchFilters,
        pathPredicate: (FileEntry) -> Boolean = { true },
    ): FileSearchResult = withContext(Dispatchers.IO) {
        require(roots.isNotEmpty()) { "Nepasirinkta paieškos vieta" }
        val regex = if (filters.useRegex && filters.query.isNotBlank()) {
            Regex(filters.query, RegexOption.IGNORE_CASE)
        } else {
            null
        }
        val results = mutableListOf<FileEntry>()
        val rootPaths = roots.map { File(it).absoluteFile.toPath().normalize().toString() }.toSet()
        val walk = walk(roots) { file ->
            val normalizedPath = file.absoluteFile.toPath().normalize().toString()
            if (normalizedPath in rootPaths) return@walk WalkAction.DESCEND
            if (!filters.includeHidden && (file.isHidden || file.name.startsWith('.'))) return@walk WalkAction.SKIP_CHILDREN
            val entry = toEntry(file)
            val nameMatches = when {
                filters.query.isBlank() -> true
                regex != null -> regex.containsMatchIn(entry.name)
                else -> entry.name.contains(filters.query, ignoreCase = true)
            }
            val matches = nameMatches &&
                (filters.minBytes == null || entry.sizeBytes >= filters.minBytes) &&
                (filters.maxBytes == null || entry.sizeBytes <= filters.maxBytes) &&
                (filters.modifiedAfter == null || entry.modifiedAtMillis >= filters.modifiedAfter) &&
                (filters.modifiedBefore == null || entry.modifiedAtMillis <= filters.modifiedBefore) &&
                (filters.kinds.isEmpty() || entry.kind in filters.kinds) &&
                pathPredicate(entry)
            if (matches) results += entry
            if (results.size >= maxResults) WalkAction.STOP else WalkAction.DESCEND
        }
        FileSearchResult(
            entries = results.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }.thenBy { it.absolutePath }),
            scannedEntries = walk.visited,
            truncated = walk.limitReached || walk.stoppedEarly,
        )
    }

    suspend fun duplicates(roots: List<String>): DuplicateAnalysisResult = withContext(Dispatchers.IO) {
        val bySize = mutableMapOf<Long, MutableList<File>>()
        var candidates = 0
        val walk = walk(roots) { file ->
            if (file.isFile && file.length() > 0) {
                if (candidates >= maxDuplicateCandidates) return@walk WalkAction.STOP
                candidates += 1
                bySize.getOrPut(file.length()) { mutableListOf() }.add(file)
            }
            WalkAction.DESCEND
        }

        val groups = mutableListOf<DuplicateGroup>()
        bySize.filterValues { it.size > 1 }.forEach { (size, files) ->
            val byHash = files.mapNotNull { file ->
                runCatching { sha256(file) }.getOrNull()?.let { hash -> hash to file }
            }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            byHash.filterValues { it.size > 1 }.forEach { (hash, duplicates) ->
                groups += DuplicateGroup(hash, size, duplicates.map(File::getAbsolutePath).sorted())
            }
        }
        DuplicateAnalysisResult(
            groups = groups.sortedByDescending { it.sizeBytes * it.paths.size },
            scannedCandidates = candidates,
            truncated = walk.limitReached || walk.stoppedEarly,
        )
    }

    suspend fun analyze(roots: List<String>): StorageAnalysis = withContext(Dispatchers.IO) {
        var files = 0
        var directories = 0
        var bytes = 0L
        val largest = java.util.PriorityQueue<FileEntry>(compareBy { it.sizeBytes })
        val oldest = java.util.PriorityQueue<FileEntry>(compareByDescending { it.modifiedAtMillis })
        val installerAndArchives = java.util.PriorityQueue<FileEntry>(compareBy { it.sizeBytes })
        val similarImageCandidates = java.util.PriorityQueue<FileEntry>(compareBy { it.sizeBytes })
        val emptyDirectories = mutableListOf<String>()
        val directoryBytes = mutableMapOf<String, Long>()
        val directoryFiles = mutableMapOf<String, Int>()
        val typeBytes = mutableMapOf<EntryKind, Long>()
        val typeFiles = mutableMapOf<EntryKind, Int>()
        val normalizedRoots = roots.map { File(it).canonicalFile }.distinctBy(File::getAbsolutePath)
        val walk = walk(roots) { file ->
            if (file.isDirectory) {
                directories += 1
                directoryBytes.putIfAbsent(file.absolutePath, 0L)
                directoryFiles.putIfAbsent(file.absolutePath, 0)
                if (emptyDirectories.size < 1_000 && file.list()?.isEmpty() == true) emptyDirectories += file.absolutePath
            } else {
                files += 1
                val fileBytes = file.length().coerceAtLeast(0)
                bytes = saturatedAdd(bytes, fileBytes)
                val entry = toEntry(file)
                typeBytes[entry.kind] = saturatedAdd(typeBytes[entry.kind] ?: 0L, fileBytes)
                typeFiles[entry.kind] = (typeFiles[entry.kind] ?: 0) + 1
                val containingRoot = normalizedRoots
                    .filter { root -> file.toPath().normalize().startsWith(root.toPath().normalize()) }
                    .maxByOrNull { it.absolutePath.length }
                var parent = file.parentFile
                var depth = 0
                while (parent != null && containingRoot != null && depth <= 64) {
                    val parentPath = parent.absolutePath
                    directoryBytes[parentPath] = saturatedAdd(directoryBytes[parentPath] ?: 0L, fileBytes)
                    directoryFiles[parentPath] = (directoryFiles[parentPath] ?: 0) + 1
                    if (parent == containingRoot) break
                    parent = parent.parentFile
                    depth += 1
                }
                largest += entry
                if (largest.size > 100) largest.remove()
                oldest += entry
                if (oldest.size > 100) oldest.remove()
                if (entry.kind == EntryKind.APK || entry.kind == EntryKind.ARCHIVE) {
                    installerAndArchives += entry
                    if (installerAndArchives.size > MAX_CLEANUP_PACKAGES) installerAndArchives.remove()
                }
                if (entry.kind == EntryKind.IMAGE && fileBytes >= 32L * 1_024L) {
                    similarImageCandidates += entry
                    if (similarImageCandidates.size > MAX_SIMILAR_IMAGE_CANDIDATES) similarImageCandidates.remove()
                }
            }
            WalkAction.DESCEND
        }
        StorageAnalysis(
            scannedFiles = files,
            scannedDirectories = directories,
            totalBytes = bytes,
            largestFiles = largest.toList().sortedByDescending { it.sizeBytes },
            oldestFiles = oldest.toList().sortedBy { it.modifiedAtMillis },
            emptyDirectories = emptyDirectories,
            truncated = walk.limitReached,
            largestDirectories = directoryBytes.entries
                .asSequence()
                .map { (path, size) -> DirectoryUsage(path, size, directoryFiles[path] ?: 0) }
                .sortedByDescending(DirectoryUsage::sizeBytes)
                .take(100)
                .toList(),
            typeUsage = typeBytes.entries
                .map { (kind, size) -> FileTypeUsage(kind, size, typeFiles[kind] ?: 0) }
                .sortedByDescending(FileTypeUsage::sizeBytes),
            installerAndArchiveFiles = installerAndArchives.toList().sortedByDescending(FileEntry::sizeBytes),
            similarImageCandidates = similarImageCandidates.toList().sortedByDescending(FileEntry::sizeBytes),
        )
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private enum class WalkAction { DESCEND, SKIP_CHILDREN, STOP }

    private data class WalkResult(val visited: Int, val limitReached: Boolean, val stoppedEarly: Boolean)

    private suspend fun walk(roots: List<String>, visitor: (File) -> WalkAction): WalkResult {
        val pending = ArrayDeque<Pair<File, Int>>()
        roots.distinct().forEach { pending.add(File(it) to 0) }
        var visited = 0
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (current, depth) = pending.removeLast()
            if (!current.exists()) continue
            visited += 1
            if (visited > maxScannedEntries) return WalkResult(maxScannedEntries, limitReached = true, stoppedEarly = false)
            when (visitor(current)) {
                WalkAction.STOP -> return WalkResult(visited, limitReached = false, stoppedEarly = pending.isNotEmpty() || current.isDirectory)
                WalkAction.SKIP_CHILDREN -> continue
                WalkAction.DESCEND -> Unit
            }
            if (current.isDirectory && depth < 64) {
                current.listFiles()?.forEach { child -> pending.add(child to depth + 1) }
            }
        }
        return WalkResult(visited, limitReached = false, stoppedEarly = false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
