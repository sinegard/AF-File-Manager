package com.affilemanager.app.model

import java.io.File

enum class EntryKind {
    DIRECTORY,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    OTHER,
}

data class FileEntry(
    val absolutePath: String,
    val name: String,
    val kind: EntryKind,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val isHidden: Boolean,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val metadataComplete: Boolean = true,
) {
    val isDirectory: Boolean get() = kind == EntryKind.DIRECTORY
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val file: File get() = File(absolutePath)
}

data class ContentFileEntry(
    val uri: String,
    val name: String,
    val kind: EntryKind,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

enum class SortMode {
    NAME,
    SIZE,
    MODIFIED,
    TYPE,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

enum class ConflictPolicy {
    ASK,
    SKIP,
    REPLACE,
    KEEP_BOTH,
    MERGE,
}

enum class ClipboardMode {
    COPY,
    MOVE,
}

data class ClipboardState(
    val paths: List<String>,
    val mode: ClipboardMode,
)

data class StorageRoot(
    val id: String,
    val title: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val removable: Boolean,
)

data class SearchFilters(
    val query: String = "",
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val kinds: Set<EntryKind> = emptySet(),
    val includeHidden: Boolean = false,
    val useRegex: Boolean = false,
    val tags: Set<String> = emptySet(),
)

data class FileSearchResult(
    val entries: List<FileEntry>,
    val scannedEntries: Int,
    val truncated: Boolean,
)

data class DuplicateGroup(
    val sha256: String,
    val sizeBytes: Long,
    val paths: List<String>,
)

data class DirectoryUsage(
    val path: String,
    val sizeBytes: Long,
    val fileCount: Int,
)

data class FileTypeUsage(
    val kind: EntryKind,
    val sizeBytes: Long,
    val fileCount: Int,
)

data class StorageAnalysis(
    val scannedFiles: Int,
    val scannedDirectories: Int,
    val totalBytes: Long,
    val largestFiles: List<FileEntry>,
    val oldestFiles: List<FileEntry>,
    val emptyDirectories: List<String>,
    val truncated: Boolean,
    val largestDirectories: List<DirectoryUsage> = emptyList(),
    val typeUsage: List<FileTypeUsage> = emptyList(),
)
