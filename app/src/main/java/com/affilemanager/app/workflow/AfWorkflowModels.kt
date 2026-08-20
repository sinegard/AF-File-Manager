package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import java.io.File
import java.util.UUID

object AfWorkflowLimits {
    const val MAX_SAVED_PLANS = 64
    const val MAX_DESTINATIONS = 16
    const val MAX_SOURCE_ROOTS = 10_000
    const val MAX_PLANNED_ENTRIES = 200_000
    const val MAX_RECEIPT_ITEMS = 20_000
    const val MAX_TREE_DEPTH = 128
    const val MAX_RECORDED_ERRORS = 100
    const val MAX_TIMELINE_ENTRIES = 256
    const val MAX_AUTOMATION_RULES = 64
    const val MAX_AUTOMATION_ITEMS = 10_000L
    const val MAX_AUTOMATION_BYTES = 1L * 1_024 * 1_024 * 1_024
    const val MAX_PATH_LENGTH = 4_096
    const val MAX_NAME_LENGTH = 120
    const val MAX_RECEIPT_BYTES = 16L * 1_024 * 1_024
    const val MAX_METADATA_BYTES = 32L * 1_024 * 1_024
    const val MAX_TEXT_MERGE_CHARS = 4 * 1_024 * 1_024
    const val MAX_TEXT_MERGE_LINES = 100_000
    const val MIN_STAGING_RESERVE_BYTES = 32L * 1_024 * 1_024
}

enum class AfLocationKind { LOCAL, REMOTE }

/** A location reference deliberately contains no credentials. */
data class AfLocationRef(
    val kind: AfLocationKind,
    val path: String,
    val profileId: String? = null,
    val profileName: String? = null,
) {
    val displayLabel: String
        get() = when (kind) {
            AfLocationKind.LOCAL -> path
            AfLocationKind.REMOTE -> "${profileName.orEmpty().ifBlank { profileId.orEmpty() }} · $path"
        }

    fun normalized(): AfLocationRef = when (kind) {
        AfLocationKind.LOCAL -> copy(
            path = File(path).canonicalPath,
            profileId = null,
            profileName = null,
        )
        AfLocationKind.REMOTE -> copy(
            path = RemotePath.normalize(path),
            profileId = requireNotNull(profileId).trim().also {
                require(it.matches(SAFE_ID)) { "Invalid remote profile identifier" }
            },
            profileName = profileName.orEmpty().trim().take(AfWorkflowLimits.MAX_NAME_LENGTH),
        )
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,120}")

        fun local(path: String): AfLocationRef = AfLocationRef(AfLocationKind.LOCAL, path).normalized()

        fun remote(profileId: String, profileName: String, path: String): AfLocationRef = AfLocationRef(
            kind = AfLocationKind.REMOTE,
            path = path,
            profileId = profileId,
            profileName = profileName,
        ).normalized()
    }
}

enum class AfSourceKind { FILE_SYSTEM, ARCHIVE_ENTRY }

data class AfSourceRef(
    val location: AfLocationRef,
    val displayName: String,
    val kind: AfSourceKind = AfSourceKind.FILE_SYSTEM,
    val archiveEntryPath: String? = null,
) {
    fun normalized(): AfSourceRef {
        val safeName = displayName.trim().take(AfWorkflowLimits.MAX_NAME_LENGTH)
        require(safeName.isNotBlank()) { "Source name is required" }
        val normalizedLocation = location.normalized()
        return when (kind) {
            AfSourceKind.FILE_SYSTEM -> copy(
                location = normalizedLocation,
                displayName = safeName,
                archiveEntryPath = null,
            )
            AfSourceKind.ARCHIVE_ENTRY -> {
                require(normalizedLocation.kind == AfLocationKind.LOCAL) {
                    "Archive entries require a locally available archive"
                }
                val entry = normalizeArchiveEntry(requireNotNull(archiveEntryPath))
                copy(location = normalizedLocation, displayName = safeName, archiveEntryPath = entry)
            }
        }
    }

    private fun normalizeArchiveEntry(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.length <= AfWorkflowLimits.MAX_PATH_LENGTH) {
            "Invalid archive entry path"
        }
        require('\u0000' !in normalized) { "Invalid archive entry path" }
        val pieces = normalized.split('/')
        require(pieces.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe archive entry path" }
        return pieces.joinToString("/")
    }
}

