package com.affilemanager.app.advanced

import android.content.Context
import android.os.Environment
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileEntryOrdering
import com.affilemanager.app.data.FileSelectionSummary
import com.affilemanager.app.editing.EditConflict
import com.affilemanager.app.editing.EditLimits
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.editing.EditSaveResult
import com.affilemanager.app.editing.EditSession
import com.affilemanager.app.editing.FileRevision
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.operations.OperationContext
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class PrivilegedRoot(
    val path: String,
    val title: String,
)

data class PrivilegedTransferResult(
    val copiedRoots: Int,
    val skippedRoots: Int,
)

class PrivilegedFileRepository(
    context: Context,
    private val access: AdvancedAccessManager,
) {
    companion object {
        private const val COPY_BUFFER = 256 * 1_024
        private const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }

    private val previewCache = PrivilegedPreviewCache(context.cacheDir)
    private val primaryStorage = Environment.getExternalStorageDirectory().absolutePath
    private val protectedRoots: List<PrivilegedRoot> = listOf(
        PrivilegedRoot("$primaryStorage/Android/data", "Android/data"),
        PrivilegedRoot("$primaryStorage/Android/obb", "Android/obb"),
    )
    val roots: List<PrivilegedRoot>
        get() = when (access.state.value.activeBackend) {
            AdvancedAccessBackend.ROOT, AdvancedAccessBackend.SHIZUKU_ROOT -> listOf(PrivilegedRoot("/", "Root"))
            else -> protectedRoots
        }
    private val allowedRoots: List<String>
        get() = roots.map(PrivilegedRoot::path)

    suspend fun probeAndroidData(): Result<Boolean> = ioResult {
        val manager = access.fileSystemOrThrow()
        val root = manager.getFile(protectedRoots.first().path)
        val accessible = root.isDirectory && root.list() != null
        access.reportAndroidDataProbe(accessible)
        accessible
    }

    suspend fun availableRoots(): Result<List<PrivilegedRoot>> = ioResult {
        val manager = access.fileSystemOrThrow()
        roots.filter { root -> runCatching { manager.getFile(root.path).isDirectory }.getOrDefault(false) }
    }

    suspend fun list(
        directoryPath: String,
        includeHidden: Boolean,
        sortMode: SortMode,
        sortDirection: SortDirection,
        localWritePath: String? = null,
    ): Result<List<FileEntry>> = ioResult {
        val manager = access.fileSystemOrThrow()
        val directory = existingContained(manager, directoryPath, allowRoot = true)
        require(directory.isDirectory) { "Tai nėra aplankas" }
        localWritePath?.let { path ->
            val changed = existingContained(manager, path, allowRoot = false)
            require(changed.parentFile?.canonicalPath == directory.canonicalPath)
            // One actual read, not a guessed size or a timed retry, coheres legacy mounts.
            privilegedInput(changed).close()
        }
        val children = directory.listFiles() ?: throw SecurityException("Aplanko turinio perskaityti nepavyko")
        require(children.size <= PrivilegedPathRules.MAX_VISIBLE_ENTRIES) {
            "Aplanke daugiau nei ${PrivilegedPathRules.MAX_VISIBLE_ENTRIES} elementų"
        }
        val entries = children.asSequence()
            .filter { includeHidden || !it.isHidden }
            .filter { child -> runCatching { canonicalContained(child, allowRoot = false); true }.getOrDefault(false) }
            .map(::toEntry)
            .toList()
        FileEntryOrdering.order(entries, sortMode, sortDirection)
    }

    suspend fun createDirectory(parentPath: String, requestedName: String): Result<FileEntry> = ioResult {
        val manager = access.fileSystemOrThrow()
        val parent = existingContained(manager, parentPath, allowRoot = true)
        require(parent.isDirectory) { "Tėvinis aplankas nepasiekiamas" }
        val path = PrivilegedPathRules.child(parent.canonicalPath, requestedName, allowedRoots)
        val target = manager.getFile(path)
        require(!target.exists() && !target.isSymlink) { "Toks pavadinimas jau naudojamas" }
        access.fileMetadataServiceOrThrow().createPrivateEntry(target.path, true)
        toEntry(existingContained(manager, path, allowRoot = false))
    }

    suspend fun selectionInfo(paths: Collection<String>): Result<FileSelectionSummary> = ioResult {
        val selected = paths.map(String::trim).filter(String::isNotEmpty).distinct()
        require(selected.isNotEmpty() && selected.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) {
            "Netinkamas pasirinktų elementų skaičius"
        }
        val manager = access.fileSystemOrThrow()
        data class Pending(val file: ExtendedFile, val depth: Int, val selectedRoot: Boolean)
        val pending = ArrayDeque<Pending>()
        selected.asReversed().forEach { pending.add(Pending(existingContained(manager, it, allowRoot = false), 0, true)) }
        val seen = hashSetOf<String>()
        var files = 0
        var folders = 0
        var bytes = 0L
        var scanned = 0
        var complete = true
        while (pending.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (scanned >= 100_000) { complete = false; break }
            val current = pending.removeLast()
            val canonical = runCatching { canonicalContained(current.file, allowRoot = false) }.getOrElse {
                complete = false; continue
            }
            if (!seen.add(canonical.canonicalPath)) { complete = false; continue }
            scanned++
            when {
                runCatching { Files.isSymbolicLink(canonical.toPath()) }.getOrDefault(false) -> complete = false
                canonical.isDirectory -> {
                    if (!current.selectedRoot) folders++
                    if (current.depth >= 64) { complete = false; continue }
                    val children = canonical.listFiles()
                    if (children == null) complete = false else children.take(50_000).asReversed().forEach {
                        pending.add(Pending(it, current.depth + 1, false))
                    }
                }
                canonical.isFile -> {
                    files++
                    val value = canonical.length().coerceAtLeast(0L)
                    bytes = if (value > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + value
                }
                else -> complete = false
            }
        }
        FileSelectionSummary(selected.size, files, folders, bytes, scanned, complete)
    }

    suspend fun createFile(parentPath: String, requestedName: String): Result<FileEntry> = ioResult {
        val manager = access.fileSystemOrThrow()
        val parent = existingContained(manager, parentPath, allowRoot = true)
        require(parent.isDirectory) { "Tėvinis aplankas nepasiekiamas" }
        val path = PrivilegedPathRules.child(parent.canonicalPath, requestedName, allowedRoots)
        val target = manager.getFile(path)
        require(!target.exists() && !target.isSymlink) { "Toks pavadinimas jau naudojamas" }
        access.fileMetadataServiceOrThrow().createPrivateEntry(target.path, false)
        toEntry(existingContained(manager, path, allowRoot = false))
    }

    suspend fun rename(path: String, requestedName: String): Result<FileEntry> = ioResult {
        val manager = access.fileSystemOrThrow()
        val source = existingContained(manager, path, allowRoot = false)
        val parent = requireNotNull(source.parentFile) { "Šakninio aplanko pervadinti negalima" }
        canonicalContained(parent, allowRoot = true)
        val targetPath = PrivilegedPathRules.child(parent.canonicalPath, requestedName, allowedRoots)
        val target = manager.getFile(targetPath)
        require(!target.exists() && !target.isSymlink) { "Toks pavadinimas jau naudojamas" }
        check(source.renameTo(target)) { "Pervadinti nepavyko" }
        toEntry(existingContained(manager, targetPath, allowRoot = false))
    }

    suspend fun deletePermanently(paths: List<String>, operation: OperationContext): PrivilegedTransferResult = withContext(Dispatchers.IO) {
        require(paths.isNotEmpty() && paths.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) { "Netinkamas pasirinktų elementų skaičius" }
        val manager = access.fileSystemOrThrow()
        val sources = paths.distinct().map { existingContained(manager, it, allowRoot = false) }
        val summary = selectionInfo(paths).getOrThrow()
        operation.setTotals(
            items = summary.scannedNodes.takeIf { summary.complete },
            bytes = summary.totalBytes.takeIf { summary.complete },
        )
        var removed = 0
        sources.forEach { source ->
            operation.checkpoint()
            deleteTree(source, source, operation, depth = 0, counter = Counter())
            removed += 1
        }
        PrivilegedTransferResult(removed, 0)
    }

    suspend fun copyWithin(
        sourcePaths: List<String>,
        destinationPath: String,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        operation: OperationContext,
    ): PrivilegedTransferResult = withContext(Dispatchers.IO) {
        require(sourcePaths.isNotEmpty() && sourcePaths.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) { "Netinkamas pasirinktų elementų skaičius" }
        val manager = access.fileSystemOrThrow()
        val destination = existingContained(manager, destinationPath, allowRoot = true)
        require(destination.isDirectory) { "Paskirties aplankas nepasiekiamas" }
        val sources = sourcePaths.distinct().map { existingContained(manager, it, allowRoot = false) }
        require(sources.none { source -> destination.canonicalPath == source.canonicalPath || destination.canonicalPath.startsWith("${source.canonicalPath}/") }) {
            "Aplanko negalima kopijuoti į jo paties vidų"
        }
        operation.setTotals(null, null)
        var copied = 0
        var skipped = 0
        sources.forEach { source ->
            operation.checkpoint()
            val target = resolveRemoteTarget(destination, source.name, conflictPolicy)
            if (target == null) {
                skipped += 1
                return@forEach
            }
            if (move && !target.exists() && source.renameTo(target)) {
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
                return@forEach
            }
            val partial = uniqueRemoteChild(destination, ".${target.name}.af-partial")
            try {
                copyRemoteTree(source, partial, operation, depth = 0, counter = Counter())
                require(equalTree(source, partial, Counter())) { "Galutinė kopijos patikra nepavyko" }
                operation.checkpoint()
                if (target.exists()) deleteTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteVerifiedMoveSource(source, target, operation)
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
            } finally {
                withContext(NonCancellable) {
                    if (partial.exists()) runCatching { deleteTree(partial, partial, OperationContext.background(), 0, Counter()) }
                }
            }
        }
        PrivilegedTransferResult(copied, skipped)
    }

    suspend fun copyFromLocal(
        sourcePaths: List<String>,
        destinationPath: String,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        operation: OperationContext,
    ): PrivilegedTransferResult = withContext(Dispatchers.IO) {
        require(sourcePaths.isNotEmpty() && sourcePaths.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) { "Netinkamas pasirinktų elementų skaičius" }
        val manager = access.fileSystemOrThrow()
        val destination = existingContained(manager, destinationPath, allowRoot = true)
        val sources = sourcePaths.distinct().map { FileSystemRules.nonSymbolicSource(File(it)) }
        require(sources.all(File::exists)) { "Vietinis šaltinis nebeegzistuoja" }
        var copied = 0
        var skipped = 0
        operation.setTotals(null, null)
        sources.forEach { source ->
            operation.checkpoint()
            val target = resolveRemoteTarget(destination, source.name, conflictPolicy)
            if (target == null) {
                skipped += 1
                return@forEach
            }
            val partial = uniqueRemoteChild(destination, ".${target.name}.af-partial")
            try {
                copyLocalTree(source, partial, operation, 0, Counter())
                require(equalLocalRemoteTree(source, partial, Counter())) { "Galutinė kopijos patikra nepavyko" }
                operation.checkpoint()
                if (target.exists()) deleteTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteVerifiedMoveSource(source, target, operation)
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
            } finally {
                withContext(NonCancellable) {
                    if (partial.exists()) runCatching { deleteTree(partial, partial, OperationContext.background(), 0, Counter()) }
                }
            }
        }
        PrivilegedTransferResult(copied, skipped)
    }

    suspend fun copyToLocal(
        sourcePaths: List<String>,
        destinationDirectory: File,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        operation: OperationContext,
    ): PrivilegedTransferResult = withContext(Dispatchers.IO) {
        require(sourcePaths.isNotEmpty() && sourcePaths.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) { "Netinkamas pasirinktų elementų skaičius" }
        val manager = access.fileSystemOrThrow()
        val destination = destinationDirectory.canonicalFile
        require(destination.isDirectory && destination.canWrite()) { "Vietinis paskirties aplankas neįrašomas" }
        val sources = sourcePaths.distinct().map { existingContained(manager, it, allowRoot = false) }
        var copied = 0
        var skipped = 0
        operation.setTotals(null, null)
        sources.forEach { source ->
            operation.checkpoint()
            val target = resolveLocalTarget(destination, source.name, conflictPolicy)
            if (target == null) {
                skipped += 1
                return@forEach
            }
            val partial = File(destination, ".${target.name}.${UUID.randomUUID()}.af-partial")
            require(partial.canonicalFile.parentFile == destination) { "Laikinas kelias išeina už paskirties" }
            try {
                copyRemoteToLocalTree(source, partial, operation, 0, Counter())
                require(equalRemoteLocalTree(source, partial, Counter())) { "Galutinė kopijos patikra nepavyko" }
                operation.checkpoint()
                if (target.exists()) deleteReplacedLocalTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteVerifiedMoveSource(source, target, operation)
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
            } finally {
                if (partial.exists()) partial.deleteRecursively()
            }
        }
        PrivilegedTransferResult(copied, skipped)
    }

    suspend fun stageForPreview(entry: FileEntry): Result<File> = ioResult {
        require(!entry.isDirectory && entry.sizeBytes <= EditLimits.MAX_FILE_BYTES) { "Failas per didelis peržiūrai" }
        // Readable system files still need a private copy: the FileProvider must
        // not expose the whole device root just to play an alarm or open a PDF.
        val local = File(entry.absolutePath).takeIf { it.isFile && it.canRead() }
        val protected = if (local == null) {
            existingContained(access.fileSystemOrThrow(), entry.absolutePath, allowRoot = false)
                .also { require(it.isFile) { "Failas nepasiekiamas" } }
        } else null
        val destination = previewCache.createDestination(entry)
        try {
            (local?.inputStream() ?: privilegedInput(requireNotNull(protected))).buffered().use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    copyBounded(input, output, EditLimits.MAX_FILE_BYTES,
                        beforeRead = { kotlinx.coroutines.currentCoroutineContext().ensureActive() })
                }
            }
            require(destination.length() == (local?.length() ?: requireNotNull(protected).length())) { "Peržiūros kopijos dydis neatitinka" }
            previewCache.validateCompleted(destination)
            destination
        } catch (error: Throwable) {
            previewCache.discard(destination)
            throw error
        }
    }

    fun discardPreview(file: File?): Boolean = previewCache.discard(file)

    fun openInput(path: String): InputStream {
        val manager = access.fileSystemOrThrow()
        return privilegedInput(existingContained(manager, path, allowRoot = false))
    }

    private fun privilegedInput(file: ExtendedFile): InputStream {
        val service = access.fileReadServiceOrThrow()
        return PrivilegedReadInputStream(service,
            PrivilegedPathRules.requireWithinAllowed(file.path, allowedRoots, allowRoot = false),
            isAuthorized = { access.isCurrentReadService(service) })
    }

    fun saveOrigin(session: EditSession, force: Boolean): EditSaveResult {
        val origin = session.origin as? EditOrigin.Privileged ?: error("Redagavimo sesija nėra privilegijuota")
        val manager = access.fileSystemOrThrow()
        val target = manager.getFile(PrivilegedPathRules.requireWithinAllowed(origin.path, allowedRoots, allowRoot = false))
        val current = revisionOrNull(target)
        if (!force && !session.originRevision.hasSameContent(current)) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, current))
        }
        require(origin.canWrite) { "Pradinio failo negalima įrašyti; naudokite „Išsaugoti kaip“" }
        val parent = requireNotNull(target.parentFile)
        canonicalContained(parent, allowRoot = true)
        val metadataService = access.fileMetadataServiceOrThrow()
        val originalMetadata = if (target.exists()) PrivilegedFileMetadata.decode(metadataService.readFileMetadata(target.path)) else null
        val temporary = uniqueRemoteChild(parent, ".${target.name}.af-edit")
        val backup = uniqueRemoteChild(parent, ".${target.name}.af-backup")
        var backupMade = false
        var publicationVerified = false
        try {
            writeRemoteFile(temporary, session.workingFile.inputStream(), EditLimits.MAX_FILE_BYTES)
            val temporaryRevision = requireNotNull(revisionOrNull(temporary)) { "Laikinos kopijos patikrinti nepavyko" }
            require(session.workingRevision.hasSameContent(temporaryRevision)) { "Laikina kopija neatitinka redaguoto failo" }
            if (!force) {
                val latest = revisionOrNull(target)
                if (!session.originRevision.hasSameContent(latest)) {
                    return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, latest))
                }
            }
            if (originalMetadata != null) {
                val latestMetadata = PrivilegedFileMetadata.decode(metadataService.readFileMetadata(target.path))
                require(originalMetadata.sameEntry(latestMetadata) && originalMetadata.sameSecurity(latestMetadata)) {
                    "Failas pasikeitė"
                }
                metadataService.applyFileMetadata(temporary.path, originalMetadata.encode())
            }
            val stagedMetadata = PrivilegedFileMetadata.decode(metadataService.readFileMetadata(temporary.path))
            if (target.exists()) {
                // Keep the original name until replacement. On legacy emulated-storage
                // mounts, moving it away can leave another view attached to its old inode.
                writeRemoteFile(backup, privilegedInput(target), EditLimits.MAX_FILE_BYTES)
                require(current?.hasSameContent(revisionOrNull(backup)) == true) { "Pradinio failo atsarginės kopijos sukurti nepavyko" }
                if (originalMetadata != null) {
                    metadataService.applyFileMetadata(backup.path, originalMetadata.encode())
                    require(originalMetadata.sameSecurity(PrivilegedFileMetadata.decode(metadataService.readFileMetadata(backup.path)))) {
                        "Pradinio failo atsarginės kopijos sukurti nepavyko"
                    }
                }
                backupMade = true
                require(current?.hasSameContent(revisionOrNull(target)) == true) { "Failas pasikeitė" }
                if (originalMetadata != null) {
                    val latestMetadata = PrivilegedFileMetadata.decode(metadataService.readFileMetadata(target.path))
                    require(originalMetadata.sameEntry(latestMetadata) && originalMetadata.sameSecurity(latestMetadata)) { "Failas pasikeitė" }
                }
            }
            require(temporary.renameTo(target)) { "Redaguoto failo įrašyti nepavyko" }
            val verified = requireNotNull(revisionOrNull(target)) { "Išsaugoto failo patikrinti nepavyko" }
            require(session.workingRevision.hasSameContent(verified)) { "Išsaugoto failo turinys neatitinka" }
            val publishedMetadata = PrivilegedFileMetadata.decode(metadataService.readFileMetadata(target.path))
            require(stagedMetadata.sameEntry(publishedMetadata) && stagedMetadata.sameSecurity(publishedMetadata)) {
                "Išsaugoto failo patikrinti nepavyko"
            }
            publicationVerified = true
            if (backupMade) require(backup.delete()) { "Failas išsaugotas, bet laikinos atsarginės kopijos pašalinti nepavyko" }
            return EditSaveResult.Saved(verified)
        } catch (error: Throwable) {
            if (backupMade && !target.exists()) runCatching { backup.renameTo(target) }
            throw error
        } finally {
            if (temporary.exists()) runCatching { temporary.delete() }
            if (publicationVerified && backup.exists() && target.exists()) runCatching { backup.delete() }
        }
    }

    private fun toEntry(file: ExtendedFile): FileEntry = FileEntry(
        absolutePath = file.absolutePath,
        name = file.name.ifBlank { file.absolutePath },
        kind = FileSystemRules.detectKind(file.name, mimeType = null, isDirectory = file.isDirectory),
        sizeBytes = if (file.isFile) file.length().coerceAtLeast(0) else 0L,
        modifiedAtMillis = file.lastModified().coerceAtLeast(0),
        isHidden = file.isHidden,
        isReadable = file.canRead(),
        isWritable = file.canWrite(),
    )

    private fun existingContained(manager: FileSystemManager, path: String, allowRoot: Boolean): ExtendedFile {
        val normalized = PrivilegedPathRules.requireWithinAllowed(path, allowedRoots, allowRoot)
        val file = manager.getFile(normalized)
        require(file.exists()) { "Failas arba aplankas nebeegzistuoja" }
        require(!file.isSymlink) { "Simbolinės nuorodos šiame režime neatidaromos" }
        return canonicalContained(file, allowRoot)
    }

    private fun canonicalContained(file: ExtendedFile, allowRoot: Boolean): ExtendedFile {
        val canonical = file.canonicalFile
        PrivilegedPathRules.requireWithinAllowed(canonical.absolutePath, allowedRoots, allowRoot)
        return canonical
    }

    private fun resolveRemoteTarget(parent: ExtendedFile, name: String, policy: ConflictPolicy): ExtendedFile? {
        val requested = parent.getChildFile(FileSystemRules.validateFileName(name).getOrThrow())
        PrivilegedPathRules.requireWithinAllowed(requested.absolutePath, allowedRoots, allowRoot = false)
        require(!requested.isSymlink) { "Simbolinės nuorodos negali būti perrašomos" }
        if (requested.exists()) canonicalContained(requested, allowRoot = false)
        if (!requested.exists()) return requested
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.ASK -> throw IllegalStateException("Paskirtyje jau yra „$name“")
            ConflictPolicy.KEEP_BOTH -> keepBothRemote(parent, name)
            ConflictPolicy.REPLACE, ConflictPolicy.MERGE -> requested
        }
    }

    private fun resolveLocalTarget(parent: File, name: String, policy: ConflictPolicy): File? {
        val safeName = FileSystemRules.validateFileName(name).getOrThrow()
        val requestedRaw = File(parent, safeName)
        require(!Files.isSymbolicLink(requestedRaw.toPath())) { "Simbolinės nuorodos negali būti perrašomos" }
        val requested = requestedRaw.canonicalFile
        require(requested.parentFile == parent) { "Paskirties kelias išeina už aplanko" }
        if (!requested.exists()) return requested
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.ASK -> throw IllegalStateException("Paskirtyje jau yra „$name“")
            ConflictPolicy.KEEP_BOTH -> FileSystemRules.keepBothTarget(requested)
            ConflictPolicy.REPLACE, ConflictPolicy.MERGE -> requested
        }
    }

    private fun keepBothRemote(parent: ExtendedFile, name: String): ExtendedFile {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { name.substring(0, it) } ?: name
        val extension = dot?.let { name.substring(it) }.orEmpty()
        for (index in 1..MAX_KEEP_BOTH_ATTEMPTS) {
            val candidate = parent.getChildFile("$base ($index)$extension")
            if (!candidate.exists() && !candidate.isSymlink) return candidate
        }
        error("Nepavyko rasti laisvo pavadinimo")
    }

    private fun uniqueRemoteChild(parent: ExtendedFile, prefix: String): ExtendedFile {
        repeat(32) {
            val safePrefix = prefix.take(120).replace('/', '_')
            val candidate = parent.getChildFile("$safePrefix-${UUID.randomUUID()}")
            PrivilegedPathRules.requireWithinAllowed(candidate.absolutePath, allowedRoots, allowRoot = false)
            if (!candidate.exists() && !candidate.isSymlink) return candidate
        }
        error("Nepavyko sukurti unikalaus laikino kelio")
    }

    private suspend fun copyRemoteTree(source: ExtendedFile, target: ExtendedFile, operation: OperationContext, depth: Int, counter: Counter) {
        visit(source, depth, counter, operation)
        if (source.isSymlink) throw SecurityException("Simbolinės nuorodos nekopijuojamos")
        if (source.isDirectory) {
            access.fileMetadataServiceOrThrow().createPrivateEntry(target.path, true)
            source.listFiles()?.forEach { child -> copyRemoteTree(child, target.getChildFile(child.name), operation, depth + 1, counter) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        } else {
            require(source.isFile) { "Source file is not available" }
            writeRemoteOperationFile(target, privilegedInput(source), source.length().coerceAtLeast(0), operation, source.name)
        }
    }

    private suspend fun copyLocalTree(source: File, target: ExtendedFile, operation: OperationContext, depth: Int, counter: Counter) {
        require(depth <= PrivilegedPathRules.MAX_DEPTH) { "Per gilus aplankų medis" }
        operation.checkpoint()
        counter.add()
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
        if (source.isDirectory) {
            access.fileMetadataServiceOrThrow().createPrivateEntry(target.path, true)
            source.listFiles()?.forEach { child -> copyLocalTree(child, target.getChildFile(child.name), operation, depth + 1, counter) }
                ?: throw SecurityException("Vietinis aplankas neperskaitomas")
        } else {
            require(source.isFile) { "Source file is not available" }
            writeRemoteOperationFile(target, source.inputStream(), source.length().coerceAtLeast(0), operation, source.name)
        }
    }

    private suspend fun copyRemoteToLocalTree(source: ExtendedFile, target: File, operation: OperationContext, depth: Int, counter: Counter) {
        visit(source, depth, counter, operation)
        if (source.isSymlink) throw SecurityException("Simbolinės nuorodos nekopijuojamos")
        if (source.isDirectory) {
            require(target.mkdir()) { "Vietinio aplanko sukurti nepavyko" }
            source.listFiles()?.forEach { child -> copyRemoteToLocalTree(child, File(target, child.name), operation, depth + 1, counter) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        } else {
            require(source.isFile) { "Source file is not available" }
            FileOutputStream(target).use { fileOutput ->
                privilegedInput(source).buffered().use { input ->
                    copyBounded(input, fileOutput, source.length().coerceAtLeast(0),
                        beforeRead = { operation.checkpoint() },
                        onChunk = { operation.progress(byteDelta = it.toLong(), currentName = source.name) })
                }
                fileOutput.fd.sync()
            }
        }
    }

    private fun writeRemoteFile(target: ExtendedFile, source: InputStream, maximumBytes: Long) {
        source.buffered().use { input ->
            PrivilegedWriteOutputStream(access.fileMetadataServiceOrThrow(), target.path).buffered().use { output ->
                copyBounded(input, output, maximumBytes)
                output.flush()
            }
        }
    }

    private suspend fun writeRemoteOperationFile(target: ExtendedFile, source: InputStream, maximumBytes: Long,
        operation: OperationContext, name: String) {
        source.buffered().use { input ->
            PrivilegedWriteOutputStream(access.fileMetadataServiceOrThrow(), target.path).buffered().use { output ->
                copyBounded(input, output, maximumBytes, beforeRead = { operation.checkpoint() },
                    onChunk = { operation.progress(byteDelta = it.toLong(), currentName = name) })
                output.flush()
            }
        }
    }

    private inline fun copyBounded(input: InputStream, output: OutputStream, maximumBytes: Long,
        beforeRead: () -> Unit = {}, onChunk: (Int) -> Unit = {}): Long {
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            beforeRead()
            val read = input.read(buffer)
            if (read < 0) break
            total = Math.addExact(total, read.toLong())
            require(total <= maximumBytes) { "Failas pasikeitė arba viršijo leistiną dydį" }
            output.write(buffer, 0, read)
            onChunk(read)
        }
        return total
    }

    private suspend fun visit(file: ExtendedFile, depth: Int, counter: Counter, operation: OperationContext) {
        require(depth <= PrivilegedPathRules.MAX_DEPTH) { "Per gilus aplankų medis" }
        operation.checkpoint()
        canonicalContained(file, allowRoot = false)
        counter.add()
    }

    private suspend fun deleteTree(root: ExtendedFile, file: ExtendedFile, operation: OperationContext, depth: Int, counter: Counter) {
        visit(file, depth, counter, operation)
        require(file.canonicalPath == root.canonicalPath || file.canonicalPath.startsWith("${root.canonicalPath}/")) { "Trynimo kelias išeina už pasirinkto elemento" }
        if (file.isSymlink) {
            require(file.delete()) { "Simbolinės nuorodos pašalinti nepavyko" }
            operation.progress(itemDelta = 1, currentName = file.name)
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach { child -> deleteTree(root, child, operation, depth + 1, counter) }
            ?: throw SecurityException("Aplankas neperskaitomas")
        val bytes = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
        require(file.delete()) { "Pašalinti nepavyko: ${file.name}" }
        operation.progress(itemDelta = 1, byteDelta = bytes, currentName = file.name)
    }

    /** Explicitly approved destination replacement, never copied-move source cleanup. */
    private suspend fun deleteReplacedLocalTree(root: File, file: File, operation: OperationContext, depth: Int, counter: Counter) {
        require(depth <= PrivilegedPathRules.MAX_DEPTH) { "Per gilus aplankų medis" }
        operation.checkpoint()
        counter.add()
        require(!Files.isSymbolicLink(file.toPath())) { "Simbolinės nuorodos nešalinamos perkeliant" }
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Trynimo kelias išeina už pasirinkto elemento" }
        if (file.isDirectory) file.listFiles()?.forEach { child -> deleteReplacedLocalTree(root, child, operation, depth + 1, counter) }
            ?: throw SecurityException("Vietinis aplankas neperskaitomas")
        require(file.delete()) { "Pašalinti nepavyko: ${file.name}" }
    }

    /** One cleanup owner for local→root, root→local and copied root→root moves. */
    private suspend fun deleteVerifiedMoveSource(source: File, target: File, operation: OperationContext) {
        operation.note("SHA-256")
        val sourceRoot = source.canonicalPath
        val targetRoot = target.canonicalPath
        val counter = Counter()
        fun isLink(file: File): Boolean = if (file is ExtendedFile) file.isSymlink else Files.isSymbolicLink(file.toPath())
        fun child(parent: File, name: String): File = if (parent is ExtendedFile) parent.getChildFile(name) else File(parent, name)
        fun input(file: File): InputStream = if (file is ExtendedFile) privilegedInput(file) else file.inputStream()
        fun contained(file: File, root: String): Boolean = file.canonicalPath.let { it == root || it.startsWith("$root/") }
        suspend fun digest(file: File): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256")
            val expected = file.length()
            var readBytes = 0L
            input(file).buffered().use { stream ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    operation.checkpoint()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    readBytes = Math.addExact(readBytes, count.toLong())
                    require(readBytes <= expected) { "Galutinė kopijos patikra nepavyko" }
                    hash.update(buffer, 0, count)
                }
            }
            require(readBytes == expected) { "Galutinė kopijos patikra nepavyko" }
            return hash.digest()
        }
        suspend fun removeVerified(file: File, copy: File, depth: Int) {
            operation.checkpoint()
            counter.add()
            require(depth <= PrivilegedPathRules.MAX_DEPTH && !isLink(file) && !isLink(copy) &&
                contained(file, sourceRoot) && contained(copy, targetRoot) && file.exists() && copy.exists() &&
                file.isDirectory == copy.isDirectory && file.isFile == copy.isFile) { "Galutinė kopijos patikra nepavyko" }
            if (file.isDirectory) {
                val children = requireNotNull(file.listFiles()) { "Galutinė kopijos patikra nepavyko" }
                // A child added after publication has no matching verified copy and is kept.
                children.forEach { removeVerified(it, child(copy, it.name), depth + 1) }
            } else {
                require(file.isFile && file.length() == copy.length()) { "Galutinė kopijos patikra nepavyko" }
                val sourceSize = file.length()
                val sourceModified = file.lastModified()
                val targetModified = copy.lastModified()
                val sourceHash = digest(file)
                val targetHash = digest(copy)
                require(MessageDigest.isEqual(sourceHash, targetHash) && !isLink(file) && !isLink(copy) &&
                    file.length() == sourceSize && copy.length() == sourceSize &&
                    file.lastModified() == sourceModified && copy.lastModified() == targetModified) { "Galutinė kopijos patikra nepavyko" }
            }
            // A later extra child makes this leaf-only directory delete fail safely.
            require(file.delete()) { "Galutinė kopijos patikra nepavyko" }
        }
        removeVerified(source, target, 0)
    }

    private fun equalTree(left: ExtendedFile, right: ExtendedFile, counter: Counter): Boolean {
        counter.add()
        if (left.isDirectory != right.isDirectory || left.isFile != right.isFile) return false
        if (left.isFile) return left.length() == right.length()
        val leftChildren = left.listFiles()?.associateBy { it.name } ?: return false
        val rightChildren = right.listFiles()?.associateBy { it.name } ?: return false
        return leftChildren.keys == rightChildren.keys && leftChildren.all { (name, child) -> equalTree(child, requireNotNull(rightChildren[name]), counter) }
    }

    private fun equalLocalRemoteTree(left: File, right: ExtendedFile, counter: Counter): Boolean {
        counter.add()
        if (left.isDirectory != right.isDirectory || left.isFile != right.isFile) return false
        if (left.isFile) return left.length() == right.length()
        val leftChildren = left.listFiles()?.associateBy { it.name } ?: return false
        val rightChildren = right.listFiles()?.associateBy { it.name } ?: return false
        return leftChildren.keys == rightChildren.keys && leftChildren.all { (name, child) -> equalLocalRemoteTree(child, requireNotNull(rightChildren[name]), counter) }
    }

    private fun equalRemoteLocalTree(left: ExtendedFile, right: File, counter: Counter): Boolean {
        counter.add()
        if (left.isDirectory != right.isDirectory || left.isFile != right.isFile) return false
        if (left.isFile) return left.length() == right.length()
        val leftChildren = left.listFiles()?.associateBy { it.name } ?: return false
        val rightChildren = right.listFiles()?.associateBy { it.name } ?: return false
        return leftChildren.keys == rightChildren.keys && leftChildren.all { (name, child) -> equalRemoteLocalTree(child, requireNotNull(rightChildren[name]), counter) }
    }

    private fun revisionOrNull(file: ExtendedFile): FileRevision? {
        if (!file.exists()) return null
        require(file.isFile) { "Pradinis kelias nėra failas" }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        privilegedInput(file).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size = Math.addExact(size, read.toLong())
                require(size <= EditLimits.MAX_FILE_BYTES) { "Failas per didelis redagavimo patikrai" }
                digest.update(buffer, 0, read)
            }
        }
        return FileRevision(
            sizeBytes = size,
            modifiedAtMillis = file.lastModified().takeIf { it > 0 },
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private suspend fun <T> ioResult(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            access.reportOperationFailure(error)
            Result.failure(error)
        }
    }

    private class Counter {
        private var value = 0
        fun add() {
            value = Math.addExact(value, 1)
            require(value <= PrivilegedPathRules.MAX_TREE_ENTRIES) { "Failų medis viršijo saugos ribą" }
        }
    }
}
