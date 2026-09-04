package com.affilemanager.app.provider

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class AfDocumentId(val rootId: String, val relativePath: String)

internal object AfDocumentIdRules {
    private const val SEPARATOR = ':'
    internal const val MAX_PATH_SEGMENTS = 64
    internal const val MAX_RELATIVE_PATH_BYTES = 8 * 1024
    private const val MAX_DOCUMENT_ID_CHARS = 12 * 1024
    private const val MAX_SEGMENT_CHARS = 255
    private val safeRootId = Regex("[A-Za-z0-9._-]{1,96}")

    fun root(rootId: String): String {
        require(safeRootId.matches(rootId)) { "Invalid root ID" }
        return "$rootId$SEPARATOR"
    }

    fun encode(rootId: String, relativePath: String): String {
        require(safeRootId.matches(rootId)) { "Invalid root ID" }
        val normalized = normalizeRelative(relativePath)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
        return "$rootId$SEPARATOR$encoded".also { documentId ->
            require(documentId.length <= MAX_DOCUMENT_ID_CHARS) { "Document ID is too long" }
        }
    }

    fun parse(documentId: String): AfDocumentId {
        require(documentId.length <= MAX_DOCUMENT_ID_CHARS) { "Document ID is too long" }
        val split = documentId.indexOf(SEPARATOR)
        require(split in 1 until documentId.length + 1) { "Invalid document ID" }
        val rootId = documentId.substring(0, split)
        require(safeRootId.matches(rootId)) { "Invalid root ID" }
        val encoded = documentId.substring(split + 1)
        val relative = if (encoded.isEmpty()) "" else String(
            Base64.getUrlDecoder().decode(encoded),
            StandardCharsets.UTF_8,
        )
        return AfDocumentId(rootId, normalizeRelative(relative))
    }

    fun resolve(root: File, documentId: String): File {
        val parsed = parse(documentId)
        val canonicalRoot = root.canonicalFile
        val candidate = if (parsed.relativePath.isEmpty()) canonicalRoot else File(canonicalRoot, parsed.relativePath).canonicalFile
        require(candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "Document escapes its storage root"
        }
        require(isAllowedRelative(parsed.relativePath)) { "Protected Android directory is not exported" }
        return candidate
    }

    fun relative(root: File, file: File): String {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile == canonicalRoot || canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
            "Document escapes its storage root"
        }
        val relative = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath.takeUnless { it == "." }.orEmpty()
        require(isAllowedRelative(relative)) { "Protected Android directory is not exported" }
        return relative
    }

    fun isAllowedRelative(relativePath: String): Boolean {
        val normalized = normalizeRelative(relativePath)
        return normalized != "Android/data" && !normalized.startsWith("Android/data/") &&
            normalized != "Android/obb" && !normalized.startsWith("Android/obb/")
    }

    private fun normalizeRelative(path: String): String {
        require('\u0000' !in path) { "Invalid document path" }
        require(path.length <= MAX_RELATIVE_PATH_BYTES) { "Document path is too long" }
        val segments = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
        require(segments.size <= MAX_PATH_SEGMENTS) { "Document path is too deep" }
        require(segments.none { it == "." || it == ".." }) { "Invalid document path" }
        require(segments.none { it.length > MAX_SEGMENT_CHARS }) { "Document path segment is too long" }
        return segments.joinToString("/").also { normalized ->
            require(normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_RELATIVE_PATH_BYTES) {
                "Document path is too long"
            }
        }
    }
}
