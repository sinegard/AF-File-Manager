package com.affilemanager.app.workflow

import android.content.Context
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private class UnsupportedAfSchema(message: String) : IllegalStateException(message)

internal class AfAtomicJsonStore(rootDirectory: File) {
    val root = File(rootDirectory, "af_workflows_v1").apply {
        require(isDirectory || mkdirs()) { "Could not create AF workflow storage" }
    }
    private val corrupt = File(root, "corrupt").apply {
        require(isDirectory || mkdirs()) { "Could not create AF workflow quarantine" }
    }

    @Synchronized
    fun read(file: File, maximumBytes: Long): String {
        require(file.isFile) { "AF workflow metadata is missing" }
        require(file.length() in 1..maximumBytes) { "AF workflow metadata size is invalid" }
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun write(file: File, text: String, maximumBytes: Long) {
        require(file.parentFile == root) { "AF workflow metadata escaped its storage" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..maximumBytes) { "AF workflow metadata is too large" }
        val temporary = File(root, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            bytes.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    fun quarantine(file: File) {
        if (!file.isFile || file.parentFile != root) return
        val target = File(corrupt, "${System.currentTimeMillis()}-${file.name}.corrupt")
        if (!file.renameTo(target)) return
        corrupt.listFiles()?.sortedByDescending(File::lastModified)?.drop(16)?.forEach(File::delete)
    }
}

class AfPlanRepository private constructor(private val store: AfAtomicJsonStore) {
    constructor(context: Context) : this(AfAtomicJsonStore(context.filesDir))
    internal constructor(rootDirectory: File) : this(AfAtomicJsonStore(rootDirectory))

    private val file = File(store.root, "plans.json")

    @Synchronized
    fun list(): List<AfPlanDefinition> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(store.read(file, AfWorkflowLimits.MAX_METADATA_BYTES))
            requireSchema(root, 1, "AF Plan collection")
            val plans = root.getJSONArray("plans")
            require(plans.length() <= AfWorkflowLimits.MAX_SAVED_PLANS) { "Too many saved AF Plans" }
            (0 until plans.length()).map { index -> AfWorkflowJson.planFromJson(plans.getJSONObject(index)) }
                .sortedByDescending(AfPlanDefinition::updatedAtMillis)
        } catch (unsupported: UnsupportedAfSchema) {
            throw unsupported
        } catch (error: Throwable) {
            store.quarantine(file)
            emptyList()
        }
    }

    @Synchronized
    fun find(id: String): AfPlanDefinition? = list().firstOrNull { it.id == id }

    @Synchronized
    fun save(plan: AfPlanDefinition): AfPlanDefinition {
        val normalized = plan.normalized()
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        if (index >= 0) current[index] = normalized else current.add(0, normalized)
        require(current.size <= AfWorkflowLimits.MAX_SAVED_PLANS) { "Saved AF Plan limit reached" }
        write(current)
        return normalized
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = list()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        write(updated)
        return true
    }

    private fun write(plans: List<AfPlanDefinition>) {
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("plans", JSONArray().apply { plans.forEach { put(AfWorkflowJson.planToJson(it)) } })
        store.write(file, root.toString(), AfWorkflowLimits.MAX_METADATA_BYTES)
    }
}

data class AfExecutionRecord(
    val preflight: AfPreflightSummary,
    val state: AfExecutionState,
    val journal: List<AfReceiptItem>,
)

class AfExecutionRepository private constructor(private val store: AfAtomicJsonStore) {
    constructor(context: Context) : this(AfAtomicJsonStore(context.filesDir))
    internal constructor(rootDirectory: File) : this(AfAtomicJsonStore(rootDirectory))

