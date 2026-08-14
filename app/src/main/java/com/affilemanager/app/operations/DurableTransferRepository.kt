package com.affilemanager.app.operations

import android.content.Context
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DurableTransferRecord(
    val plan: DurableTransferPlan,
    val state: DurableTransferState,
)

class DurableTransferRepository(context: Context) : DurableTransferStateWriter {
    companion object {
        private const val MAX_HISTORY = 64
        private const val MAX_PLAN_BYTES = 32L * 1_024 * 1_024
        private const val MAX_STATE_BYTES = 1L * 1_024 * 1_024
        private const val MAX_PATH_LENGTH = 4_096
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,80}")
    }

    private val directory = File(context.filesDir, "durable_operations_v1").apply {
        require(isDirectory || mkdirs()) { "Operacijų saugyklos sukurti nepavyko" }
    }
    private val corruptDirectory = File(directory, "corrupt").apply {
        require(isDirectory || mkdirs()) { "Sugadintų operacijų karantino sukurti nepavyko" }
    }

    @Synchronized
    fun create(plan: DurableTransferPlan): DurableTransferState {
        validatePlan(plan)
        val planFile = planFile(plan.id)
        val stateFile = stateFile(plan.id)
        require(!planFile.exists() && !stateFile.exists()) { "Tokia operacija jau egzistuoja" }
        val state = DurableTransferState(planId = plan.id)
        atomicWrite(planFile, plan.toJson().toString(), MAX_PLAN_BYTES)
        try {
            atomicWrite(stateFile, state.toJson().toString(), MAX_STATE_BYTES)
        } catch (error: Throwable) {
            planFile.delete()
            throw error
        }
        pruneHistory()
        return state
    }

    @Synchronized
    override fun saveState(state: DurableTransferState) {
        validateState(state)
        require(planFile(state.planId).isFile) { "Operacijos planas neberastas" }
        atomicWrite(stateFile(state.planId), state.toJson().toString(), MAX_STATE_BYTES)
    }

    @Synchronized
    fun load(id: String): DurableTransferRecord {
        validateId(id)
        val plan = readPlan(planFile(id))
        val state = readState(stateFile(id))
        require(plan.id == state.planId) { "Plano ir būsenos tapatybės nesutampa" }
        return DurableTransferRecord(plan, state)
    }

    @Synchronized
    fun list(): List<DurableTransferRecord> {
        val states = directory.listFiles { file -> file.isFile && file.name.endsWith(".state.json") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
            .take(MAX_HISTORY * 2)
        val result = ArrayList<DurableTransferRecord>()
        states.forEach { statePath ->
            val id = statePath.name.removeSuffix(".state.json")
            if (!SAFE_ID.matches(id)) {
                quarantine(statePath)
                return@forEach
            }
            runCatching { load(id) }
                .onSuccess { result += it }
                .onFailure {
                    quarantine(statePath)
                    quarantine(planFile(id))
                }
        }
        return result.sortedByDescending { it.state.updatedAtMillis }.take(MAX_HISTORY)
    }

    private fun readPlan(file: File): DurableTransferPlan {
        val json = JSONObject(readLimited(file, MAX_PLAN_BYTES))
        require(json.getInt("schemaVersion") == 1) { "Nepalaikoma operacijos plano versija" }
        val itemsJson = json.getJSONArray("items")
        require(itemsJson.length() in 1..DurableTransferPlanner.MAX_PLAN_ITEMS) { "Netinkamas plano elementų skaičius" }
        val rootsJson = json.getJSONArray("sourceRoots")
        require(rootsJson.length() in 1..DurableTransferPlanner.MAX_SOURCE_PATHS) { "Netinkamas pradinių kelių skaičius" }
        val plan = DurableTransferPlan(
            schemaVersion = 1,
            id = json.getString("id"),
            createdAtMillis = json.getLong("createdAtMillis"),
            destinationPath = json.getString("destinationPath"),
            sourceRoots = (0 until rootsJson.length()).map(rootsJson::getString),
            move = json.getBoolean("move"),
            conflictPolicy = ConflictPolicy.valueOf(json.getString("conflictPolicy")),
            verification = TransferVerification.valueOf(json.getString("verification")),
            failurePolicy = TransferFailurePolicy.valueOf(json.getString("failurePolicy")),
            items = (0 until itemsJson.length()).map { index ->
                val item = itemsJson.getJSONObject(index)
                PlannedTransferItem(
                    index = item.getInt("index"),
                    sourceRootIndex = item.getInt("sourceRootIndex"),
                    sourcePath = item.getString("sourcePath"),
                    targetPath = item.getString("targetPath"),
                    directory = item.getBoolean("directory"),
                    sizeBytes = item.getLong("sizeBytes"),
                    modifiedAtMillis = item.getLong("modifiedAtMillis"),
                    replaceExisting = item.getBoolean("replaceExisting"),
                )
            },
            totalBytes = json.getLong("totalBytes"),
        )
        validatePlan(plan)
        return plan
    }

    private fun readState(file: File): DurableTransferState {
        val json = JSONObject(readLimited(file, MAX_STATE_BYTES))
        require(json.getInt("schemaVersion") == 1) { "Nepalaikoma operacijos būsenos versija" }
        return DurableTransferState(
            schemaVersion = 1,
            planId = json.getString("planId"),
            status = DurableTransferStatus.valueOf(json.getString("status")),
            phase = TransferPhase.valueOf(json.getString("phase")),
            nextItemIndex = json.getInt("nextItemIndex"),
            nextDeleteRootIndex = json.getInt("nextDeleteRootIndex"),
            nextFinalizeItemIndex = json.getInt("nextFinalizeItemIndex"),
            failedItemIndices = json.getJSONArray("failedItemIndices").toIntList(),
            retryItemIndices = json.optJSONArray("retryItemIndices")?.toIntList().orEmpty(),
            retryPosition = json.optInt("retryPosition", 0),
            attempt = json.getInt("attempt"),
            lastMessage = if (json.isNull("lastMessage")) null else json.getString("lastMessage").takeIf(String::isNotBlank),
            updatedAtMillis = json.getLong("updatedAtMillis"),
        ).also(::validateState)
    }

    private fun validatePlan(plan: DurableTransferPlan) {
        require(plan.schemaVersion == 1) { "Nepalaikoma plano versija" }
        validateId(plan.id)
        validatePath(plan.destinationPath)
        require(plan.sourceRoots.size in 1..DurableTransferPlanner.MAX_SOURCE_PATHS) { "Pradinių kelių riba viršyta" }
        plan.sourceRoots.forEach(::validatePath)
        require(plan.items.size in 1..DurableTransferPlanner.MAX_PLAN_ITEMS) { "Plano elementų riba viršyta" }
        require(plan.totalBytes >= 0) { "Netinkamas bendras dydis" }
        require(!plan.move || plan.failurePolicy == TransferFailurePolicy.STOP) { "Nesaugi perkėlimo klaidų politika" }
        val destination = File(plan.destinationPath).canonicalFile
        plan.items.forEachIndexed { expectedIndex, item ->
            require(item.index == expectedIndex) { "Plano indeksai nėra nuoseklūs" }
            require(item.sourceRootIndex in plan.sourceRoots.indices) { "Netinkama šaltinio šaknis" }
            validatePath(item.sourcePath)
            validatePath(item.targetPath)
            require(FileSystemRules.isContained(destination, File(item.targetPath))) { "Tikslas išeina už paskirties katalogo" }
            require(item.sizeBytes >= 0 && item.modifiedAtMillis >= 0) { "Netinkami failo metaduomenys" }
        }
    }

    private fun validateState(state: DurableTransferState) {
        require(state.schemaVersion == 1) { "Nepalaikoma būsenos versija" }
        validateId(state.planId)
        require(state.nextItemIndex >= 0 && state.nextDeleteRootIndex >= 0 && state.nextFinalizeItemIndex >= 0) {
            "Netinkamas kontrolinis taškas"
        }
        require(state.failedItemIndices.size <= 100 && state.retryItemIndices.size <= 100) { "Klaidų sąrašo riba viršyta" }
        require(state.retryPosition in 0..state.retryItemIndices.size) { "Netinkama kartojimo padėtis" }
        require(state.attempt in 0..1_000) { "Bandymų riba viršyta" }
        require(state.lastMessage == null || state.lastMessage.length <= 500) { "Klaidos pranešimas per ilgas" }
    }

    private fun DurableTransferPlan.toJson(): JSONObject {
        val roots = JSONArray().apply { sourceRoots.forEach(::put) }
        val itemArray = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("index", item.index)
                        .put("sourceRootIndex", item.sourceRootIndex)
                        .put("sourcePath", item.sourcePath)
                        .put("targetPath", item.targetPath)
                        .put("directory", item.directory)
                        .put("sizeBytes", item.sizeBytes)
                        .put("modifiedAtMillis", item.modifiedAtMillis)
                        .put("replaceExisting", item.replaceExisting),
                )
            }
        }
        return JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("id", id)
            .put("createdAtMillis", createdAtMillis)
            .put("destinationPath", destinationPath)
            .put("sourceRoots", roots)
            .put("move", move)
            .put("conflictPolicy", conflictPolicy.name)
            .put("verification", verification.name)
            .put("failurePolicy", failurePolicy.name)
            .put("items", itemArray)
            .put("totalBytes", totalBytes)
    }

    private fun DurableTransferState.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("planId", planId)
        .put("status", status.name)
        .put("phase", phase.name)
        .put("nextItemIndex", nextItemIndex)
        .put("nextDeleteRootIndex", nextDeleteRootIndex)
        .put("nextFinalizeItemIndex", nextFinalizeItemIndex)
        .put("failedItemIndices", JSONArray().apply { failedItemIndices.forEach(::put) })
        .put("retryItemIndices", JSONArray().apply { retryItemIndices.forEach(::put) })
        .put("retryPosition", retryPosition)
        .put("attempt", attempt)
        .put("lastMessage", lastMessage ?: JSONObject.NULL)
        .put("updatedAtMillis", updatedAtMillis)

    private fun readLimited(file: File, maxBytes: Long): String {
        require(file.isFile) { "Operacijos metaduomenys nerasti" }
        require(file.length() in 1..maxBytes) { "Operacijos metaduomenų dydis netinkamas" }
        return file.readText(Charsets.UTF_8)
    }

    private fun atomicWrite(target: File, value: String, maxBytes: Long) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..maxBytes) { "Operacijos metaduomenys viršija ribą" }
        val temporary = File(directory, ".${target.name}.tmp")
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

    private fun pruneHistory() {
        val stateFiles = directory.listFiles { file -> file.isFile && file.name.endsWith(".state.json") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        stateFiles.drop(MAX_HISTORY).forEach { file ->
            val id = file.name.removeSuffix(".state.json")
            val status = runCatching { readState(file).status }.getOrNull()
            if (status in setOf(
                    DurableTransferStatus.COMPLETED,
                    DurableTransferStatus.COMPLETED_WITH_ERRORS,
                    DurableTransferStatus.CANCELLED,
                )
            ) {
                planFile(id).delete()
                file.delete()
            }
        }
    }

    private fun quarantine(file: File) {
        if (!file.exists() || file.parentFile != directory) return
        val target = File(corruptDirectory, "${System.currentTimeMillis()}-${file.name}.corrupt")
        file.renameTo(target)
        corruptDirectory.listFiles()?.sortedByDescending(File::lastModified)?.drop(8)?.forEach(File::delete)
    }

    private fun planFile(id: String): File = File(directory, "${validatedId(id)}.plan.json")
    private fun stateFile(id: String): File = File(directory, "${validatedId(id)}.state.json")
    private fun validatedId(id: String): String = id.also(::validateId)
    private fun validateId(id: String) = require(SAFE_ID.matches(id)) { "Netinkama operacijos tapatybė" }
    private fun validatePath(path: String) = require(path.isNotBlank() && path.length <= MAX_PATH_LENGTH && '\u0000' !in path) { "Netinkamas kelias" }
    private fun JSONArray.toIntList(): List<Int> = (0 until length()).map(::getInt)
}
