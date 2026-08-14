package com.affilemanager.app.data

import com.affilemanager.app.core.FileSystemRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class FileTagDefinition(
    val name: String,
    val colorArgb: Int,
)

data class TaggedFileRecord(
    val path: String,
    val tags: Set<String>,
    val rating: Int,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun currentlyMatchesFile(): Boolean {
        val file = File(path)
        return file.exists() && (file.isDirectory || file.length() == sizeBytes && file.lastModified() == modifiedAtMillis)
    }
}

data class FileTagSnapshot(
    val schemaVersion: Int = 1,
    val definitions: List<FileTagDefinition> = emptyList(),
    val records: List<TaggedFileRecord> = emptyList(),
)

class FileTagRepository(private val storageFile: File) {
    companion object {
        const val MAX_RECORDS = 20_000
        const val MAX_DEFINITIONS = 500
        const val MAX_TAGS_PER_FILE = 32
        private const val MAX_FILE_BYTES = 8L * 1_024 * 1_024
        private const val MAX_PATH_LENGTH = 4_096
        private const val MAX_TAG_NAME_LENGTH = 80

        fun forApp(context: android.content.Context): FileTagRepository = FileTagRepository(File(context.filesDir, "file_tags_v1.json"))
    }

    @Synchronized
    fun snapshot(): FileTagSnapshot {
        if (!storageFile.isFile) return FileTagSnapshot()
        return runCatching {
            require(storageFile.length() in 1..MAX_FILE_BYTES) { "Žymų duomenų failo dydis netinkamas" }
            parse(JSONObject(storageFile.readText(Charsets.UTF_8)))
        }.getOrElse { error ->
            val quarantine = File(
                storageFile.parentFile,
                "${storageFile.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.json",
            )
            runCatching { Files.move(storageFile.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            throw IllegalStateException("Žymų duomenys sugadinti ir izoliuoti", error)
        }
    }

    @Synchronized
    fun apply(paths: List<String>, tags: Set<String>, rating: Int?, newTagColorArgb: Int = 0xff1976d2.toInt()): FileTagSnapshot {
        require(paths.isNotEmpty()) { "Nepasirinkta failų" }
        require(paths.size <= 10_000) { "Vienu metu galima keisti iki 10 000 failų žymų" }
        require(tags.isNotEmpty() || rating != null) { "Nepasirinkta žymų ar įvertinimo" }
        val normalizedTags = tags.map(::validateTagName).toSet()
        require(normalizedTags.size <= MAX_TAGS_PER_FILE) { "Vienam failui žymų per daug" }
        require(rating == null || rating in 0..5) { "Įvertinimas turi būti nuo 0 iki 5" }
        val current = snapshot()
        val definitions = current.definitions.associateBy(FileTagDefinition::name).toMutableMap()
        normalizedTags.forEach { tag ->
            if (tag !in definitions) {
                require(definitions.size < MAX_DEFINITIONS) { "Žymų žodyno riba viršyta" }
                definitions[tag] = FileTagDefinition(tag, newTagColorArgb)
            }
        }
        val records = current.records.associateBy(TaggedFileRecord::path).toMutableMap()
        paths.distinct().forEach { rawPath ->
            val file = File(rawPath).canonicalFile
            require(file.exists()) { "Failas nebeegzistuoja: ${file.name}" }
            validatePath(file.canonicalPath)
            val existing = records[file.canonicalPath]
            val combined = (existing?.tags.orEmpty() + normalizedTags)
            require(combined.size <= MAX_TAGS_PER_FILE) { "Failui ${file.name} žymų riba viršyta" }
            if (existing == null) require(records.size < MAX_RECORDS) { "Pažymėtų failų riba viršyta" }
            records[file.canonicalPath] = TaggedFileRecord(
                path = file.canonicalPath,
                tags = combined,
                rating = rating ?: existing?.rating ?: 0,
                sizeBytes = if (file.isFile) file.length().coerceAtLeast(0) else 0,
                modifiedAtMillis = file.lastModified().coerceAtLeast(0),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        return FileTagSnapshot(
            definitions = definitions.values.sortedBy { it.name.lowercase() },
            records = records.values.sortedBy(TaggedFileRecord::path),
        ).also(::write)
    }

    @Synchronized
    fun clear(paths: List<String>): FileTagSnapshot {
        val canonical = paths.map { File(it).canonicalPath }.toSet()
        val current = snapshot()
        return current.copy(records = current.records.filterNot { it.path in canonical }).also(::write)
    }

    fun pathsWithAll(tags: Set<String>): Set<String> {
        val normalized = tags.map(::validateTagName).toSet()
        if (normalized.isEmpty()) return emptySet()
        return snapshot().records.filter { it.tags.containsAll(normalized) }.mapTo(linkedSetOf(), TaggedFileRecord::path)
    }

    @Synchronized
    fun exportTo(destinationDirectory: File): File {
        val destination = destinationDirectory.canonicalFile
        require(destination.isDirectory && destination.canWrite()) { "Eksporto katalogas nepasiekiamas" }
        val target = FileSystemRules.keepBothTarget(File(destination, "af-file-manager-tags.json"))
        atomicWrite(target, snapshot().toJson().toString())
        return target
    }

    @Synchronized
    fun importFrom(source: File): FileTagSnapshot {
        val file = source.canonicalFile
        require(file.isFile && file.canRead()) { "Žymų importo failas nepasiekiamas" }
        require(file.length() in 1..MAX_FILE_BYTES) { "Žymų importo failo dydis netinkamas" }
        val incoming = parse(JSONObject(file.readText(Charsets.UTF_8)))
        val current = snapshot()
        val definitions = (current.definitions + incoming.definitions).associateBy(FileTagDefinition::name)
        val records = (current.records + incoming.records).associateBy(TaggedFileRecord::path)
        require(definitions.size <= MAX_DEFINITIONS && records.size <= MAX_RECORDS) { "Importas viršija žymų ribas" }
        return FileTagSnapshot(
            definitions = definitions.values.sortedBy { it.name.lowercase() },
            records = records.values.sortedBy(TaggedFileRecord::path),
        ).also(::write)
    }

    private fun parse(json: JSONObject): FileTagSnapshot {
        require(json.getInt("schemaVersion") == 1) { "Nepalaikoma žymų formato versija" }
        val definitionsJson = json.getJSONArray("definitions")
        val recordsJson = json.getJSONArray("records")
        require(definitionsJson.length() <= MAX_DEFINITIONS && recordsJson.length() <= MAX_RECORDS) { "Žymų duomenų riba viršyta" }
        val definitions = (0 until definitionsJson.length()).map { index ->
            val item = definitionsJson.getJSONObject(index)
            FileTagDefinition(validateTagName(item.getString("name")), item.getInt("colorArgb"))
        }
        require(definitions.map(FileTagDefinition::name).distinct().size == definitions.size) { "Žymų pavadinimai kartojasi" }
        val known = definitions.map(FileTagDefinition::name).toSet()
        val records = (0 until recordsJson.length()).map { index ->
            val item = recordsJson.getJSONObject(index)
            val tagsJson = item.getJSONArray("tags")
            require(tagsJson.length() <= MAX_TAGS_PER_FILE) { "Vieno failo žymų riba viršyta" }
            val tags = (0 until tagsJson.length()).map { validateTagName(tagsJson.getString(it)) }.toSet()
            require(tags.all { it in known }) { "Failas nurodo nežinomą žymą" }
            TaggedFileRecord(
                path = item.getString("path").also(::validatePath),
                tags = tags,
                rating = item.getInt("rating").also { require(it in 0..5) },
                sizeBytes = item.getLong("sizeBytes").also { require(it >= 0) },
                modifiedAtMillis = item.getLong("modifiedAtMillis").also { require(it >= 0) },
                updatedAtMillis = item.getLong("updatedAtMillis").also { require(it >= 0) },
            )
        }
        require(records.map(TaggedFileRecord::path).distinct().size == records.size) { "Failų žymų įrašai kartojasi" }
        return FileTagSnapshot(definitions = definitions, records = records)
    }

    private fun write(snapshot: FileTagSnapshot) {
        require(snapshot.definitions.size <= MAX_DEFINITIONS && snapshot.records.size <= MAX_RECORDS) { "Žymų duomenų riba viršyta" }
        atomicWrite(storageFile, snapshot.toJson().toString())
    }

    private fun FileTagSnapshot.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("definitions", JSONArray().apply {
            definitions.sortedBy { it.name.lowercase() }.forEach { definition ->
                put(JSONObject().put("name", definition.name).put("colorArgb", definition.colorArgb))
            }
        })
        .put("records", JSONArray().apply {
            records.sortedBy(TaggedFileRecord::path).forEach { record ->
                put(
                    JSONObject()
                        .put("path", record.path)
                        .put("tags", JSONArray().apply { record.tags.sorted().forEach(::put) })
                        .put("rating", record.rating)
                        .put("sizeBytes", record.sizeBytes)
                        .put("modifiedAtMillis", record.modifiedAtMillis)
                        .put("updatedAtMillis", record.updatedAtMillis),
                )
            }
        })

    private fun atomicWrite(target: File, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_FILE_BYTES) { "Žymų duomenys per dideli" }
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Žymų katalogo sukurti nepavyko" } }
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            runCatching {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            bytes.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validateTagName(raw: String): String {
        val tag = raw.trim()
        require(tag.isNotEmpty() && tag.length <= MAX_TAG_NAME_LENGTH) { "Žymos pavadinimo ilgis netinkamas" }
        require(tag.none { it == '\u0000' || it.isISOControl() }) { "Žymos pavadinime yra neleistinų ženklų" }
        require(tag.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Hierarchinė žyma netinkama" }
        return tag
    }

    private fun validatePath(path: String) = require(path.isNotBlank() && path.length <= MAX_PATH_LENGTH && '\u0000' !in path) { "Failo kelias netinkamas" }
}
