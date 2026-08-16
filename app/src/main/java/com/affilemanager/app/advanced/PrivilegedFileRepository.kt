package com.affilemanager.app.advanced

import android.content.Context
import android.os.Environment
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileEntryOrdering
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
    val roots: List<PrivilegedRoot> = listOf(
        PrivilegedRoot("$primaryStorage/Android/data", "Android/data"),
        PrivilegedRoot("$primaryStorage/Android/obb", "Android/obb"),
    )
    private val allowedRoots: List<String> = roots.map(PrivilegedRoot::path)

    suspend fun probeAndroidData(): Result<Boolean> = ioResult {
        val manager = access.fileSystemOrThrow()
        val root = manager.getFile(roots.first().path)
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
    ): Result<List<FileEntry>> = ioResult {
        val manager = access.fileSystemOrThrow()
        val directory = existingContained(manager, directoryPath, allowRoot = true)
        require(directory.isDirectory) { "Tai nėra aplankas" }
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
        require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
        check(target.mkdir()) { "Aplanko sukurti nepavyko" }
        toEntry(existingContained(manager, path, allowRoot = false))
    }

    suspend fun createFile(parentPath: String, requestedName: String): Result<FileEntry> = ioResult {
        val manager = access.fileSystemOrThrow()
        val parent = existingContained(manager, parentPath, allowRoot = true)
        require(parent.isDirectory) { "Tėvinis aplankas nepasiekiamas" }
        val path = PrivilegedPathRules.child(parent.canonicalPath, requestedName, allowedRoots)
        val target = manager.getFile(path)
        require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
        check(target.createNewFile()) { "Failo sukurti nepavyko" }
        toEntry(existingContained(manager, path, allowRoot = false))
    }

    suspend fun rename(path: String, requestedName: String): Result<FileEntry> = ioResult {
        val manager = access.fileSystemOrThrow()
        val source = existingContained(manager, path, allowRoot = false)
        val parent = requireNotNull(source.parentFile) { "Šakninio aplanko pervadinti negalima" }
        canonicalContained(parent, allowRoot = true)
        val targetPath = PrivilegedPathRules.child(parent.canonicalPath, requestedName, allowedRoots)
        val target = manager.getFile(targetPath)
        require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
        check(source.renameTo(target)) { "Pervadinti nepavyko" }
        toEntry(existingContained(manager, targetPath, allowRoot = false))
    }

    suspend fun deletePermanently(paths: List<String>, operation: OperationContext): PrivilegedTransferResult = withContext(Dispatchers.IO) {
        require(paths.isNotEmpty() && paths.size <= PrivilegedPathRules.MAX_SELECTED_ROOTS) { "Netinkamas pasirinktų elementų skaičius" }
        val manager = access.fileSystemOrThrow()
        val sources = paths.distinct().map { existingContained(manager, it, allowRoot = false) }
        operation.setTotals(null, null)
        var removed = 0
        sources.forEach { source ->
            operation.checkpoint()
            deleteTree(source, source, operation, depth = 0, counter = Counter())
            operation.progress(itemDelta = 1, currentName = source.name)
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
                require(equalTree(source, partial, Counter())) { "Nukopijuotas turinys nepatikrintas" }
                if (target.exists()) deleteTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteTree(source, source, operation, 0, Counter())
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
            } finally {
                if (partial.exists()) runCatching { deleteTree(partial, partial, OperationContext.background(), 0, Counter()) }
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
        val sources = sourcePaths.distinct().map { File(it).canonicalFile }
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
                require(equalLocalRemoteTree(source, partial, Counter())) { "Nukopijuotas turinys nepatikrintas" }
                if (target.exists()) deleteTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteLocalTree(source, source, operation, 0, Counter())
                copied += 1
                operation.progress(itemDelta = 1, currentName = source.name)
            } finally {
                if (partial.exists()) runCatching { deleteTree(partial, partial, OperationContext.background(), 0, Counter()) }
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
                require(equalRemoteLocalTree(source, partial, Counter())) { "Nukopijuotas turinys nepatikrintas" }
                if (target.exists()) deleteLocalTree(target, target, operation, 0, Counter())
                require(partial.renameTo(target)) { "Kopijos užbaigti nepavyko" }
                if (move) deleteTree(source, source, operation, 0, Counter())
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
        val manager = access.fileSystemOrThrow()
        val source = existingContained(manager, entry.absolutePath, allowRoot = false)
        require(source.isFile) { "Failas nepasiekiamas" }
        val destination = previewCache.createDestination(entry)
        try {
            source.newInputStream().buffered().use { input ->
                FileOutputStream(destination).buffered().use { output -> copyBounded(input, output, EditLimits.MAX_FILE_BYTES, null) }
            }
            require(destination.length() == source.length()) { "Peržiūros kopijos dydis neatitinka" }
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
        return existingContained(manager, path, allowRoot = false).newInputStream()
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
        val temporary = uniqueRemoteChild(parent, ".${target.name}.af-edit")
        val backup = uniqueRemoteChild(parent, ".${target.name}.af-backup")
        var backupMade = false
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
            if (target.exists()) {
                require(target.renameTo(backup)) { "Pradinio failo atsarginės kopijos sukurti nepavyko" }
                backupMade = true
            }
            require(temporary.renameTo(target)) { "Redaguoto failo įrašyti nepavyko" }
            val verified = requireNotNull(revisionOrNull(target)) { "Išsaugoto failo patikrinti nepavyko" }
            require(session.workingRevision.hasSameContent(verified)) { "Išsaugoto failo turinys neatitinka" }
            if (backupMade) require(backup.delete()) { "Failas išsaugotas, bet laikinos atsarginės kopijos pašalinti nepavyko" }
            return EditSaveResult.Saved(verified)
        } catch (error: Throwable) {
            if (backupMade && !target.exists()) runCatching { backup.renameTo(target) }
            throw error
        } finally {
            if (temporary.exists()) runCatching { temporary.delete() }
            if (backup.exists() && target.exists()) runCatching { backup.delete() }
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
        require(!requested.exists() || !requested.isSymlink) { "Simbolinės nuorodos negali būti perrašomos" }
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
            if (!candidate.exists()) return candidate
        }
        error("Nepavyko rasti laisvo pavadinimo")
    }

    private fun uniqueRemoteChild(parent: ExtendedFile, prefix: String): ExtendedFile {
        repeat(32) {
            val safePrefix = prefix.take(120).replace('/', '_')
            val candidate = parent.getChildFile("$safePrefix-${UUID.randomUUID()}")
            PrivilegedPathRules.requireWithinAllowed(candidate.absolutePath, allowedRoots, allowRoot = false)
            if (!candidate.exists()) return candidate
        }
        error("Nepavyko sukurti unikalaus laikino kelio")
    }

    private suspend fun copyRemoteTree(source: ExtendedFile, target: ExtendedFile, operation: OperationContext, depth: Int, counter: Counter) {
        visit(source, depth, counter, operation)
        if (source.isSymlink) throw SecurityException("Simbolinės nuorodos nekopijuojamos")
        if (source.isDirectory) {
            require(target.mkdir()) { "Aplanko kopijos sukurti nepavyko" }
            source.listFiles()?.forEach { child -> copyRemoteTree(child, target.getChildFile(child.name), operation, depth + 1, counter) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        } else {
            writeRemoteFile(target, source.newInputStream(), source.length().coerceAtLeast(0))
            operation.progress(byteDelta = source.length().coerceAtLeast(0), currentName = source.name)
        }
    }

    private suspend fun copyLocalTree(source: File, target: ExtendedFile, operation: OperationContext, depth: Int, counter: Counter) {
        require(depth <= PrivilegedPathRules.MAX_DEPTH) { "Per gilus aplankų medis" }
        operation.checkpoint()
        counter.add()
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
        if (source.isDirectory) {
            require(target.mkdir()) { "Aplanko kopijos sukurti nepavyko" }
            source.listFiles()?.forEach { child -> copyLocalTree(child, target.getChildFile(child.name), operation, depth + 1, counter) }
                ?: throw SecurityException("Vietinis aplankas neperskaitomas")
        } else {
            writeRemoteFile(target, source.inputStream(), source.length().coerceAtLeast(0))
            operation.progress(byteDelta = source.length().coerceAtLeast(0), currentName = source.name)
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
            FileOutputStream(target).use { fileOutput ->
                source.newInputStream().buffered().use { input -> copyBounded(input, fileOutput, source.length().coerceAtLeast(0), operation) }
                fileOutput.fd.sync()
            }
        }
    }

    private fun writeRemoteFile(target: ExtendedFile, source: InputStream, maximumBytes: Long) {
        target.newOutputStream().buffered().use { output ->
            source.buffered().use { input -> copyBounded(input, output, maximumBytes, null) }
            output.flush()
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maximumBytes: Long, operation: OperationContext?): Long {
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total = Math.addExact(total, read.toLong())
            require(total <= maximumBytes) { "Failas pasikeitė arba viršijo leistiną dydį" }
            output.write(buffer, 0, read)
            operation?.progress(byteDelta = read.toLong())
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
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach { child -> deleteTree(root, child, operation, depth + 1, counter) }
            ?: throw SecurityException("Aplankas neperskaitomas")
        require(file.delete()) { "Pašalinti nepavyko: ${file.name}" }
    }

    private suspend fun deleteLocalTree(root: File, file: File, operation: OperationContext, depth: Int, counter: Counter) {
        require(depth <= PrivilegedPathRules.MAX_DEPTH) { "Per gilus aplankų medis" }
        operation.checkpoint()
        counter.add()
        require(!Files.isSymbolicLink(file.toPath())) { "Simbolinės nuorodos nešalinamos perkeliant" }
        val rootPath = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(rootPath)) { "Trynimo kelias išeina už pasirinkto elemento" }
        if (file.isDirectory) file.listFiles()?.forEach { child -> deleteLocalTree(root, child, operation, depth + 1, counter) }
            ?: throw SecurityException("Vietinis aplankas neperskaitomas")
        require(file.delete()) { "Pašalinti nepavyko: ${file.name}" }
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
        file.newInputStream().buffered().use { input ->
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