data class AfDestinationRef(
    val location: AfLocationRef,
    val required: Boolean = true,
) {
    fun normalized(): AfDestinationRef = copy(location = location.normalized())
}

data class AfPlanDefinition(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val sources: List<AfSourceRef>,
    val destinations: List<AfDestinationRef>,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val verification: TransferVerification = TransferVerification.SIZE,
    val failurePolicy: TransferFailurePolicy = TransferFailurePolicy.STOP,
    val deleteSourcesAfterVerifiedCopies: Boolean = false,
) {
    fun normalized(nowMillis: Long = System.currentTimeMillis()): AfPlanDefinition {
        require(schemaVersion == 1) { "Unsupported AF Plan version" }
        require(id.matches(SAFE_ID)) { "Invalid AF Plan identifier" }
        val safeName = name.trim().take(AfWorkflowLimits.MAX_NAME_LENGTH)
        require(safeName.isNotBlank()) { "Plan name is required" }
        require(sources.isNotEmpty() && sources.size <= AfWorkflowLimits.MAX_SOURCE_ROOTS) {
            "AF Plan must contain 1–${AfWorkflowLimits.MAX_SOURCE_ROOTS} sources"
        }
        require(destinations.isNotEmpty() && destinations.size <= AfWorkflowLimits.MAX_DESTINATIONS) {
            "AF Plan must contain 1–${AfWorkflowLimits.MAX_DESTINATIONS} destinations"
        }
        require(conflictPolicy != ConflictPolicy.ASK) { "Resolve conflicts before saving the AF Plan" }
        require(!deleteSourcesAfterVerifiedCopies || failurePolicy == TransferFailurePolicy.STOP) {
            "Source deletion requires stop-on-error"
        }
        require(conflictPolicy != ConflictPolicy.REPLACE || failurePolicy == TransferFailurePolicy.STOP) {
            "Safe replacement requires stop-on-error"
        }
        require(!deleteSourcesAfterVerifiedCopies || destinations.any(AfDestinationRef::required)) {
            "Source deletion requires at least one required destination"
        }
        val normalizedSources = sources.map(AfSourceRef::normalized).distinctBy { source -> source.identityKey() }
        val normalizedDestinations = destinations.map(AfDestinationRef::normalized)
            .distinctBy { it.location.identityKey() }
        require(normalizedSources.isNotEmpty() && normalizedDestinations.isNotEmpty()) {
            "AF Plan became empty after normalization"
        }
        return copy(
            name = safeName,
            updatedAtMillis = nowMillis.coerceAtLeast(createdAtMillis),
            sources = normalizedSources,
            destinations = normalizedDestinations,
        )
    }

    private fun AfSourceRef.identityKey(): String = "${kind.name}:${location.identityKey()}:${archiveEntryPath.orEmpty()}"

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,80}")
    }
}

fun AfLocationRef.identityKey(): String = when (kind) {
    AfLocationKind.LOCAL -> "local:${File(path).canonicalPath}"
    AfLocationKind.REMOTE -> "remote:${profileId.orEmpty()}:${RemotePath.normalize(path)}"
}

enum class AfPreflightDisposition {
    READY,
    VERIFIED_IDENTICAL,
    POSSIBLY_IDENTICAL,
    KEEP_BOTH,
    SKIP,
    REPLACE,
    BLOCKED,
}

data class AfNodeSnapshot(
    val location: AfLocationRef,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long?,
    val sha256: String? = null,
)

data class AfPlannedEntry(
    val index: Int,
    val sourceRootIndex: Int,
    val relativePath: String,
    val source: AfNodeSnapshot,
    val depth: Int,
)

data class AfDestinationProjection(
    val destinationIndex: Int,
    val sourceRootIndex: Int,
    val requestedRootName: String,
    val resolvedRootName: String,
    val disposition: AfPreflightDisposition,
    val targetRoot: AfLocationRef,
    val conflictSummary: String? = null,
)

