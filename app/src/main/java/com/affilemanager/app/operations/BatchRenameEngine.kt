package com.affilemanager.app.operations

import com.affilemanager.app.core.FileSystemRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

enum class RenameCaseMode {
    KEEP,
    LOWERCASE,
    UPPERCASE,
}

data class BatchRenameSpec(
    val findText: String = "",
    val replacementText: String = "",
    val useRegex: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val caseMode: RenameCaseMode = RenameCaseMode.KEEP,
    val numberingEnabled: Boolean = false,
    val numberStart: Int = 1,
    val numberPadding: Int = 3,
    val numberSeparator: String = " ",
    val extensionOverride: String = "",
)

data class BatchRenamePreviewItem(
    val originalPath: String,
    val originalName: String,
    val targetPath: String,
    val targetName: String,
    val expectedSizeBytes: Long,
    val expectedModifiedAtMillis: Long,
    val directory: Boolean,
    val issue: String? = null,
) {
    val changed: Boolean get() = originalPath != targetPath
}

data class BatchRenamePreview(
    val items: List<BatchRenamePreviewItem>,
    val errors: List<String> = emptyList(),
) {
    val changedCount: Int get() = items.count(BatchRenamePreviewItem::changed)
    val canExecute: Boolean get() = changedCount > 0 && errors.isEmpty() && items.none { it.issue != null }
}

data class BatchRenameUndoItem(
    val originalPath: String,
    val renamedPath: String,
    val renamedSizeBytes: Long,
    val renamedModifiedAtMillis: Long,
    val directory: Boolean,
)

data class BatchRenameUndo(val items: List<BatchRenameUndoItem>)