    @Synchronized
    fun create(preflight: AfPreflightSummary): AfExecutionState {
        require(preflight.canRun) { "AF Plan preview contains blockers" }
        validateId(preflight.executionId)
        val previewFile = previewFile(preflight.executionId)
        val stateFile = stateFile(preflight.executionId)
        require(!previewFile.exists() && !stateFile.exists()) { "AF execution already exists" }
        val state = AfExecutionState(executionId = preflight.executionId)
        store.write(previewFile, AfWorkflowJson.preflightToJson(preflight).toString(), AfWorkflowLimits.MAX_METADATA_BYTES)
        try {
            saveJournal(preflight.executionId, emptyList())
            save(state)
        } catch (error: Throwable) {
            previewFile.delete()
            journalFile(preflight.executionId).delete()
            throw error
        }
        prune()
        return state
    }

    @Synchronized
    fun save(state: AfExecutionState) {
        validateState(state)
        require(previewFile(state.executionId).isFile) { "AF execution preview is missing" }
        store.write(stateFile(state.executionId), AfWorkflowJson.executionStateToJson(state).toString(), 1L * 1_024 * 1_024)
    }

    @Synchronized
    fun saveJournal(executionId: String, items: List<AfReceiptItem>) {
        validateId(executionId)
        require(items.size <= AfWorkflowLimits.MAX_RECEIPT_ITEMS) { "AF execution journal limit exceeded" }
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("executionId", executionId)
            .put("items", JSONArray().apply { items.forEach { put(AfWorkflowJson.receiptItemToJson(it)) } })
        store.write(journalFile(executionId), root.toString(), AfWorkflowLimits.MAX_RECEIPT_BYTES)
    }

    @Synchronized
    fun loadJournal(executionId: String): List<AfReceiptItem> {
        validateId(executionId)
        val path = journalFile(executionId)
        if (!path.isFile) return emptyList()
        val root = JSONObject(store.read(path, AfWorkflowLimits.MAX_RECEIPT_BYTES))
        requireSchema(root, 1, "AF execution journal")
        require(root.getString("executionId") == executionId) { "AF execution journal identity mismatch" }
        val items = root.getJSONArray("items")
        require(items.length() <= AfWorkflowLimits.MAX_RECEIPT_ITEMS) { "AF execution journal limit exceeded" }
        return (0 until items.length()).map { AfWorkflowJson.receiptItemFromJson(items.getJSONObject(it)) }
    }

    @Synchronized
    fun load(id: String): AfExecutionRecord {
        validateId(id)
        val preflight = AfWorkflowJson.preflightFromJson(
            JSONObject(store.read(previewFile(id), AfWorkflowLimits.MAX_METADATA_BYTES)),
        )
        val state = AfWorkflowJson.executionStateFromJson(
            JSONObject(store.read(stateFile(id), 1L * 1_024 * 1_024)),
        )
        require(preflight.executionId == state.executionId) { "AF execution identity mismatch" }
        return AfExecutionRecord(preflight, state, loadJournal(id))
    }

    @Synchronized
    fun listRecoverable(): List<AfExecutionRecord> {
        val states = store.root.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".execution.json") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
            .take(64)
        return states.mapNotNull { statePath ->
            val id = statePath.name.removeSuffix(".execution.json")
            try {
                load(id)
            } catch (unsupported: UnsupportedAfSchema) {
                null
            } catch (_: Throwable) {
                store.quarantine(statePath)
                store.quarantine(previewFile(id))
                null
            }
        }.filter { it.state.status in setOf(AfExecutionStatus.QUEUED, AfExecutionStatus.RUNNING, AfExecutionStatus.PAUSED, AfExecutionStatus.INTERRUPTED) }
    }

