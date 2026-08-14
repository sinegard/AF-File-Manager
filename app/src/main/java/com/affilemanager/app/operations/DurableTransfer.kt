package com.affilemanager.app.operations

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

enum class TransferVerification { SIZE, SHA256 }
enum class TransferFailurePolicy { STOP, SKIP_AND_CONTINUE }
enum class TransferPhase { COPY, DELETE_SOURCES, FINALIZE_BACKUPS, COMPLETE }
enum class DurableTransferStatus { QUEUED, RUNNING, FAILED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, INTERRUPTED }

data class PlannedTransferItem(
    val index: Int,
    val sourceRootIndex: Int,
    val sourcePath: String,
    val targetPath: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val replaceExisting: Boolean,
)

data class DurableTransferPlan(
    val schemaVersion: Int = 1,
    val id: String,
    val createdAtMillis: Long,
    val destinationPath: String,
    val sourceRoots: List<String>,
    val move: Boolean,
    val conflictPolicy: ConflictPolicy,
    val verification: TransferVerification,
    val failurePolicy: TransferFailurePolicy,
    val items: List<PlannedTransferItem>,
    val totalBytes: Long,
)

data class DurableTransferState(
    val schemaVersion: Int = 1,
    val planId: String,
    val status: DurableTransferStatus = DurableTransferStatus.QUEUED,
    val phase: TransferPhase = TransferPhase.COPY,
    val nextItemIndex: Int = 0,
    val nextDeleteRootIndex: Int = 0,
    val nextFinalizeItemIndex: Int = 0,
    val failedItemIndices: List<Int> = emptyList(),
    val retryItemIndices: List<Int> = emptyList(),
    val retryPosition: Int = 0,
    val attempt: Int = 0,
    val lastMessage: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

interface DurableTransferStateWriter {
    fun saveState(state: DurableTransferState)
}

class DurableTransferPlanner {
    companion object {
        const val MAX_SOURCE_PATHS = 10_000
        const val MAX_PLAN_ITEMS = 200_000
        const val MAX_TREE_DEPTH = 64
    }

    private data class Pending(
        val sourceRootIndex: Int,
        val source: File,
        val requestedTarget: File,
        val depth: Int,
    )

    fun create(
        sourcePaths: List<String>,
        destinationDirectoryPath: String,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        verification: TransferVerification,
        failurePolicy: TransferFailurePolicy,
        id: String = UUID.randomUUID().toString(),
        createdAtMillis: Long = System.currentTimeMillis(),
    ): DurableTransferPlan {
        require(sourcePaths.isNotEmpty()) { "Nepasirinkta failų" }
        require(sourcePaths.size <= MAX_SOURCE_PATHS) { "Pasirinkta daugiau kaip $MAX_SOURCE_PATHS pradinių kelių" }
        require(conflictPolicy != ConflictPolicy.ASK) { "Konfliktų sprendimas turi būti pasirinktas prieš planavimą" }
        require(!move || failurePolicy == TransferFailurePolicy.STOP) {
            "Perkėlimas klaidos atveju turi sustoti, kad šaltinis nebūtų pašalintas dalinai"
        }

        val destination = File(destinationDirectoryPath).canonicalFile
        require(destination.isDirectory) { "Paskirties aplankas nepasiekiamas" }
        val sources = sourcePaths.distinct().map { File(it).canonicalFile }
        sources.forEach { source ->
            require(source.exists()) { "Failas nebeegzistuoja: ${source.name}" }
            require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos nepalaikomos: ${source.name}" }
            require(source != destination) { "Negalima kopijuoti aplanko į save" }
            require(!source.isDirectory || !FileSystemRules.isContained(source, destination)) {
                "Negalima kopijuoti aplanko į jo paties poaplankį"
            }
        }

        val reservedTargets = linkedSetOf<String>()
        val items = ArrayList<PlannedTransferItem>()
        var totalBytes = 0L
        sources.forEachIndexed { rootIndex, source ->
            val pending = ArrayDeque<Pending>()
            pending.add(Pending(rootIndex, source, File(destination, source.name), 0))
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                require(node.depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
                val resolved = resolveTarget(node.source, node.requestedTarget, conflictPolicy, reservedTargets) ?: continue
                val (target, replaceExisting) = resolved
                val targetKey = target.canonicalPath
                require(reservedTargets.add(targetKey)) {
                    "Keli šaltiniai planuoja tą patį tikslą: ${target.name}; pasirinkite „Palikti abu“"
                }

                val index = items.size
                require(index < MAX_PLAN_ITEMS) { "Operacija viršija $MAX_PLAN_ITEMS elementų ribą" }
                val size = if (node.source.isFile) node.source.length().coerceAtLeast(0) else 0L
                totalBytes = Math.addExact(totalBytes, size)
                items += PlannedTransferItem(
                    index = index,
                    sourceRootIndex = node.sourceRootIndex,
                    sourcePath = node.source.canonicalPath,
                    targetPath = targetKey,
                    directory = node.source.isDirectory,
                    sizeBytes = size,
                    modifiedAtMillis = node.source.lastModified().coerceAtLeast(0),
                    replaceExisting = replaceExisting,
                )

                if (node.source.isDirectory) {
                    val children = node.source.listFiles()?.sortedBy(File::getName)
                        ?: throw SecurityException("Nepavyko perskaityti ${node.source.name}")
                    children.asReversed().forEach { child ->
                        require(!Files.isSymbolicLink(child.toPath())) { "Simbolinės nuorodos nepalaikomos: ${child.name}" }
                        pending.add(Pending(node.sourceRootIndex, child, File(target, child.name), node.depth + 1))
                    }
                }
            }
        }
        require(items.isNotEmpty()) { "Pagal pasirinktą konfliktų politiką neliko kopijuojamų elementų" }

        return DurableTransferPlan(
            id = id,
            createdAtMillis = createdAtMillis,
            destinationPath = destination.canonicalPath,
            sourceRoots = sources.map(File::getCanonicalPath),
            move = move,
            conflictPolicy = conflictPolicy,
            verification = verification,
            failurePolicy = failurePolicy,
            items = items,
            totalBytes = totalBytes,
        )
    }

    private fun resolveTarget(
        source: File,
        requested: File,
        policy: ConflictPolicy,
        reserved: Set<String>,
    ): Pair<File, Boolean>? {
        val canonical = requested.canonicalFile
        val occupied = canonical.exists() || canonical.canonicalPath in reserved
        if (!occupied) return canonical to false
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> keepBothTarget(canonical, reserved) to false
            ConflictPolicy.REPLACE -> canonical to canonical.exists()
            ConflictPolicy.MERGE -> {
                require(source.isDirectory && canonical.isDirectory) { "Sujungti galima tik aplankus: ${canonical.name}" }
                canonical to false
            }
            ConflictPolicy.ASK -> error("Konfliktų sprendimas nepasirinktas")
        }
    }

    private fun keepBothTarget(requested: File, reserved: Set<String>): File {
        val extension = requested.extension.takeIf(String::isNotBlank)
        val stem = if (extension == null) requested.name else requested.name.removeSuffix(".$extension")
        for (index in 1..9_999) {
            val name = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            val candidate = File(requested.parentFile, name).canonicalFile
            if (!candidate.exists() && candidate.canonicalPath !in reserved) return candidate
        }
        throw IllegalStateException("Nepavyko parinkti unikalaus vardo: ${requested.name}")
    }
}

