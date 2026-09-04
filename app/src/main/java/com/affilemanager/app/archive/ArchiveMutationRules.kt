package com.affilemanager.app.archive

import java.util.Locale

internal data class ArchiveRenamePlan(
    val sourcePath: String,
    val targetPath: String,
    val renamedHeaders: Map<String, String>,
)

/** Pure, bounded planning rules used before any archive is rewritten. */
internal object ArchiveMutationRules {
    private const val MAX_SELECTED_PATHS = 10_000
    private const val MAX_NAME_CHARACTERS = 255

    fun extractionBaseName(archiveName: String, fallback: String): String {
        val name = archiveName.trim()
        val lower = name.lowercase(Locale.ROOT)
        val withoutSuffix = when {
            lower.endsWith(".tar.gz") -> name.dropLast(7)
            lower.endsWith(".tgz") -> name.dropLast(4)
            '.' in name -> name.substringBeforeLast('.')
            else -> name
        }
        return withoutSuffix.ifBlank { fallback.ifBlank { "extracted" } }
    }

    fun deletionHeaders(
        entries: List<ArchiveEntryInfo>,
        selectedPaths: Collection<String>,
    ): List<String> {
        val selected = normalizeSelection(selectedPaths)
        val affected = entries.asSequence()
            .map(ArchiveEntryInfo::name)
            .filter { original ->
                val normalized = normalizePath(original)
                selected.any { selectedPath -> isAtOrBelow(normalized, selectedPath) }
            }
            .distinct()
            .toList()
        require(affected.isNotEmpty()) { "Pasirinktų archyvo įrašų neberasta" }
        return affected
    }

    fun renamePlan(
        entries: List<ArchiveEntryInfo>,
        sourcePath: String,
        requestedName: String,
    ): ArchiveRenamePlan {
        val source = normalizePath(sourcePath)
        val newName = validateLeafName(requestedName)
        val parent = source.substringBeforeLast('/', missingDelimiterValue = "")
        val target = if (parent.isEmpty()) newName else "$parent/$newName"
        require(target != source) { "Pavadinimas nepasikeitė" }

        val normalizedEntries = entries.map { entry -> entry.name to normalizePath(entry.name) }
        val affected = normalizedEntries.filter { (_, normalized) -> isAtOrBelow(normalized, source) }
        require(affected.isNotEmpty()) { "Archyvo įrašas neberastas" }

        val sourceIsDirectory = affected.any { (_, normalized) -> normalized != source } ||
            entries.any { it.directory && normalizePath(it.name) == source }
        if (!sourceIsDirectory) {
            require(affected.size == 1 && affected.single().second == source) { "Archyvo įrašo tipas neaiškus" }
        }

        val affectedNormalized = affected.mapTo(hashSetOf()) { it.second }
        val renamed = linkedMapOf<String, String>()
        affected.forEach { (original, normalized) ->
            val suffix = normalized.removePrefix(source)
            val renamedPath = target + suffix
            renamed[original] = if (original.replace('\\', '/').endsWith('/')) "$renamedPath/" else renamedPath
        }
        val targetNormalized = renamed.values.map(::normalizePath)
        require(targetNormalized.distinct().size == targetNormalized.size) { "Nauji archyvo keliai kartojasi" }
        val unaffected = normalizedEntries.map { it.second }.filterNot(affectedNormalized::contains).toSet()
        require(targetNormalized.none(unaffected::contains)) { "Toks archyvo įrašas jau yra" }
        return ArchiveRenamePlan(source, target, renamed)
    }

    fun normalizeSelection(paths: Collection<String>): List<String> {
        require(paths.isNotEmpty()) { "Nepasirinkta archyvo įrašų" }
        require(paths.size <= MAX_SELECTED_PATHS) { "Pasirinkta per daug archyvo įrašų" }
        val ordered = paths.map(::normalizePath).distinct().sortedBy(String::length)
        return ordered.filter { candidate ->
            ordered.none { other -> other != candidate && other.length < candidate.length && isAtOrBelow(candidate, other) }
        }
    }

    fun normalizePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && '\u0000' !in normalized) { "Netinkamas archyvo kelias" }
        val segments = normalized.split('/')
        require(segments.size <= 64 && segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Nesaugus archyvo kelias"
        }
        return segments.joinToString("/")
    }

    private fun validateLeafName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_CHARACTERS) { "Netinkamas archyvo įrašo pavadinimo ilgis" }
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) {
            "Archyvo įrašo pavadinime yra neleistinų ženklų"
        }
        return name
    }

    private fun isAtOrBelow(path: String, root: String): Boolean = path == root || path.startsWith("$root/")
}