    private fun prune() {
        val states = store.root.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".execution.json") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        states.drop(64).forEach { path ->
            val id = path.name.removeSuffix(".execution.json")
            val status = runCatching {
                AfWorkflowJson.executionStateFromJson(JSONObject(store.read(path, 1L * 1_024 * 1_024))).status
            }.getOrNull()
            if (status in TERMINAL_STATUSES) {
                path.delete()
                previewFile(id).delete()
                journalFile(id).delete()
            }
        }
    }

    private fun previewFile(id: String) = File(store.root, "${validatedId(id)}.preview.json")
    private fun stateFile(id: String) = File(store.root, "${validatedId(id)}.execution.json")
    private fun journalFile(id: String) = File(store.root, "${validatedId(id)}.journal.json")

    private fun validateState(state: AfExecutionState) {
        require(state.schemaVersion == 1) { "Unsupported AF execution state version" }
        validateId(state.executionId)
        require(state.nextProjectionIndex >= 0 && state.nextEntryIndex >= 0) { "Invalid AF execution checkpoint" }
        require(state.completedFiles >= 0 && state.completedBytes >= 0 && state.failedItems >= 0) { "Invalid AF execution progress" }
        require(state.errors.size <= AfWorkflowLimits.MAX_RECORDED_ERRORS) { "Too many AF execution errors" }
        require(state.errors.all { it.length <= 500 }) { "AF execution error is too long" }
        require(state.attempt in 0..1_000) { "Invalid AF execution attempt count" }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            AfExecutionStatus.COMPLETED,
            AfExecutionStatus.COMPLETED_WITH_ERRORS,
            AfExecutionStatus.FAILED,
            AfExecutionStatus.CANCELLED,
        )
    }
}

class AfTimelineRepository private constructor(private val store: AfAtomicJsonStore) {
    constructor(context: Context) : this(AfAtomicJsonStore(context.filesDir))
    internal constructor(rootDirectory: File) : this(AfAtomicJsonStore(rootDirectory))

    @Synchronized
    fun add(receipt: AfOperationReceipt) {
        validateReceipt(receipt)
        val path = receiptFile(receipt.id)
        store.write(path, AfWorkflowJson.receiptToJson(receipt).toString(), AfWorkflowLimits.MAX_RECEIPT_BYTES)
        prune()
    }

    @Synchronized
    fun get(id: String): AfOperationReceipt? {
        val path = receiptFile(id)
        if (!path.isFile) return null
        return try {
            AfWorkflowJson.receiptFromJson(JSONObject(store.read(path, AfWorkflowLimits.MAX_RECEIPT_BYTES)))
        } catch (unsupported: UnsupportedAfSchema) {
            throw unsupported
        } catch (_: Throwable) {
            store.quarantine(path)
            null
        }
    }

