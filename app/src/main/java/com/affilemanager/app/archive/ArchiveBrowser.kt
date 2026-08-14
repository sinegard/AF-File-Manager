package com.affilemanager.app.archive

internal data class ArchiveBrowserItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
)

/**
 * Builds a bounded, read-only directory index from the archive listing.
 *
 * Archive formats do not always contain explicit entries for directories, so
 * every intermediate path component is represented as a synthetic directory.
 * The index is built once per preview and makes folder navigation independent
 * of the total number of archive entries.
 */
internal class ArchiveBrowserIndex private constructor(
    private val childrenByParent: Map<String, List<ArchiveBrowserItem>>,
) {
    fun children(path: String): List<ArchiveBrowserItem> = childrenByParent[path].orEmpty()

    companion object {
        private const val MAX_PATH_CHARACTERS = 4_096
        private const val MAX_PATH_DEPTH = 64
        private const val MAX_BROWSER_NODES = 200_000

        fun from(entries: List<ArchiveEntryInfo>): ArchiveBrowserIndex {
            val itemsByPath = linkedMapOf<String, ArchiveBrowserItem>()
            var omittedEntries = 0

            entries.forEach { entry ->
                if (entry.name.length > MAX_PATH_CHARACTERS) {
                    omittedEntries += 1
                    return@forEach
                }
                val segments = entry.name
                    .replace('\\', '/')
                    .splitToSequence('/')
                    .filter { it.isNotEmpty() && it != "." }
                    .take(MAX_PATH_DEPTH + 1)
                    .toList()

                if (segments.size > MAX_PATH_DEPTH) {
                    omittedEntries += 1
                    return@forEach
                }

                var path = ""
                val candidates = segments.mapIndexed { index, segment ->
                    path = if (path.isEmpty()) segment else "$path/$segment"
                    val isLastSegment = index == segments.lastIndex
                    val isDirectory = !isLastSegment || entry.directory
                    ArchiveBrowserItem(
                        name = segment,
                        path = path,
                        directory = isDirectory,
                        sizeBytes = if (isDirectory) -1 else entry.sizeBytes,
                    )
                }
                val newNodes = candidates.count { it.path !in itemsByPath }
                if (itemsByPath.size + newNodes > MAX_BROWSER_NODES - 1) {
                    omittedEntries += 1
                    return@forEach
                }

                candidates.forEach { candidate ->
                    val existing = itemsByPath[candidate.path]
                    itemsByPath[candidate.path] = when {
                        existing == null -> candidate
                        existing.directory || candidate.directory -> existing.copy(directory = true, sizeBytes = -1)
                        else -> existing
                    }
                }
            }

            if (omittedEntries > 0) {
                itemsByPath["\u0000omitted-archive-paths"] = ArchiveBrowserItem(
                    name = "$omittedEntries archyvo įrašų nerodoma: viršyta saugi kelio arba naršymo riba",
                    path = "\u0000omitted-archive-paths",
                    directory = false,
                    sizeBytes = -1,
                )
            }

            val ordering = compareByDescending<ArchiveBrowserItem> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
            val indexed = itemsByPath.values
                .groupBy { parentOf(it.path) }
                .mapValues { (_, children) -> children.sortedWith(ordering) }
            return ArchiveBrowserIndex(indexed)
        }

        fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

        fun folderName(path: String): String = path.substringAfterLast('/')
    }
}
