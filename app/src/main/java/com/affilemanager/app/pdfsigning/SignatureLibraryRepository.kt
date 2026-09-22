package com.affilemanager.app.pdfsigning

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SavedSignature(
    val id: String,
    val name: String,
    val drawing: SignatureDrawing,
    val createdAtMillis: Long,
)

/** Persists only bounded vector drawings inside AF File Manager's private files directory. */
class SignatureLibraryRepository(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_SIGNATURES = 20
        const val MAX_NAME_LENGTH = 60
        private const val SCHEMA_VERSION = 1
        private const val MAX_FILE_BYTES = 4L * 1_024 * 1_024
        private val SAFE_ID = Regex("[0-9a-fA-F-]{36}")
    }

    private val storageFile = File(directory, "signature_library_v1.json")

    @Synchronized
    fun load(): List<SavedSignature> {
        if (!storageFile.exists()) return emptyList()
        require(storageFile.isFile && storageFile.length() in 1..MAX_FILE_BYTES) {
            "Parašų bibliotekos failo dydis netinkamas"
        }
        val root = JSONObject(storageFile.readText(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Parašų bibliotekos versija nepalaikoma" }
        val records = root.getJSONArray("signatures")
        require(records.length() <= MAX_SIGNATURES) { "Parašų bibliotekos riba viršyta" }
        val parsed = List(records.length()) { index -> parseSignature(records.getJSONObject(index)) }
        require(parsed.map(SavedSignature::id).distinct().size == parsed.size) { "Parašų bibliotekoje kartojasi identifikatoriai" }
        return parsed.sortedByDescending(SavedSignature::createdAtMillis)
    }

    @Synchronized
    fun save(name: String, drawing: SignatureDrawing): List<SavedSignature> {
        val current = load()
        require(current.size < MAX_SIGNATURES) { "Parašų bibliotekos riba viršyta" }
        val record = SavedSignature(
            id = UUID.randomUUID().toString(),
            name = normalizeName(name),
            drawing = VisualSignatureRules.validate(drawing),
            createdAtMillis = clock().coerceAtLeast(0L),
        )
        return (listOf(record) + current).also(::write)
    }

    @Synchronized
    fun delete(id: String): List<SavedSignature> {
        require(SAFE_ID.matches(id)) { "Parašo identifikatorius netinkamas" }
        val current = load()
        val remaining = current.filterNot { it.id == id }
        require(remaining.size != current.size) { "Išsaugotas parašas neberastas" }
        write(remaining)
        return remaining
    }

    private fun parseSignature(json: JSONObject): SavedSignature {
        val id = json.getString("id")
        require(SAFE_ID.matches(id)) { "Parašo identifikatorius netinkamas" }
        val strokesJson = json.getJSONArray("strokes")
        require(strokesJson.length() in 1..VisualSignatureRules.MAX_STROKES) { "Paraše per daug brūkšnių" }
        var totalPoints = 0
        val strokes = List(strokesJson.length()) { strokeIndex ->
            val pointsJson = strokesJson.getJSONArray(strokeIndex)
            require(pointsJson.length() in 1..VisualSignatureRules.MAX_POINTS) { "Paraše yra netinkamas brūkšnys" }
            totalPoints += pointsJson.length()
            require(totalPoints <= VisualSignatureRules.MAX_POINTS) { "Paraše per daug taškų" }
            SignatureStroke(
                List(pointsJson.length()) { pointIndex ->
                    val pointJson = pointsJson.getJSONArray(pointIndex)
                    require(pointJson.length() == 2) { "Parašo taškas netinkamas" }
                    val x = pointJson.getDouble(0).toFloat()
                    val y = pointJson.getDouble(1).toFloat()
                    require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) { "Parašo taškas netinkamas" }
                    SignaturePoint(x, y)
                },
            )
        }
        return SavedSignature(
            id = id,
            name = normalizeName(json.getString("name")),
            drawing = VisualSignatureRules.validate(SignatureDrawing(strokes)),
            createdAtMillis = json.getLong("createdAtMillis").also {
                require(it >= 0L) { "Parašo data netinkama" }
            },
        )
    }

    private fun write(records: List<SavedSignature>) {
        require(records.size <= MAX_SIGNATURES) { "Parašų bibliotekos riba viršyta" }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "signatures",
                JSONArray().apply {
                    records.forEach { signature ->
                        put(
                            JSONObject()
                                .put("id", signature.id)
                                .put("name", normalizeName(signature.name))
                                .put("createdAtMillis", signature.createdAtMillis)
                                .put(
                                    "strokes",
                                    JSONArray().apply {
                                        VisualSignatureRules.validate(signature.drawing).strokes.forEach { stroke ->
                                            put(
                                                JSONArray().apply {
                                                    stroke.points.forEach { point -> put(JSONArray().put(point.x).put(point.y)) }
                                                },
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(payload.size.toLong() in 1..MAX_FILE_BYTES) { "Parašų biblioteka per didelė" }
        require(directory.isDirectory || directory.mkdirs()) { "Parašų bibliotekos katalogo sukurti nepavyko" }
        val temporary = File(directory, ".${storageFile.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    storageFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            payload.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun normalizeName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "Parašo pavadinimo ilgis netinkamas" }
        require(name.none { it == '\u0000' || it.isISOControl() }) { "Parašo pavadinime yra neleistinų ženklų" }
        return name
    }
}