    @Synchronized
    fun list(): List<AfOperationReceipt> = store.root
        .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".receipt.json") }
        ?.sortedByDescending(File::lastModified)
        .orEmpty()
        .take(AfWorkflowLimits.MAX_TIMELINE_ENTRIES * 2)
        .mapNotNull { path -> get(path.name.removeSuffix(".receipt.json")) }
        .sortedByDescending(AfOperationReceipt::finishedAtMillis)
        .take(AfWorkflowLimits.MAX_TIMELINE_ENTRIES)

    fun findPath(query: String): List<AfOperationReceipt> {
        if (query.isBlank()) return emptyList()
        return AfTimelineSearch.trace(list(), query)
    }

    fun exportJson(receipt: AfOperationReceipt): String = AfWorkflowJson.receiptToJson(receipt).toString(2)

    /** Prevents a completed undo from being offered again if its original receipt is reopened. */
    @Synchronized
    fun markUndoConsumed(id: String): Boolean {
        val receipt = get(id) ?: return false
        if (!receipt.undoAvailable) return true
        store.write(
            receiptFile(id),
            AfWorkflowJson.receiptToJson(receipt.copy(undoAvailable = false)).toString(),
            AfWorkflowLimits.MAX_RECEIPT_BYTES,
        )
        return true
    }

    fun exportText(receipt: AfOperationReceipt): String = buildString {
        appendLine("AF File Manager operation receipt")
        appendLine("Operation: ${receipt.planName}")
        appendLine("ID: ${receipt.id}")
        appendLine("Status: ${receipt.status.name}")
        appendLine("Started: ${java.time.Instant.ofEpochMilli(receipt.startedAtMillis)}")
        appendLine("Finished: ${java.time.Instant.ofEpochMilli(receipt.finishedAtMillis)}")
        appendLine("Verification: ${receipt.verification.name}")
        appendLine("Sources deleted: ${receipt.sourcesDeleted}")
        appendLine("Errors: ${receipt.errorCount}")
        receipt.errors.forEach { appendLine("Error: $it") }
        appendLine()
        receipt.items.forEach { item ->
            append(item.status.name)
            append(" · ")
            append(item.source.displayLabel)
            item.destination?.let { destination -> append(" -> ${destination.displayLabel}") }
            append(" · ${item.sizeBytes} bytes")
            item.sha256?.let { append(" · SHA-256 $it") }
            item.errorCode?.let { append(" · $it") }
            appendLine()
        }
    }

    private fun prune() {
        store.root.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".receipt.json") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(AfWorkflowLimits.MAX_TIMELINE_ENTRIES)
            ?.forEach(File::delete)
    }

    private fun validateReceipt(receipt: AfOperationReceipt) {
        require(receipt.schemaVersion == 1) { "Unsupported AF receipt version" }
        validateId(receipt.id)
        validateId(receipt.planId)
        require(receipt.planName.isNotBlank() && receipt.planName.length <= AfWorkflowLimits.MAX_NAME_LENGTH) { "Invalid AF Plan name" }
        require(receipt.finishedAtMillis >= receipt.startedAtMillis) { "Invalid AF receipt time range" }
        require(receipt.items.size <= AfWorkflowLimits.MAX_RECEIPT_ITEMS) { "AF receipt item limit exceeded" }
        require(receipt.errorCount in 0..AfWorkflowLimits.MAX_RECORDED_ERRORS) { "Invalid AF receipt error count" }
        require(receipt.errors.size <= AfWorkflowLimits.MAX_RECORDED_ERRORS) { "AF receipt error list limit exceeded" }
    }

    private fun receiptFile(id: String) = File(store.root, "${validatedId(id)}.receipt.json")
}

class AfAutomationRepository private constructor(private val store: AfAtomicJsonStore) {
    constructor(context: Context) : this(AfAtomicJsonStore(context.filesDir))
    internal constructor(rootDirectory: File) : this(AfAtomicJsonStore(rootDirectory))

    private val file = File(store.root, "automation.json")

    @Synchronized
    fun list(): List<AfAutomationRule> {
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(store.read(file, 1L * 1_024 * 1_024))
            requireSchema(root, 1, "AF automation collection")
            val values = root.getJSONArray("rules")
            require(values.length() <= AfWorkflowLimits.MAX_AUTOMATION_RULES) { "Too many AF automation rules" }
            (0 until values.length()).map { AfWorkflowJson.automationFromJson(values.getJSONObject(it)) }
        } catch (unsupported: UnsupportedAfSchema) {
            throw unsupported
        } catch (_: Throwable) {
            store.quarantine(file)
            emptyList()
        }
    }

    @Synchronized
    fun save(rule: AfAutomationRule): AfAutomationRule {
        val normalized = rule.normalized()
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        if (index >= 0) current[index] = normalized else current += normalized
        require(current.size <= AfWorkflowLimits.MAX_AUTOMATION_RULES) { "AF automation rule limit reached" }
        write(current)
        return normalized
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = list()
        val updated = current.filterNot { it.id == id }
        if (current.size == updated.size) return false
        write(updated)
        return true
    }

    private fun write(rules: List<AfAutomationRule>) {
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("rules", JSONArray().apply { rules.forEach { put(AfWorkflowJson.automationToJson(it)) } })
        store.write(file, root.toString(), 1L * 1_024 * 1_024)
    }
}

private object AfWorkflowJson {
    fun locationToJson(value: AfLocationRef): JSONObject = JSONObject()
        .put("kind", value.kind.name)
        .put("path", value.path)
        .put("profileId", value.profileId ?: JSONObject.NULL)
        .put("profileName", value.profileName ?: JSONObject.NULL)

