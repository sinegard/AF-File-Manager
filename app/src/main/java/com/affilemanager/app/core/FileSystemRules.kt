package com.affilemanager.app.core

import com.affilemanager.app.model.EntryKind
import java.io.File
import java.util.Locale

object FileSystemRules {
    private const val MAX_FILE_NAME_LENGTH = 240
    private val forbiddenCharacters = charArrayOf('\u0000', '/', '\\')

    fun validateFileName(name: String): Result<String> {
        val candidate = name.trim()
        return when {
            candidate.isEmpty() -> Result.failure(IllegalArgumentException("Pavadinimas negali būti tuščias"))
            candidate == "." || candidate == ".." -> Result.failure(IllegalArgumentException("Netinkamas pavadinimas"))
            candidate.length > MAX_FILE_NAME_LENGTH -> Result.failure(IllegalArgumentException("Pavadinimas per ilgas"))
            forbiddenCharacters.any(candidate::contains) -> Result.failure(IllegalArgumentException("Pavadinime yra neleistinų ženklų"))
            else -> Result.success(candidate)
        }
    }

    fun isContained(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        return candidate.canonicalFile.toPath().startsWith(rootPath)
    }

    fun keepBothTarget(original: File): File {
        if (!original.exists()) return original
        val parent = original.parentFile ?: return original
        val fullName = original.name
        val extensionIndex = fullName.lastIndexOf('.').takeIf { it > 0 }
        val stem = extensionIndex?.let { fullName.substring(0, it) } ?: fullName
        val extension = extensionIndex?.let { fullName.substring(it) }.orEmpty()
        for (index in 1..10_000) {
            val candidate = File(parent, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        throw IllegalStateException("Nepavyko parinkti laisvo pavadinimo")
    }

    fun detectKind(file: File): EntryKind {
        return detectKind(file.name, mimeType = null, isDirectory = file.isDirectory)
    }

    fun detectKind(name: String, mimeType: String?, isDirectory: Boolean = false): EntryKind {
        if (isDirectory) return EntryKind.DIRECTORY
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when {
            normalizedMime.startsWith("image/") -> return EntryKind.IMAGE
            normalizedMime.startsWith("video/") -> return EntryKind.VIDEO
            normalizedMime.startsWith("audio/") -> return EntryKind.AUDIO
            normalizedMime.startsWith("text/") || normalizedMime in documentMimeTypes -> return EntryKind.DOCUMENT
            normalizedMime in archiveMimeTypes -> return EntryKind.ARCHIVE
            normalizedMime == "application/vnd.android.package-archive" -> return EntryKind.APK
        }
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg" -> EntryKind.IMAGE
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp" -> EntryKind.VIDEO
            "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus" -> EntryKind.AUDIO
            "pdf", "txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "html", "htm", "smil", "smi",
            "lua", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx", "py", "sh", "sql",
            "css", "scss", "sass", "less", "php", "rb", "go", "rs", "swift", "dart", "vue", "svelte", "smali",
            "gradle", "properties", "toml", "ini", "conf", "cfg", "proto", "graphql", "gql", "env", "gitignore",
            "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "epub" -> EntryKind.DOCUMENT
            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "jar" -> EntryKind.ARCHIVE
            "apk", "apks", "xapk" -> EntryKind.APK
            else -> EntryKind.OTHER
        }
    }

    private val documentMimeTypes = setOf(
        "application/pdf",
        "application/json",
        "application/xml",
        "application/epub+zip",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    private val archiveMimeTypes = setOf(
        "application/zip",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-rar-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-bzip2",
        "application/x-xz",
    )

    fun humanBytes(value: Long): String {
        if (value < 1_024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unitIndex = -1
        do {
            amount /= 1_024.0
            unitIndex += 1
        } while (amount >= 1_024 && unitIndex < units.lastIndex)
        return if (amount >= 100) {
            "%.0f %s".format(Locale.getDefault(), amount, units[unitIndex])
        } else {
            "%.1f %s".format(Locale.getDefault(), amount, units[unitIndex])
        }
    }
}