class BatchRenameEngine(
    private val renameFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    private data class RenameStage(
        val item: BatchRenamePreviewItem,
        val temporary: File,
        var finalized: Boolean = false,
    )

    companion object {
        const val MAX_RENAME_ITEMS = 2_000
        private const val MAX_NUMBER = 999_999_999
        private const val MAX_PADDING = 9
        private const val MAX_TEMP_ATTEMPTS = 100
    }

    suspend fun preview(paths: List<String>, spec: BatchRenameSpec): BatchRenamePreview = withContext(Dispatchers.IO) {
        require(paths.isNotEmpty()) { "Nepasirinkta failų" }
        require(paths.size <= MAX_RENAME_ITEMS) { "Vienu metu galima pervadinti iki $MAX_RENAME_ITEMS elementų" }
        require(spec.numberStart in 0..MAX_NUMBER) { "Numeravimo pradžia turi būti nuo 0 iki $MAX_NUMBER" }
        require(spec.numberPadding in 1..MAX_PADDING) { "Skaitmenų skaičius turi būti nuo 1 iki $MAX_PADDING" }

        val sources = paths.map { File(it).canonicalFile }.distinctBy(File::getAbsolutePath)
        require(sources.size <= MAX_RENAME_ITEMS) { "Vienu metu galima pervadinti iki $MAX_RENAME_ITEMS elementų" }
        val regexResult = if (spec.useRegex && spec.findText.isNotEmpty()) runCatching {
            Regex(spec.findText)
        } else {
            Result.success(null)
        }
        val globalErrors = regexResult.exceptionOrNull()?.let { listOf("Netinkama reguliarioji išraiška: ${it.message.orEmpty()}") }.orEmpty()
        val regex = regexResult.getOrNull()

        var items = sources.mapIndexed { index, source ->
            val sourceIssue = when {
                !source.exists() -> "Failas nebeegzistuoja"
                source.parentFile == null -> "Šakninio aplanko pervadinti negalima"
                source.parentFile?.canWrite() != true -> "Aplanko negalima keisti"
                else -> null
            }
            val targetNameResult = runCatching { generatedName(source, spec, regex, index) }
                .mapCatching { FileSystemRules.validateFileName(it).getOrThrow() }
            val targetName = targetNameResult.getOrElse { source.name }
            val target = source.parentFile?.let { File(it, targetName).absoluteFile } ?: source
            BatchRenamePreviewItem(
                originalPath = source.absolutePath,
                originalName = source.name,
                targetPath = target.absolutePath,
                targetName = targetName,
                expectedSizeBytes = if (source.isFile) source.length() else 0,
                expectedModifiedAtMillis = source.lastModified(),
                directory = source.isDirectory,
                issue = sourceIssue ?: targetNameResult.exceptionOrNull()?.message,
            )
        }

        val changedSources = items.filter(BatchRenamePreviewItem::changed).map(BatchRenamePreviewItem::originalPath).toSet()
        val duplicateTargets = items.groupBy(BatchRenamePreviewItem::targetPath).filterValues { it.size > 1 }.keys
        items = items.map { item ->
            val conflict = when {
                item.targetPath in duplicateTargets -> "Keli elementai gautų tą patį pavadinimą"
                item.changed && File(item.targetPath).exists() && item.targetPath !in changedSources -> "Toks pavadinimas jau naudojamas"
                else -> null
            }
            item.copy(issue = item.issue ?: conflict)
        }
        BatchRenamePreview(items, globalErrors)
    }

    suspend fun execute(preview: BatchRenamePreview, context: OperationContext): BatchRenameUndo = withContext(Dispatchers.IO) {
        require(preview.canExecute) { "Pervadinimo planas turi neišspręstų klaidų arba nieko nekeičia" }
        executeMappings(preview.items.filter(BatchRenamePreviewItem::changed), context)
    }

    suspend fun undo(undo: BatchRenameUndo, context: OperationContext) = withContext(Dispatchers.IO) {
        require(undo.items.isNotEmpty()) { "Nėra ko atšaukti" }
        val reverseItems = undo.items.map { item ->
            val current = File(item.renamedPath)
            BatchRenamePreviewItem(
                originalPath = item.renamedPath,
                originalName = current.name,
                targetPath = item.originalPath,
                targetName = File(item.originalPath).name,
                expectedSizeBytes = item.renamedSizeBytes,
                expectedModifiedAtMillis = item.renamedModifiedAtMillis,
                directory = item.directory,
            )
        }
        executeMappings(reverseItems, context)
    }

    private suspend fun executeMappings(
        mappings: List<BatchRenamePreviewItem>,
        context: OperationContext,
    ): BatchRenameUndo {
        require(mappings.isNotEmpty()) { "Nėra ko pervadinti" }
        require(mappings.size <= MAX_RENAME_ITEMS) { "Pervadinimo riba viršyta" }
        validateFreshPlan(mappings)
        context.setTotals(mappings.size, null)

        val stages = mutableListOf<RenameStage>()
        try {
            mappings.forEach { item ->
                context.checkpoint()
                val source = File(item.originalPath)
                val temporary = uniqueTemporary(source)
                check(renameFile(source, temporary)) { "Nepavyko laikinai pervadinti ${source.name}" }
                stages += RenameStage(item, temporary)
            }
            stages.forEach { stage ->
                context.checkpoint()
                val target = File(stage.item.targetPath)
                check(renameFile(stage.temporary, target)) { "Nepavyko užbaigti ${stage.item.targetName}" }
                stage.finalized = true
                context.progress(itemDelta = 1, currentName = stage.item.targetName)
            }
        } catch (failure: Throwable) {
            val recoveryFailures = rollback(stages)
            if (recoveryFailures.isNotEmpty()) {
                val recovery = IllegalStateException(
                    "Pervadinimas nepavyko, o ${recoveryFailures.size} elementų automatiškai grąžinti nepavyko",
                    failure,
                )
                recoveryFailures.forEach(recovery::addSuppressed)
                throw recovery
            }
            throw failure
        }

        return BatchRenameUndo(
            stages.map { stage ->
                val renamed = File(stage.item.targetPath)
                BatchRenameUndoItem(
                    originalPath = stage.item.originalPath,
                    renamedPath = stage.item.targetPath,
                    renamedSizeBytes = if (renamed.isFile) renamed.length() else 0,
                    renamedModifiedAtMillis = renamed.lastModified(),
                    directory = renamed.isDirectory,
                )
            },
        )
    }

    private fun validateFreshPlan(mappings: List<BatchRenamePreviewItem>) {
        val sourcePaths = mappings.map(BatchRenamePreviewItem::originalPath).toSet()
        require(sourcePaths.size == mappings.size) { "Pervadinimo plane kartojasi šaltiniai" }
        require(mappings.map(BatchRenamePreviewItem::targetPath).toSet().size == mappings.size) {
            "Pervadinimo plane kartojasi tikslai"
        }
        mappings.forEach { item ->
            val source = File(item.originalPath)
            require(source.exists()) { "Failas nebeegzistuoja: ${item.originalName}" }
            require(source.isDirectory == item.directory) { "Failo tipas pasikeitė: ${item.originalName}" }
            require((if (source.isFile) source.length() else 0) == item.expectedSizeBytes) {
                "Failas pasikeitė po peržiūros: ${item.originalName}"
            }
            require(source.lastModified() == item.expectedModifiedAtMillis) {
                "Failas pasikeitė po peržiūros: ${item.originalName}"
            }
            require(source.parentFile?.canWrite() == true) { "Aplanko negalima keisti: ${item.originalName}" }
            val target = File(item.targetPath)
            require(target.parentFile?.absolutePath == source.parentFile?.absolutePath) {
                "Masinis pervadinimas negali perkelti failų į kitą aplanką"
            }
            require(!target.exists() || item.targetPath in sourcePaths) { "Tikslas jau egzistuoja: ${target.name}" }
        }
    }

    private fun rollback(stages: List<RenameStage>): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        stages.asReversed().filter(RenameStage::finalized).forEach { stage ->
            val target = File(stage.item.targetPath)
            if (target.exists() && !renameFile(target, stage.temporary)) {
                failures += IllegalStateException("Nepavyko atkurti tarpinio vardo: ${target.name}")
            }
        }
        stages.asReversed().forEach { stage ->
            if (stage.temporary.exists()) {
                val original = File(stage.item.originalPath)
                if (original.exists() || !renameFile(stage.temporary, original)) {
                    failures += IllegalStateException("Nepavyko grąžinti: ${stage.item.originalName}")
                }
            }
        }
        return failures
    }

    private fun uniqueTemporary(source: File): File {
        val parent = requireNotNull(source.parentFile)
        repeat(MAX_TEMP_ATTEMPTS) {
            val candidate = File(parent, ".af-rename-${UUID.randomUUID()}.tmp")
            if (!candidate.exists()) return candidate
        }
        throw IllegalStateException("Nepavyko parinkti saugaus laikino pavadinimo")
    }

    private fun generatedName(source: File, spec: BatchRenameSpec, regex: Regex?, index: Int): String {
        val extensionIndex = if (source.isFile) source.name.lastIndexOf('.').takeIf { it > 0 } else null
        val originalStem = extensionIndex?.let { source.name.substring(0, it) } ?: source.name
        val originalExtension = extensionIndex?.let { source.name.substring(it + 1) }.orEmpty()
        var stem = when {
            spec.findText.isEmpty() -> originalStem
            regex != null -> regex.replace(originalStem, spec.replacementText)
            else -> originalStem.replace(spec.findText, spec.replacementText)
        }
        stem = when (spec.caseMode) {
            RenameCaseMode.KEEP -> stem
            RenameCaseMode.LOWERCASE -> stem.lowercase(Locale.ROOT)
            RenameCaseMode.UPPERCASE -> stem.uppercase(Locale.ROOT)
        }
        stem = spec.prefix + stem + spec.suffix
        if (spec.numberingEnabled) {
            val number = Math.addExact(spec.numberStart, index)
            require(number <= MAX_NUMBER) { "Numeravimas viršijo $MAX_NUMBER" }
            stem += spec.numberSeparator + number.toString().padStart(spec.numberPadding, '0')
        }
        val extension = if (!source.isFile || spec.extensionOverride.isBlank()) {
            originalExtension
        } else {
            spec.extensionOverride.trim().removePrefix(".")
        }
        return if (extension.isBlank()) stem else "$stem.$extension"
    }
}