    fun locationFromJson(value: JSONObject): AfLocationRef = AfLocationRef(
        kind = AfLocationKind.valueOf(value.getString("kind")),
        path = value.getString("path"),
        profileId = value.stringOrNull("profileId"),
        profileName = value.stringOrNull("profileName"),
    ).normalized()

    fun sourceToJson(value: AfSourceRef): JSONObject = JSONObject()
        .put("location", locationToJson(value.location))
        .put("displayName", value.displayName)
        .put("kind", value.kind.name)
        .put("archiveEntryPath", value.archiveEntryPath ?: JSONObject.NULL)

    fun sourceFromJson(value: JSONObject): AfSourceRef = AfSourceRef(
        location = locationFromJson(value.getJSONObject("location")),
        displayName = value.getString("displayName"),
        kind = AfSourceKind.valueOf(value.getString("kind")),
        archiveEntryPath = value.stringOrNull("archiveEntryPath"),
    ).normalized()

    fun planToJson(value: AfPlanDefinition): JSONObject = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("id", value.id)
        .put("name", value.name)
        .put("createdAtMillis", value.createdAtMillis)
        .put("updatedAtMillis", value.updatedAtMillis)
        .put("sources", JSONArray().apply { value.sources.forEach { put(sourceToJson(it)) } })
        .put("destinations", JSONArray().apply {
            value.destinations.forEach { destination ->
                put(JSONObject().put("location", locationToJson(destination.location)).put("required", destination.required))
            }
        })
        .put("conflictPolicy", value.conflictPolicy.name)
        .put("verification", value.verification.name)
        .put("failurePolicy", value.failurePolicy.name)
        .put("deleteSourcesAfterVerifiedCopies", value.deleteSourcesAfterVerifiedCopies)

    fun planFromJson(value: JSONObject): AfPlanDefinition {
        requireSchema(value, 1, "AF Plan")
        val sources = value.getJSONArray("sources")
        val destinations = value.getJSONArray("destinations")
        require(sources.length() in 1..AfWorkflowLimits.MAX_SOURCE_ROOTS) { "Invalid AF Plan source count" }
        require(destinations.length() in 1..AfWorkflowLimits.MAX_DESTINATIONS) { "Invalid AF Plan destination count" }
        return AfPlanDefinition(
            schemaVersion = 1,
            id = value.getString("id"),
            name = value.getString("name"),
            createdAtMillis = value.getLong("createdAtMillis"),
            updatedAtMillis = value.getLong("updatedAtMillis"),
            sources = (0 until sources.length()).map { sourceFromJson(sources.getJSONObject(it)) },
            destinations = (0 until destinations.length()).map { index ->
                destinations.getJSONObject(index).let { destination ->
                    AfDestinationRef(locationFromJson(destination.getJSONObject("location")), destination.optBoolean("required", true))
                }
            },
            conflictPolicy = ConflictPolicy.valueOf(value.getString("conflictPolicy")),
            verification = TransferVerification.valueOf(value.getString("verification")),
            failurePolicy = TransferFailurePolicy.valueOf(value.getString("failurePolicy")),
            deleteSourcesAfterVerifiedCopies = value.optBoolean("deleteSourcesAfterVerifiedCopies", false),
        ).normalized(value.getLong("updatedAtMillis"))
    }

    fun nodeToJson(value: AfNodeSnapshot): JSONObject = JSONObject()
        .put("location", locationToJson(value.location))
        .put("name", value.name)
        .put("directory", value.directory)
        .put("sizeBytes", value.sizeBytes)
        .put("modifiedAtMillis", value.modifiedAtMillis ?: JSONObject.NULL)
        .put("sha256", value.sha256 ?: JSONObject.NULL)

    fun nodeFromJson(value: JSONObject): AfNodeSnapshot = AfNodeSnapshot(
        location = locationFromJson(value.getJSONObject("location")),
        name = value.getString("name"),
        directory = value.getBoolean("directory"),
        sizeBytes = value.getLong("sizeBytes"),
        modifiedAtMillis = value.longOrNull("modifiedAtMillis"),
        sha256 = value.stringOrNull("sha256"),
    )