data class AfPreflightSummary(
    val schemaVersion: Int = 1,
    val executionId: String = UUID.randomUUID().toString(),
    val plan: AfPlanDefinition,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val entries: List<AfPlannedEntry>,
    val projections: List<AfDestinationProjection>,
    val totalSourceBytes: Long,
    val projectedWriteBytes: Long,
    val readyCopies: Int,
    val conflicts: Int,
    val verifiedIdentical: Int,
    val possiblyIdentical: Int,
    val skipped: Int,
    val blockers: List<String>,
    val warnings: List<String>,
) {
    val canRun: Boolean
        get() = blockers.isEmpty() && entries.isNotEmpty() && (
            readyCopies > 0 ||
                plan.deleteSourcesAfterVerifiedCopies && projections.any {
                    it.disposition in setOf(
                        AfPreflightDisposition.VERIFIED_IDENTICAL,
                        AfPreflightDisposition.POSSIBLY_IDENTICAL,
                    )
                }
            )
}

enum class AfExecutionStatus {
    PREVIEWED,
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

data class AfExecutionState(
    val schemaVersion: Int = 1,
    val executionId: String,
    val status: AfExecutionStatus = AfExecutionStatus.PREVIEWED,
    val nextProjectionIndex: Int = 0,
    val nextEntryIndex: Int = 0,
    val completedFiles: Int = 0,
    val completedBytes: Long = 0,
    val failedItems: Int = 0,
    val errors: List<String> = emptyList(),
    val sourcesDeleted: Boolean = false,
    /** Strong proofs are durable and source deletion may safely resume item by item. */
    val sourceDeletionPrepared: Boolean = false,
    /** Deterministic recovery backup for an interrupted root replacement. */
    val activeBackup: AfLocationRef? = null,
    val attempt: Int = 0,
    val startedAtMillis: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
)

enum class AfReceiptItemStatus { COPIED, VERIFIED_IDENTICAL, SKIPPED, REPLACED, FAILED, DELETED, RESTORED }

data class AfReceiptItem(
    val source: AfLocationRef,
    val destination: AfLocationRef?,
    val directory: Boolean,
    val status: AfReceiptItemStatus,
    val sizeBytes: Long,
    val sha256: String? = null,
    val backup: AfLocationRef? = null,
    val errorCode: String? = null,
)

data class AfOperationReceipt(
    val schemaVersion: Int = 1,
    val id: String,
    val planId: String,
    val planName: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val status: AfExecutionStatus,
    val verification: TransferVerification,
    val items: List<AfReceiptItem>,
    val sourceDeletionRequested: Boolean,
    val sourcesDeleted: Boolean,
    val undoAvailable: Boolean,
    val errorCount: Int,
    val errors: List<String> = emptyList(),
)

enum class AfUndoDisposition { SAFE, CHANGED, MISSING, UNSUPPORTED }

data class AfUndoPreviewItem(
    val receiptItemIndex: Int,
    val location: AfLocationRef,
    val disposition: AfUndoDisposition,
    val detail: String,
)

data class AfUndoPreview(
    val receiptId: String,
    val items: List<AfUndoPreviewItem>,
) {
    val canRun: Boolean get() = items.isNotEmpty() && items.all { it.disposition == AfUndoDisposition.SAFE }
}

enum class AfAutomationSchedule { MANUAL_ONLY, EVERY_6_HOURS, DAILY, WEEKLY }

data class AfAutomationRule(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val planId: String,
    val enabled: Boolean = false,
    val schedule: AfAutomationSchedule = AfAutomationSchedule.MANUAL_ONLY,
    val unmeteredOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val requireFreshPreview: Boolean = true,
    val lastPreviewFingerprint: String? = null,
    val lastPreviewAtMillis: Long? = null,
    val lastRunAtMillis: Long? = null,
    val lastStatus: String? = null,
) {
    fun normalized(): AfAutomationRule {
        require(schemaVersion == 1) { "Unsupported automation rule version" }
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid automation rule identifier" }
        require(planId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid AF Plan identifier" }
        val safeName = name.trim().take(AfWorkflowLimits.MAX_NAME_LENGTH)
        require(safeName.isNotBlank()) { "Automation name is required" }
        require(!enabled || schedule != AfAutomationSchedule.MANUAL_ONLY) {
            "A manual-only rule cannot run automatically"
        }
        require(lastStatus == null || lastStatus.length <= 240) { "Automation status is too long" }
        return copy(name = safeName, lastStatus = lastStatus?.take(240))
    }
}
