package com.affilemanager.app.workflow

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AfTimelineUndoEngine {
    suspend fun preview(receipt: AfOperationReceipt, storage: AfStorageSession): AfUndoPreview {
        if (!receipt.undoAvailable || receipt.sourcesDeleted) {
            val representativeLocation = receipt.items.firstOrNull()?.let { it.destination ?: it.source }
            return AfUndoPreview(
                receipt.id,
                representativeLocation?.let { location -> listOf(
                    AfUndoPreviewItem(
                        receiptItemIndex = -1,
                        location = location,
                        disposition = AfUndoDisposition.UNSUPPORTED,
                        detail = "This operation no longer has a safe undo path",
                    ),
                ) }.orEmpty(),
            )
        }
        val result = ArrayList<AfUndoPreviewItem>()
        val recordedDestinations = receipt.items.mapNotNull(AfReceiptItem::destination)
        receipt.items.forEachIndexed { index, item ->
            if (item.status !in UNDOABLE_STATUSES || item.destination == null) return@forEachIndexed
            val destination = item.destination
            val current = runCatching { storage.stat(destination) }.getOrNull()
            val disposition: AfUndoDisposition
            val detail: String
            when {
                current == null -> {
                    disposition = AfUndoDisposition.MISSING
                    detail = "The destination no longer exists"
                }
                current.directory != item.directory -> {
                    disposition = AfUndoDisposition.CHANGED
                    detail = "The destination type changed"
                }
                item.directory -> {
                    val expectedChildren = recordedDestinations.asSequence()
                        .filter { candidate -> parent(candidate).identityKey() == destination.identityKey() }
                        .map(AfLocationRef::identityKey)
                        .toSet()
                    val actualChildren = runCatching { storage.listChildren(destination) }.getOrNull()
                    val hasUnexpectedChildren = actualChildren == null ||
                        actualChildren.any { child -> child.location.identityKey() !in expectedChildren }
                    val backupReady = item.status != AfReceiptItemStatus.REPLACED ||
                        (item.backup != null && runCatching { storage.stat(item.backup) }.getOrNull() != null)
                    when {
                        hasUnexpectedChildren -> {
                            disposition = AfUndoDisposition.CHANGED
                            detail = "The folder contains items that were not created by this operation"
                        }
                        !backupReady -> {
                            disposition = AfUndoDisposition.MISSING
                            detail = "The recovery backup is missing"
                        }
                        else -> {
                            disposition = AfUndoDisposition.SAFE
                            detail = "Folder will be removed only if it is empty after its recorded children are undone"
                        }
                    }
                }
                current.sizeBytes != item.sizeBytes || item.sha256 == null -> {
                    disposition = AfUndoDisposition.CHANGED
                    detail = "The destination no longer matches the operation proof"
                }
                runCatching { storage.sha256(destination) }.getOrNull() != item.sha256 -> {
                    disposition = AfUndoDisposition.CHANGED
                    detail = "The destination content changed after the operation"
                }
                item.status == AfReceiptItemStatus.REPLACED &&
                    (item.backup == null || runCatching { storage.stat(item.backup) }.getOrNull() == null) -> {
                    disposition = AfUndoDisposition.MISSING
                    detail = "The recovery backup is missing"
                }
                else -> {
                    disposition = AfUndoDisposition.SAFE
                    detail = if (item.status == AfReceiptItemStatus.REPLACED) {
                        "The previous version can be restored"
                    } else {
                        "The copied file can be removed"
                    }
                }
            }
            result += AfUndoPreviewItem(index, destination, disposition, detail)
        }
        return AfUndoPreview(receipt.id, result)
    }

    suspend fun execute(
        receipt: AfOperationReceipt,
        preview: AfUndoPreview,
        storage: AfStorageSession,
        operation: OperationContext,
    ): AfOperationReceipt {
        require(preview.receiptId == receipt.id && preview.canRun) { "Undo preview is no longer safe" }
        require(this.preview(receipt, storage) == preview) { "Undo targets changed after preview" }
        val selectedIndices = preview.items.associateBy(AfUndoPreviewItem::receiptItemIndex)
        operation.setTotals(selectedIndices.size, null)
        val results = ArrayList<AfReceiptItem>()
        receipt.items.withIndex().toList().asReversed().forEach { indexed ->
            currentCoroutineContext().ensureActive()
            operation.checkpoint()
            val previewItem = selectedIndices[indexed.index] ?: return@forEach
            require(previewItem.disposition == AfUndoDisposition.SAFE) { "Undo target changed after preview" }
            val original = indexed.value
            val destination = requireNotNull(original.destination)
            val current = storage.stat(destination) ?: error("Undo target disappeared")
            if (!original.directory) {
                require(current.sizeBytes == original.sizeBytes && original.sha256 != null) { "Undo target changed" }
                require(storage.sha256(destination) == original.sha256) { "Undo target changed" }
            }
            when (original.status) {
                AfReceiptItemStatus.COPIED -> storage.delete(destination, recursive = false)
                AfReceiptItemStatus.REPLACED -> {
                    val backup = requireNotNull(original.backup) { "Undo recovery backup is missing" }
                    require(storage.stat(backup) != null) { "Undo recovery backup is missing" }
                    storage.delete(destination, recursive = false)
                    storage.rename(backup, destination)
                }
                else -> error("Receipt item is not undoable")
            }
            results += AfReceiptItem(
                source = destination,
                destination = original.backup,
                directory = original.directory,
                status = AfReceiptItemStatus.RESTORED,
                sizeBytes = original.sizeBytes,
                sha256 = original.sha256,
            )
            operation.progress(itemDelta = 1, currentName = destination.path.substringAfterLast('/'))
        }
        val now = System.currentTimeMillis()
        return AfOperationReceipt(
            id = java.util.UUID.randomUUID().toString(),
            planId = receipt.planId,
            planName = "Undo: ${receipt.planName}".take(AfWorkflowLimits.MAX_NAME_LENGTH),
            startedAtMillis = now,
            finishedAtMillis = System.currentTimeMillis(),
            status = AfExecutionStatus.COMPLETED,
            verification = receipt.verification,
            items = results,
            sourceDeletionRequested = false,
            sourcesDeleted = false,
            undoAvailable = false,
            errorCount = 0,
        )
    }

    private companion object {
        val UNDOABLE_STATUSES = setOf(AfReceiptItemStatus.COPIED, AfReceiptItemStatus.REPLACED)
    }
}