    fun preflightToJson(value: AfPreflightSummary): JSONObject = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("executionId", value.executionId)
        .put("plan", planToJson(value.plan))
        .put("createdAtMillis", value.createdAtMillis)
        .put("entries", JSONArray().apply {
            value.entries.forEach { entry ->
                put(JSONObject()
                    .put("index", entry.index)
                    .put("sourceRootIndex", entry.sourceRootIndex)
                    .put("relativePath", entry.relativePath)
                    .put("source", nodeToJson(entry.source))
                    .put("depth", entry.depth))
            }
        })
        .put("projections", JSONArray().apply {
            value.projections.forEach { projection ->
                put(JSONObject()
                    .put("destinationIndex", projection.destinationIndex)
                    .put("sourceRootIndex", projection.sourceRootIndex)
                    .put("requestedRootName", projection.requestedRootName)
                    .put("resolvedRootName", projection.resolvedRootName)
                    .put("disposition", projection.disposition.name)
                    .put("targetRoot", locationToJson(projection.targetRoot))
                    .put("conflictSummary", projection.conflictSummary ?: JSONObject.NULL))
            }
        })
        .put("totalSourceBytes", value.totalSourceBytes)
        .put("projectedWriteBytes", value.projectedWriteBytes)
        .put("readyCopies", value.readyCopies)
        .put("conflicts", value.conflicts)
        .put("verifiedIdentical", value.verifiedIdentical)
        .put("possiblyIdentical", value.possiblyIdentical)
        .put("skipped", value.skipped)
        .put("blockers", JSONArray(value.blockers))
        .put("warnings", JSONArray(value.warnings))

    fun preflightFromJson(value: JSONObject): AfPreflightSummary {
        requireSchema(value, 1, "AF Plan preview")
        val entries = value.getJSONArray("entries")
        val projections = value.getJSONArray("projections")
        require(entries.length() in 1..AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "Invalid AF preview entry count" }
        require(projections.length() <= AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "Invalid AF preview projection count" }
        return AfPreflightSummary(
            schemaVersion = 1,
            executionId = value.getString("executionId"),
            plan = planFromJson(value.getJSONObject("plan")),
            createdAtMillis = value.getLong("createdAtMillis"),
            entries = (0 until entries.length()).map { index ->
                entries.getJSONObject(index).let { entry ->
                    AfPlannedEntry(
                        index = entry.getInt("index"),
                        sourceRootIndex = entry.getInt("sourceRootIndex"),
                        relativePath = entry.getString("relativePath"),
                        source = nodeFromJson(entry.getJSONObject("source")),
                        depth = entry.getInt("depth"),
                    )
                }
            },
            projections = (0 until projections.length()).map { index ->
                projections.getJSONObject(index).let { projection ->
                    AfDestinationProjection(
                        destinationIndex = projection.getInt("destinationIndex"),
                        sourceRootIndex = projection.getInt("sourceRootIndex"),
                        requestedRootName = projection.getString("requestedRootName"),
                        resolvedRootName = projection.getString("resolvedRootName"),
                        disposition = AfPreflightDisposition.valueOf(projection.getString("disposition")),
                        targetRoot = locationFromJson(projection.getJSONObject("targetRoot")),
                        conflictSummary = projection.stringOrNull("conflictSummary"),
                    )
                }
            },
            totalSourceBytes = value.getLong("totalSourceBytes"),
            projectedWriteBytes = value.getLong("projectedWriteBytes"),
            readyCopies = value.getInt("readyCopies"),
            conflicts = value.getInt("conflicts"),
            verifiedIdentical = value.getInt("verifiedIdentical"),
            possiblyIdentical = value.getInt("possiblyIdentical"),
            skipped = value.getInt("skipped"),
            blockers = value.getJSONArray("blockers").strings(AfWorkflowLimits.MAX_RECORDED_ERRORS),
            warnings = value.getJSONArray("warnings").strings(AfWorkflowLimits.MAX_RECORDED_ERRORS),
        )
    }

