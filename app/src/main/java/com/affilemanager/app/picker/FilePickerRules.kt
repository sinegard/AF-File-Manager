package com.affilemanager.app.picker

import java.io.File
import java.util.Locale

/** Rules for an external read-only picker; caller input never chooses its roots or a source path. */
internal data class FilePickerRequest(val mimeTypes: List<String>, val allowMultiple: Boolean) {
    val selectionLimit: Int get() = if (allowMultiple) 100 else 1

    fun accepts(mimeType: String): Boolean = mimeTypes.any { filter ->
        filter == "*/*" || filter.equals(mimeType, ignoreCase = true) ||
            (filter.endsWith("/*") && filter.substringBefore('/') == mimeType.substringBefore('/').lowercase(Locale.ROOT))
    }

    companion object {
        private val mimePattern = Regex("(?:[a-z0-9!#$&^_.+\\-]+|\\*)/(?:[a-z0-9!#$&^_.+\\-]+|\\*)")

        fun parse(type: String?, extras: List<String>?, multiple: Boolean): FilePickerRequest? {
            val primary = normalize(type) ?: return null
            if (extras != null && (extras.isEmpty() || extras.size > 32)) return null
            val filters = extras?.map { normalize(it) ?: return null }?.distinct() ?: listOf(primary)
            // EXTRA_MIME_TYPES refines, never broadens, the top-level type.
            if (primary != "*/*" && filters.any { filter ->
                    filter != primary && !(primary.endsWith("/*") &&
                        filter.substringBefore('/') == primary.substringBefore('/'))
                }) return null
            return FilePickerRequest(filters, multiple)
        }

        private fun normalize(value: String?): String? {
            if (value == null || value.length !in 3..127) return null
            val normalized = value.lowercase(Locale.ROOT)
            return normalized.takeIf { mimePattern.matches(it) && (!it.startsWith("*/") || it == "*/*") }
        }
    }
}

internal class FilePickerBoundary(roots: List<File>) {
    val roots: List<File> = roots.take(32).map(File::getCanonicalFile).distinct()

    fun permitted(path: String): File? = runCatching {
        if (path.length > 4096) return null
        val file = File(path).canonicalFile
        val root = roots.firstOrNull { file.toPath().startsWith(it.toPath()) } ?: return null
        val relative = root.toPath().relativize(file.toPath()).map { it.toString() }
        // Even AF's own external app-private data must not become an exported picker source.
        if (relative.size >= 2 && relative[0].equals("Android", true) &&
            relative[1].lowercase(Locale.ROOT) in setOf("data", "obb")) return null
        file
    }.getOrNull()

    fun parent(path: String): String? {
        if (path.isEmpty()) return null // virtual list of OS storage volumes
        val file = permitted(path) ?: return ""
        if (file in roots) return ""
        return file.parentFile?.takeIf { permitted(it.path) != null }?.path ?: ""
    }

    fun selected(paths: List<String>, request: FilePickerRequest, mimeType: (File) -> String): List<File> {
        require(paths.isNotEmpty() && paths.size <= request.selectionLimit) { "Failų paruošti nepavyko" }
        val files = paths.map { path ->
            val file = permitted(path)
            require(file != null && file.isFile && file.canRead() && request.accepts(mimeType(file))) {
                "Pasirinkti failai nepasiekiami arba neatitinka prašomo tipo"
            }
            file
        }
        require(files.distinct().size == files.size) { "Failų paruošti nepavyko" }
        return files
    }
}