class DurableTransferEngine {
    companion object {
        private const val BUFFER_SIZE = 256 * 1_024
        private const val MAX_RECORDED_FAILURES = 100
        private const val STATE_CHECKPOINT_ITEMS = 32
    }

    suspend fun execute(
        plan: DurableTransferPlan,
        initialState: DurableTransferState,
        stateWriter: DurableTransferStateWriter,
        context: OperationContext,
    ): DurableTransferState {
        validatePlanState(plan, initialState)
        var state = initialState.copy(
            status = DurableTransferStatus.RUNNING,
            attempt = Math.addExact(initialState.attempt, 1),
            lastMessage = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
        stateWriter.saveState(state)

        val retryItems = state.retryItemIndices
        val totalItems = if (retryItems.isEmpty()) plan.items.size * if (plan.move) 2 else 1 else retryItems.size
        val totalBytes = if (retryItems.isEmpty()) plan.totalBytes else retryItems.sumOf { plan.items[it].sizeBytes }
        context.setTotals(totalItems, totalBytes)
        if (retryItems.isEmpty() && state.nextItemIndex > 0) {
            val completed = plan.items.take(state.nextItemIndex)
            context.progress(completed.size, completed.sumOf(PlannedTransferItem::sizeBytes), "Atkuriama")
        }

        if (state.phase == TransferPhase.COPY) {
            state = copyPhase(plan, state, stateWriter, context)
        }
        if (state.phase == TransferPhase.DELETE_SOURCES) {
            state = deleteSourcesPhase(plan, state, stateWriter, context)
        }
        if (state.phase == TransferPhase.FINALIZE_BACKUPS) {
            state = finalizeBackupsPhase(plan, state, stateWriter, context)
        }

        val finalStatus = if (state.failedItemIndices.isEmpty()) {
            DurableTransferStatus.COMPLETED
        } else {
            DurableTransferStatus.COMPLETED_WITH_ERRORS
        }
        state = state.copy(
            status = finalStatus,
            phase = TransferPhase.COMPLETE,
            retryItemIndices = emptyList(),
            retryPosition = 0,
            lastMessage = if (state.failedItemIndices.isEmpty()) null else "Nepavyko ${state.failedItemIndices.size} elementų",
            updatedAtMillis = System.currentTimeMillis(),
        )
        stateWriter.saveState(state)
        if (state.failedItemIndices.isNotEmpty()) {
            context.completeWithErrors(state.failedItemIndices.size, requireNotNull(state.lastMessage))
        } else {
            context.setRetryable(false)
        }
        return state
    }

    fun prepareRetry(state: DurableTransferState): DurableTransferState {
        require(state.status in setOf(
            DurableTransferStatus.FAILED,
            DurableTransferStatus.CANCELLED,
            DurableTransferStatus.INTERRUPTED,
            DurableTransferStatus.COMPLETED_WITH_ERRORS,
        )) { "Operacijos kartoti negalima" }
        return if (state.status == DurableTransferStatus.COMPLETED_WITH_ERRORS && state.failedItemIndices.isNotEmpty()) {
            state.copy(
                status = DurableTransferStatus.QUEUED,
                phase = TransferPhase.COPY,
                retryItemIndices = state.failedItemIndices,
                retryPosition = 0,
                failedItemIndices = emptyList(),
                lastMessage = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        } else {
            state.copy(
                status = DurableTransferStatus.QUEUED,
                lastMessage = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun markInterrupted(state: DurableTransferState, message: String): DurableTransferState = state.copy(
        status = DurableTransferStatus.INTERRUPTED,
        lastMessage = message.take(500),
        updatedAtMillis = System.currentTimeMillis(),
    )

    fun restoreBackupsAfterCopyCancellation(plan: DurableTransferPlan, state: DurableTransferState) {
        if (state.phase != TransferPhase.COPY) return
        plan.items.asReversed().forEach { item ->
            val backup = backupFile(plan, item)
            if (!backup.exists()) return@forEach
            val target = File(item.targetPath)
            if (target.exists()) deleteTree(target)
            backup.renameTo(target)
            partialFile(plan, item).delete()
        }
    }

    private suspend fun copyPhase(
        plan: DurableTransferPlan,
        initial: DurableTransferState,
        writer: DurableTransferStateWriter,
        context: OperationContext,
    ): DurableTransferState {
        var state = initial
        val retry = state.retryItemIndices
        val work = if (retry.isEmpty()) {
            (state.nextItemIndex until plan.items.size).toList()
        } else {
            retry.drop(state.retryPosition)
        }

        work.forEachIndexed { workOffset, itemIndex ->
            currentCoroutineContext().ensureActive()
            context.checkpoint()
            val item = plan.items[itemIndex]
            try {
                copyOne(plan, item, context)
                state = if (retry.isEmpty()) {
                    state.copy(nextItemIndex = itemIndex + 1, lastMessage = null)
                } else {
                    state.copy(retryPosition = state.retryPosition + 1, lastMessage = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = "${item.sourcePath}: ${error.message ?: error::class.java.simpleName}".take(500)
                if (plan.failurePolicy == TransferFailurePolicy.SKIP_AND_CONTINUE && !plan.move) {
                    restoreBackup(plan, item)
                    val failures = (state.failedItemIndices + itemIndex).distinct().take(MAX_RECORDED_FAILURES)
                    state = if (retry.isEmpty()) {
                        state.copy(nextItemIndex = itemIndex + 1, failedItemIndices = failures, lastMessage = message)
                    } else {
                        state.copy(retryPosition = state.retryPosition + 1, failedItemIndices = failures, lastMessage = message)
                    }
                    context.note("Praleista: ${File(item.sourcePath).name}")
                } else {
                    state = state.copy(
                        status = DurableTransferStatus.FAILED,
                        failedItemIndices = (state.failedItemIndices + itemIndex).distinct().take(MAX_RECORDED_FAILURES),
                        lastMessage = message,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    writer.saveState(state)
                    throw error
                }
            }
            if ((workOffset + 1) % STATE_CHECKPOINT_ITEMS == 0 || workOffset == work.lastIndex) {
                state = state.copy(updatedAtMillis = System.currentTimeMillis())
                writer.saveState(state)
            }
        }

        val nextPhase = if (plan.move && state.failedItemIndices.isEmpty()) TransferPhase.DELETE_SOURCES else TransferPhase.FINALIZE_BACKUPS
        state = state.copy(
            phase = nextPhase,
            retryItemIndices = emptyList(),
            retryPosition = 0,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writer.saveState(state)
        return state
    }

    private suspend fun deleteSourcesPhase(
        plan: DurableTransferPlan,
        initial: DurableTransferState,
        writer: DurableTransferStateWriter,
        context: OperationContext,
    ): DurableTransferState {
        var state = initial
        require(state.failedItemIndices.isEmpty()) { "Šaltiniai nešalinami, kol plane yra klaidų" }
        for (rootIndex in state.nextDeleteRootIndex until plan.sourceRoots.size) {
            currentCoroutineContext().ensureActive()
            context.checkpoint()
            verifyRootTargets(plan, rootIndex)
            val root = File(plan.sourceRoots[rootIndex])
            if (root.exists()) deleteTreeSuspend(root, context)
            state = state.copy(nextDeleteRootIndex = rootIndex + 1, updatedAtMillis = System.currentTimeMillis())
            writer.saveState(state)
        }
        state = state.copy(phase = TransferPhase.FINALIZE_BACKUPS, updatedAtMillis = System.currentTimeMillis())
        writer.saveState(state)
        return state
    }

    private suspend fun finalizeBackupsPhase(
        plan: DurableTransferPlan,
        initial: DurableTransferState,
        writer: DurableTransferStateWriter,
        context: OperationContext,
    ): DurableTransferState {
        var state = initial
        for (index in state.nextFinalizeItemIndex until plan.items.size) {
            currentCoroutineContext().ensureActive()
            context.checkpoint()
            val backup = backupFile(plan, plan.items[index])
            if (backup.exists()) deleteTree(backup)
            partialFile(plan, plan.items[index]).delete()
            state = state.copy(nextFinalizeItemIndex = index + 1)
            if ((index + 1) % STATE_CHECKPOINT_ITEMS == 0 || index == plan.items.lastIndex) {
                state = state.copy(updatedAtMillis = System.currentTimeMillis())
                writer.saveState(state)
            }
        }
        return state
    }

    private suspend fun copyOne(plan: DurableTransferPlan, item: PlannedTransferItem, context: OperationContext) {
        val source = File(item.sourcePath)
        require(source.exists()) { "Šaltinis nebeegzistuoja" }
        require(source.isDirectory == item.directory) { "Šaltinio tipas pasikeitė" }
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinė nuoroda nepalaikoma" }
        if (!item.directory) {
            require(source.length() == item.sizeBytes && source.lastModified() == item.modifiedAtMillis) {
                "Šaltinis pasikeitė po plano patvirtinimo"
            }
        }

        val target = File(item.targetPath)
        if (item.directory) {
            ensureDirectory(plan, item, target)
            context.progress(itemDelta = 1, currentName = source.name)
            return
        }

        if (target.isFile && filesEquivalent(source, target, plan.verification)) {
            context.progress(itemDelta = 1, byteDelta = item.sizeBytes, currentName = source.name)
            return
        }
        prepareTargetForReplacement(plan, item, target)
        val partial = partialFile(plan, item)
        if (partial.exists() && !partial.delete()) throw IllegalStateException("Nepavyko pašalinti nutrūkusios dalinės kopijos")
        partial.parentFile?.let { parent -> require(parent.isDirectory || parent.mkdirs()) { "Paskirties aplankas nepasiekiamas" } }

        val sourceDigest = if (plan.verification == TransferVerification.SHA256) MessageDigest.getInstance("SHA-256") else null
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        context.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sourceDigest?.update(buffer, 0, read)
                        context.progress(byteDelta = read.toLong(), currentName = source.name)
                    }
                    output.fd.sync()
                    buffer.fill(0)
                }
            }
            require(source.length() == item.sizeBytes && source.lastModified() == item.modifiedAtMillis) {
                "Šaltinis pasikeitė kopijavimo metu"
            }
            require(partial.length() == item.sizeBytes) { "Kopijos dydis nesutampa" }
            if (sourceDigest != null) {
                require(MessageDigest.isEqual(sourceDigest.digest(), sha256(partial))) { "SHA-256 patikra nepavyko" }
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("Nepavyko pakeisti ${target.name}")
            if (!partial.renameTo(target)) throw IllegalStateException("Nepavyko užbaigti ${target.name}")
            target.setLastModified(item.modifiedAtMillis)
            require(filesEquivalent(source, target, plan.verification)) { "Galutinė kopijos patikra nepavyko" }
            context.progress(itemDelta = 1, currentName = source.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun ensureDirectory(plan: DurableTransferPlan, item: PlannedTransferItem, target: File) {
        if (target.isDirectory) return
        if (target.exists()) prepareTargetForReplacement(plan, item, target)
        require(!target.exists() && (target.mkdir() || target.isDirectory)) { "Nepavyko sukurti ${target.name}" }
    }

    private fun prepareTargetForReplacement(plan: DurableTransferPlan, item: PlannedTransferItem, target: File) {
        if (!target.exists()) return
        require(item.replaceExisting) { "Tikslas pasikeitė po plano patvirtinimo: ${target.name}" }
        val backup = backupFile(plan, item)
        if (backup.exists()) {
            deleteTree(target)
            return
        }
        require(!backup.exists() && target.renameTo(backup)) { "Nepavyko saugiai atidėti keičiamo failo" }
    }

    private fun restoreBackup(plan: DurableTransferPlan, item: PlannedTransferItem) {
        val backup = backupFile(plan, item)
        if (!backup.exists()) return
        val target = File(item.targetPath)
        if (target.exists()) deleteTree(target)
        require(backup.renameTo(target)) { "Nepavyko grąžinti ankstesnio tikslo" }
    }

    private fun verifyRootTargets(plan: DurableTransferPlan, rootIndex: Int) {
        plan.items.filter { it.sourceRootIndex == rootIndex && !it.directory }.forEach { item ->
            val source = File(item.sourcePath)
            if (!source.exists()) return@forEach
            val target = File(item.targetPath)
            require(target.isFile && filesEquivalent(source, target, plan.verification)) {
                "Tikslas pasikeitė prieš šaltinio pašalinimą: ${target.name}"
            }
        }
    }

    private fun filesEquivalent(source: File, target: File, verification: TransferVerification): Boolean {
        if (!source.isFile || !target.isFile || source.length() != target.length()) return false
        return verification == TransferVerification.SIZE || MessageDigest.isEqual(sha256(source), sha256(target))
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        return digest.digest()
    }

    private suspend fun deleteTreeSuspend(file: File, context: OperationContext) {
        context.checkpoint()
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTreeSuspend(it, context) }
                ?: throw SecurityException("Nepavyko perskaityti ${file.name}")
        }
        val size = if (file.isFile) file.length() else 0L
        require(file.delete() || !file.exists()) { "Nepavyko pašalinti ${file.name}" }
        context.progress(itemDelta = 1, byteDelta = 0, currentName = file.name)
        if (size < 0) error("Neigiama failo apimtis")
    }

    private fun backupFile(plan: DurableTransferPlan, item: PlannedTransferItem): File =
        File(File(item.targetPath).parentFile, ".af-backup-${plan.id}-${item.index}")

    private fun partialFile(plan: DurableTransferPlan, item: PlannedTransferItem): File =
        File(File(item.targetPath).parentFile, ".af-part-${plan.id}-${item.index}.tmp")

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        require(file.delete() || !file.exists()) { "Nepavyko pašalinti ${file.name}" }
    }

    private fun validatePlanState(plan: DurableTransferPlan, state: DurableTransferState) {
        require(plan.schemaVersion == 1 && state.schemaVersion == 1) { "Nepalaikoma operacijos versija" }
        require(plan.id == state.planId) { "Plano ir būsenos tapatybės nesutampa" }
        require(plan.items.size <= DurableTransferPlanner.MAX_PLAN_ITEMS) { "Plano elementų riba viršyta" }
        require(state.nextItemIndex in 0..plan.items.size) { "Netinkamas operacijos kontrolinis taškas" }
        require(state.failedItemIndices.size <= MAX_RECORDED_FAILURES) { "Klaidų sąrašo riba viršyta" }
        require(state.retryItemIndices.size <= MAX_RECORDED_FAILURES) { "Kartojimo sąrašo riba viršyta" }
        require(state.failedItemIndices.all { it in plan.items.indices }) { "Netinkamas klaidos elemento indeksas" }
        require(state.retryItemIndices.all { it in plan.items.indices }) { "Netinkamas kartojimo elemento indeksas" }
    }
}