    fun executionStateToJson(value: AfExecutionState): JSONObject = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("executionId", value.executionId)
        .put("status", value.status.name)
        .put("nextProjectionIndex", value.nextProjectionIndex)
        .put("nextEntryIndex", value.nextEntryIndex)
        .put("completedFiles", value.completedFiles)
        .put("completedBytes", value.completedBytes)
        .put("failedItems", value.failedItems)
        .put("errors", JSONArray(value.errors))
        .put("sourcesDeleted", value.sourcesDeleted)
        .put("sourceDeletionPrepared", value.sourceDeletionPrepared)
        .put("activeBackup", value.activeBackup?.let(::locationToJson) ?: JSONObject.NULL)
        .put("attempt", value.attempt)
        .put("startedAtMillis", value.startedAtMillis ?: JSONObject.NULL)
        .put("updatedAtMillis", value.updatedAtMillis)
        .put("finishedAtMillis", value.finishedAtMillis ?: JSONObject.NULL)

    fun executionStateFromJson(value: JSONObject): AfExecutionState {
        requireSchema(value, 1, "AF execution state")
        return AfExecutionState(
            schemaVersion = 1,
            executionId = value.getString("executionId"),
            status = AfExecutionStatus.valueOf(value.getString("status")),
            nextProjectionIndex = value.getInt("nextProjectionIndex"),
            nextEntryIndex = value.getInt("nextEntryIndex"),
            completedFiles = value.getInt("completedFiles"),
            completedBytes = value.getLong("completedBytes"),
            failedItems = value.getInt("failedItems"),
            errors = value.getJSONArray("errors").strings(AfWorkflowLimits.MAX_RECORDED_ERRORS),
            sourcesDeleted = value.getBoolean("sourcesDeleted"),
            sourceDeletionPrepared = value.optBoolean("sourceDeletionPrepared", false),
            activeBackup = value.objectOrNull("activeBackup")?.let(::locationFromJson),
            attempt = value.getInt("attempt"),
            startedAtMillis = value.longOrNull("startedAtMillis"),
            updatedAtMillis = value.getLong("updatedAtMillis"),
            finishedAtMillis = value.longOrNull("finishedAtMillis"),
        )
    }

    fun receiptItemToJson(item: AfReceiptItem): JSONObject = JSONObject()
        .put("source", locationToJson(item.source))
        .put("destination", item.destination?.let(::locationToJson) ?: JSONObject.NULL)
        .put("directory", item.directory)
        .put("status", item.status.name)
        .put("sizeBytes", item.sizeBytes)
        .put("sha256", item.sha256 ?: JSONObject.NULL)
        .put("backup", item.backup?.let(::locationToJson) ?: JSONObject.NULL)
        .put("errorCode", item.errorCode ?: JSONObject.NULL)

    fun receiptItemFromJson(item: JSONObject): AfReceiptItem = AfReceiptItem(
        source = locationFromJson(item.getJSONObject("source")),
        destination = item.objectOrNull("destination")?.let(::locationFromJson),
        directory = item.getBoolean("directory"),
        status = AfReceiptItemStatus.valueOf(item.getString("status")),
        sizeBytes = item.getLong("sizeBytes"),
        sha256 = item.stringOrNull("sha256"),
        backup = item.objectOrNull("backup")?.let(::locationFromJson),
        errorCode = item.stringOrNull("errorCode"),
    )

    fun receiptToJson(value: AfOperationReceipt): JSONObject = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("id", value.id)
        .put("planId", value.planId)
        .put("planName", value.planName)
        .put("startedAtMillis", value.startedAtMillis)
        .put("finishedAtMillis", value.finishedAtMillis)
        .put("status", value.status.name)
        .put("verification", value.verification.name)
        .put("items", JSONArray().apply {
            value.items.forEach { item -> put(receiptItemToJson(item)) }
        })
        .put("sourceDeletionRequested", value.sourceDeletionRequested)
        .put("sourcesDeleted", value.sourcesDeleted)
        .put("undoAvailable", value.undoAvailable)
        .put("errorCount", value.errorCount)
        .put("errors", JSONArray(value.errors))

