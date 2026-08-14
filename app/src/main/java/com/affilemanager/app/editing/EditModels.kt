package com.affilemanager.app.editing

import com.affilemanager.app.model.EntryKind
import java.io.File

object EditLimits {
    const val MAX_FILE_BYTES: Long = 256L * 1_024 * 1_024
    const val MAX_TEXT_BYTES: Int = 2 * 1_024 * 1_024
    const val MIN_FREE_BYTES: Long = 32L * 1_024 * 1_024
}

data class FileRevision(
    val sizeBytes: Long,
    val modifiedAtMillis: Long?,
    val sha256: String,
) {
    init {
        require(sizeBytes >= 0) { "File size cannot be negative" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 revision" }
    }

    fun hasSameContent(other: FileRevision?): Boolean = other != null &&
        sizeBytes == other.sizeBytes &&
        sha256 == other.sha256
}

sealed interface EditOrigin {
    val label: String
    val canWrite: Boolean

    data class Local(
        val path: String,
        override val canWrite: Boolean,
    ) : EditOrigin {
        override val label: String = path
    }

    data class Content(
        val uri: String,
        override val canWrite: Boolean,
    ) : EditOrigin {
        override val label: String = uri
    }

    data class Remote(
        val profileId: String,
        val connectionName: String,
        val path: String,
    ) : EditOrigin {
        override val label: String = "$connectionName · $path"
        override val canWrite: Boolean = true
    }
}

data class EditSession(
    val id: String,
    val sourceKey: String,
    val displayName: String,
    val mimeType: String,
    val workingFile: File,
    val origin: EditOrigin,
    val originRevision: FileRevision,
    val workingRevision: FileRevision,
    val lastSavedRevision: FileRevision,
    val usesInternalTextEditor: Boolean,
) {
    val hasOriginChanges: Boolean get() = !workingRevision.hasSameContent(originRevision)
    val hasUnsavedChanges: Boolean get() = !workingRevision.hasSameContent(lastSavedRevision)
}

data class EditConflict(
    val originLabel: String,
    val expected: FileRevision,
    val current: FileRevision?,
)

sealed interface EditSaveResult {
    data class Saved(val revision: FileRevision) : EditSaveResult
    data class Conflict(val details: EditConflict) : EditSaveResult
}

object EditabilityRules {
    private val internalTextExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml",
        "log", "html", "htm", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx",
        "css", "scss", "sh", "bash", "zsh", "ini", "conf", "cfg", "toml", "properties",
        "gradle", "sql", "c", "h", "cpp", "hpp", "cs", "go", "rs", "php", "rb", "swift",
    )

    fun supportsInternalText(name: String, mimeType: String, kind: EntryKind): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in internalTextExtensions ||
            mimeType.substringBefore(';').trim().lowercase().startsWith("text/") ||
            (kind == EntryKind.DOCUMENT && mimeType.lowercase() in setOf(
                "application/json",
                "application/ld+json",
                "application/xml",
                "application/x-yaml",
                "application/yaml",
                "application/javascript",
            ))
    }

    fun mayUseExternalEditor(kind: EntryKind, extension: String): Boolean =
        kind !in setOf(EntryKind.DIRECTORY, EntryKind.ARCHIVE, EntryKind.APK) && extension.lowercase() != "afvault"
}