    fun receiptFromJson(value: JSONObject): AfOperationReceipt {
        requireSchema(value, 1, "AF operation receipt")
        val items = value.getJSONArray("items")
        require(items.length() <= AfWorkflowLimits.MAX_RECEIPT_ITEMS) { "AF receipt item limit exceeded" }
        return AfOperationReceipt(
            schemaVersion = 1,
            id = value.getString("id"),
            planId = value.getString("planId"),
            planName = value.getString("planName"),
            startedAtMillis = value.getLong("startedAtMillis"),
            finishedAtMillis = value.getLong("finishedAtMillis"),
            status = AfExecutionStatus.valueOf(value.getString("status")),
            verification = TransferVerification.valueOf(value.getString("verification")),
            items = (0 until items.length()).map { index ->
                receiptItemFromJson(items.getJSONObject(index))
            },
            sourceDeletionRequested = value.getBoolean("sourceDeletionRequested"),
            sourcesDeleted = value.getBoolean("sourcesDeleted"),
            undoAvailable = value.getBoolean("undoAvailable"),
            errorCount = value.getInt("errorCount"),
            errors = value.optJSONArray("errors")?.strings(AfWorkflowLimits.MAX_RECORDED_ERRORS).orEmpty(),
        )
    }

    fun automationToJson(value: AfAutomationRule): JSONObject = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("id", value.id)
        .put("name", value.name)
        .put("planId", value.planId)
        .put("enabled", value.enabled)
        .put("schedule", value.schedule.name)
        .put("unmeteredOnly", value.unmeteredOnly)
        .put("chargingOnly", value.chargingOnly)
        .put("requireFreshPreview", value.requireFreshPreview)
        .put("lastPreviewFingerprint", value.lastPreviewFingerprint ?: JSONObject.NULL)
        .put("lastPreviewAtMillis", value.lastPreviewAtMillis ?: JSONObject.NULL)
        .put("lastRunAtMillis", value.lastRunAtMillis ?: JSONObject.NULL)
        .put("lastStatus", value.lastStatus ?: JSONObject.NULL)

    fun automationFromJson(value: JSONObject): AfAutomationRule {
        requireSchema(value, 1, "AF automation rule")
        return AfAutomationRule(
            schemaVersion = 1,
            id = value.getString("id"),
            name = value.getString("name"),
            planId = value.getString("planId"),
            enabled = value.getBoolean("enabled"),
            schedule = AfAutomationSchedule.valueOf(value.getString("schedule")),
            unmeteredOnly = value.getBoolean("unmeteredOnly"),
            chargingOnly = value.optBoolean("chargingOnly", false),
            requireFreshPreview = value.optBoolean("requireFreshPreview", true),
            lastPreviewFingerprint = value.stringOrNull("lastPreviewFingerprint"),
            lastPreviewAtMillis = value.longOrNull("lastPreviewAtMillis"),
            lastRunAtMillis = value.longOrNull("lastRunAtMillis"),
            lastStatus = value.stringOrNull("lastStatus"),
        ).normalized()
    }
}

private fun requireSchema(json: JSONObject, expected: Int, label: String) {
    val actual = json.optInt("schemaVersion", -1)
    if (actual > expected) throw UnsupportedAfSchema("$label was created by a newer AF File Manager")
    require(actual == expected) { "Unsupported $label version" }
}

private fun validatedId(id: String): String = id.also(::validateId)
private fun validateId(id: String) = require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid AF identifier" }

private fun JSONObject.stringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

private fun JSONObject.longOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
private fun JSONObject.objectOrNull(name: String): JSONObject? = if (!has(name) || isNull(name)) null else getJSONObject(name)

private fun JSONArray.strings(maximum: Int): List<String> {
    require(length() <= maximum) { "AF metadata list limit exceeded" }
    return (0 until length()).map { getString(it).take(500) }
}
