package com.affilemanager.app.data

import android.content.Context
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class TrashItem(
    val id: String,
    val originalPath: String,
    val storedPath: String,
    val deletedAtMillis: Long,
    val sizeBytes: Long,
    val directory: Boolean,
)

data class TrashBrowserEntry(
    val itemId: String,
    val relativePath: String,
    val storedPath: String,
    val name: String,
    val kind: EntryKind,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val isReadable: Boolean,
    val deletedAtMillis: Long?,
    val originalPath: String?,
    val topLevel: Boolean,
) {
    val isDirectory: Boolean get() = kind == EntryKind.DIRECTORY

    fun toFileEntry(): FileEntry = FileEntry(
        absolutePath = storedPath,
        name = name,
        kind = kind,
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        isHidden = false,
        isReadable = isReadable,
        isWritable = false,
    )
}

data class EmptyTrashResult(
    val deletedItems: Int,
    val failedItems: Int,
)

data class TrashMoveResult(
    val movedItems: Int,
    val failedItems: Int,
)

data class TrashStorageState(
    val storedItemCount: Int,
    val countComplete: Boolean,
) {
    val hasStoredData: Boolean get() = storedItemCount > 0 || !countComplete
}

internal object TrashPathRules {
    private const val MAX_DEPTH = 64
    private const val MAX_SEGMENT_LENGTH = 255

    fun normalize(relativePath: String): String {
        if (relativePath.isBlank()) return ""
        val parts = relativePath.split('/').filter(String::isNotEmpty)
        require(parts.size <= MAX_DEPTH) { "Per gilus šiukšliadėžės katalogo kelias" }
        require(parts.none { it == "." || it == ".." }) { "Netinkamas šiukšliadėžės katalogo kelias" }
        require(parts.all { it.length <= MAX_SEGMENT_LENGTH }) { "Per ilgas šiukšliadėžės kelio segmentas" }
        return parts.joinToString("/")
    }

    fun child(parent: String, name: String): String = normalize(
        if (parent.isBlank()) name else "$parent/$name",
    )

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")
}

class TrashRepository(
    private val context: Context,
    private val configuredRoot: File? = null,
) {
    companion object {
        private const val MAX_TRASH_ITEMS = 10_000
        private const val MAX_ROOT_ENTRIES_PER_PASS = 20_000
        private const val MAX_DIRECTORY_ENTRIES = 50_000
        private const val MAX_SCAN_ENTRIES = 200_000
        private const val COPY_BUFFER = 256 * 1_024
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        private val processMutationMutex = Mutex()
    }

    private data class RootSnapshot(
        val entries: List<File>,
        val complete: Boolean,
    )

    private class EntryBudget(private val maximum: Int) {
        private var used = 0

        fun claim() {
            used += 1
            require(used <= maximum) { "Per daug elementų" }
        }
    }

    private val mutationMutex: Mutex get() = processMutationMutex

    private val root: File by lazy {
        (configuredRoot ?: requireNotNull(context.getExternalFilesDir("trash")) { "Šiukšliadėžės vieta nepasiekiama" })
            .apply { require(isDirectory || mkdirs()) { "Šiukšliadėžės vietos sukurti nepavyko" } }
    }

    suspend fun list(): List<TrashItem> = withContext(Dispatchers.IO) {
        listInternal()
    }

    suspend fun storageState(): TrashStorageState = withContext(Dispatchers.IO) {
        val snapshot = rootSnapshot()
        TrashStorageState(
            storedItemCount = groupRootEntries(snapshot.entries).size,
            countComplete = snapshot.complete,
        )
    }

    suspend fun browse(itemId: String?, relativePath: String = ""): Result<List<TrashBrowserEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedPath = TrashPathRules.normalize(relativePath)
                if (itemId == null) {
                    require(normalizedPath.isEmpty()) { "Šiukšliadėžės šaknies kelias turi būti tuščias" }
                    return@runCatching listInternal().map(::toRootBrowserEntry)
                }

                val item = listInternal().firstOrNull { it.id == itemId }
                    ?: throw IllegalArgumentException("Šiukšliadėžės elementas neberastas")
                val storedRoot = File(item.storedPath).canonicalFile
                require(FileSystemRules.isContained(root, storedRoot)) { "Netinkamas šiukšliadėžės turinio kelias" }
                require(storedRoot.isDirectory) { "Šiukšliadėžės elementas nėra katalogas" }
                val directory = if (normalizedPath.isEmpty()) storedRoot else File(storedRoot, normalizedPath).canonicalFile
                require(FileSystemRules.isContained(storedRoot, directory)) { "Katalogas išeina už šiukšliadėžės elemento ribų" }
                require(directory.isDirectory) { "Šiukšliadėžės katalogas neberastas" }
                val children = directory.listFiles() ?: throw SecurityException("Šiukšliadėžės katalogas neperskaitomas")
                require(children.size <= MAX_DIRECTORY_ENTRIES) { "Šiukšliadėžės kataloge per daug elementų" }
                children.map { child ->
                    require(FileSystemRules.isContained(storedRoot, child)) { "Nesaugus šiukšliadėžės katalogo elementas" }
                    val childRelativePath = TrashPathRules.child(normalizedPath, child.name)
                    TrashBrowserEntry(
                        itemId = item.id,
                        relativePath = childRelativePath,
                        storedPath = child.absolutePath,
                        name = child.name,
                        kind = FileSystemRules.detectKind(child.name, mimeType = null, isDirectory = child.isDirectory),
                        sizeBytes = if (child.isFile) child.length().coerceAtLeast(0) else 0,
                        modifiedAtMillis = child.lastModified().coerceAtLeast(0),
                        isReadable = child.canRead(),
                        deletedAtMillis = null,
                        originalPath = null,
                        topLevel = false,
                    )
                }.sortedWith(compareByDescending<TrashBrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
    }

    suspend fun moveToTrash(paths: List<String>, operation: OperationContext): TrashMoveResult = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            require(paths.isNotEmpty()) { "Nepasirinkta failų" }
            val requested = paths.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(MAX_TRASH_ITEMS + 1)
                .toList()
            require(requested.isNotEmpty()) { "Nepasirinkta failų" }
            require(requested.size <= MAX_TRASH_ITEMS) { "Per daug elementų" }

            val failures = mutableListOf<String>()
            val canonicalSources = requested.mapNotNull { path ->
                try {
                    File(path).canonicalFile
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += failureMessage(error)
                    null
                }
            }
            val sources = compactSourceRoots(canonicalSources)
            val scans = linkedMapOf<File, Pair<Int, Long>>()
            var totalItems = 0
            var totalBytes = 0L
            sources.forEach { source ->
                operation.checkpoint()
                operation.progress(currentName = source.name)
                try {
                    require(source.exists()) { "Failas nebeegzistuoja: ${source.name}" }
                    val sourceScan = scan(source)
                    totalItems = Math.addExact(totalItems, sourceScan.first)
                    totalBytes = Math.addExact(totalBytes, sourceScan.second)
                    scans[source] = sourceScan
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += failureMessage(error)
                }
            }
            operation.setTotals(
                totalItems,
                totalBytes,
            )
            var movedItems = 0
            scans.forEach { (source, sourceScan) ->
                operation.checkpoint()
                try {
                    movePreparedSource(source, sourceScan, operation)
                    operation.progress(itemDelta = sourceScan.first, byteDelta = sourceScan.second, currentName = source.name)
                    movedItems += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += failureMessage(error)
                }
            }
            if (failures.isNotEmpty()) {
                if (movedItems == 0) throw IllegalStateException(failures.first())
                operation.completeWithErrors(failures.size, failures.first())
            }
            TrashMoveResult(movedItems = movedItems, failedItems = failures.size)
        }
    }

    private suspend fun movePreparedSource(
        source: File,
        sourceScan: Pair<Int, Long>,
        operation: OperationContext,
    ) {
        val id = UUID.randomUUID().toString()
        val stored = File(root, "$id.payload")
        val partial = File(root, "$id.partial")
        val sourceWasDirectory = source.isDirectory
        val movedByRename = source.renameTo(stored)

        if (movedByRename) {
            val item = trashItem(id, source, stored, sourceScan, sourceWasDirectory)
            try {
                writeMetadata(item)
            } catch (error: Throwable) {
                if (!source.exists() && stored.exists()) runCatching { stored.renameTo(source) }
                throw error
            }
            return
        }

        try {
            copyTree(source, partial, operation, 0)
            val copied = scan(partial)
            require(copied == sourceScan) { "Šiukšlinės kopija nepatikrinta" }
            require(partial.renameTo(stored)) { "Nepavyko užbaigti šiukšlinės kopijos" }
        } catch (error: Throwable) {
            cleanupQuietly(partial)
            throw error
        }

        val item = trashItem(id, source, stored, sourceScan, sourceWasDirectory)
        try {
            writeMetadata(item)
        } catch (error: Throwable) {
            cleanupQuietly(stored)
            throw error
        }
        // Keep the verified Trash copy and its metadata if source cleanup fails.
        // That is visible and recoverable instead of becoming an orphaned payload.
        deleteTree(source, source, 0, budget = EntryBudget(MAX_SCAN_ENTRIES))
    }

    private fun compactSourceRoots(sources: List<File>): List<File> {
        val ordered = sources.distinctBy(File::getPath)
            .sortedWith(compareBy<File>({ it.toPath().nameCount }, { it.path }))
        val accepted = ArrayList<File>(ordered.size)
        ordered.forEach { candidate ->
            val candidatePath = candidate.toPath().normalize()
            if (accepted.none { parent -> candidatePath.startsWith(parent.toPath().normalize()) }) accepted += candidate
        }
        return accepted
    }

    private fun failureMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).take(500)

    suspend fun restore(id: String): Result<String> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            runCatching {
                val metadata = File(root, "$id.json")
                val item = readMetadata(metadata)
                val stored = File(item.storedPath)
                require(stored.exists()) { "Šiukšlinės turinys neberastas" }
                val requested = File(item.originalPath)
                requested.parentFile?.mkdirs()
                val target = if (requested.exists()) FileSystemRules.keepBothTarget(requested) else requested
                require(stored.renameTo(target)) { "Atkurti nepavyko" }
                check(metadata.delete() || !metadata.exists()) { "Nepavyko pašalinti metaduomenų" }
                target.absolutePath
            }
        }
    }

    suspend fun deleteForever(id: String, operation: OperationContext? = null): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            runCatching {
                val metadata = File(root, "$id.json")
                val item = readMetadata(metadata)
                val stored = File(item.storedPath)
                val totals = if (stored.exists()) scan(stored) else 0 to 0L
                operation?.setTotals(totals.first, totals.second)
                deleteTrackedItem(metadata, item, operation, EntryBudget(MAX_SCAN_ENTRIES))
            }
        }
    }

    suspend fun emptyAll(operation: OperationContext? = null): EmptyTrashResult = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            var deleted = 0
            var failed = 0
            val failedGroupKeys = mutableSetOf<String>()
            val deletionBudget = EntryBudget(MAX_SCAN_ENTRIES)
            var snapshot = rootSnapshot()
            if (snapshot.complete) {
                val totals = scanAll(snapshot.entries, operation)
                operation?.setTotals(totals.first, totals.second)
            } else {
                operation?.setTotals(null, null)
            }

            while (snapshot.entries.isNotEmpty()) {
                val candidates = snapshot.entries.filterNot { logicalRootKey(it.name) in failedGroupKeys }
                if (candidates.isEmpty()) break
                groupRootEntries(candidates).forEach { group ->
                    val groupKey = logicalRootKey(group.first().name)
                    operation?.checkpoint()
                    var groupFailed = false
                    for (entry in group.sortedWith(compareBy<File>(::cleanupPriority).thenBy(File::getName))) {
                        if (!entry.exists() && !Files.isSymbolicLink(entry.toPath())) continue
                        try {
                            deleteTree(root, entry, 0, operation, deletionBudget)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            failedGroupKeys += groupKey
                            groupFailed = true
                            break
                        }
                    }
                    if (groupFailed) failed += 1 else deleted += 1
                }
                snapshot = rootSnapshot()
                if (snapshot.complete && snapshot.entries.all { logicalRootKey(it.name) in failedGroupKeys }) break
            }
            EmptyTrashResult(deletedItems = deleted, failedItems = failed)
        }
    }

    suspend fun deleteExpired(
        period: TrashRetentionPeriod,
        nowMillis: Long = System.currentTimeMillis(),
    ): EmptyTrashResult = withContext(Dispatchers.IO) {
        val days = period.days ?: return@withContext EmptyTrashResult(0, 0)
        require(nowMillis >= 0L) { "Netinkamas dabartinis laikas" }
        val cutoff = (nowMillis - days * MILLIS_PER_DAY).coerceAtLeast(0L)
        mutationMutex.withLock {
            var deleted = 0
            var failed = 0
            val budget = EntryBudget(MAX_SCAN_ENTRIES)
            metadataItems()
                .filter { (_, item) -> item.deletedAtMillis <= cutoff }
                .forEach { (metadata, item) ->
                    try {
                        deleteTrackedItem(metadata, item, budget = budget)
                        deleted += 1
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        failed += 1
                    }
                }
            EmptyTrashResult(deleted, failed)
        }
    }

    private fun listInternal(): List<TrashItem> = metadataItems()
        .map { it.second }
        .sortedByDescending(TrashItem::deletedAtMillis)

    private fun metadataItems(): List<Pair<File, TrashItem>> = root.listFiles { file -> file.extension == "json" }
        ?.take(MAX_TRASH_ITEMS)
        ?.mapNotNull { metadata -> runCatching { metadata to readMetadata(metadata) }.getOrNull() }
        .orEmpty()

    private fun rootSnapshot(): RootSnapshot {
        val entries = ArrayList<File>(minOf(MAX_ROOT_ENTRIES_PER_PASS, 1_024))
        var complete = true
        Files.newDirectoryStream(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (entries.size == MAX_ROOT_ENTRIES_PER_PASS) {
                    complete = false
                    break
                }
                val entry = iterator.next().toFile().absoluteFile
                require(entry.parentFile == root.absoluteFile) { "Netinkamas šiukšlinės kelias" }
                entries += entry
            }
        }
        return RootSnapshot(entries.sortedBy(File::getName), complete)
    }

    private fun groupRootEntries(entries: List<File>): List<List<File>> = entries
        .groupBy { file -> logicalRootKey(file.name) }
        .toSortedMap()
        .values
        .toList()

    private fun logicalRootKey(name: String): String {
        val id = when {
            name.endsWith(".json.partial") -> name.removeSuffix(".json.partial")
            name.endsWith(".payload") -> name.removeSuffix(".payload")
            name.endsWith(".partial") -> name.removeSuffix(".partial")
            name.endsWith(".json") -> name.removeSuffix(".json")
            else -> return "entry:$name"
        }
        return if (id.isBlank()) "entry:$name" else "trash:$id"
    }

    private fun cleanupPriority(file: File): Int = when {
        file.name.endsWith(".json") -> 2
        file.name.endsWith(".json.partial") -> 1
        else -> 0
    }

    private fun trashItem(
        id: String,
        source: File,
        stored: File,
        sourceScan: Pair<Int, Long>,
        sourceWasDirectory: Boolean,
    ): TrashItem = TrashItem(
        id = id,
        originalPath = source.absolutePath,
        storedPath = stored.absolutePath,
        deletedAtMillis = System.currentTimeMillis(),
        sizeBytes = sourceScan.second,
        directory = sourceWasDirectory,
    )

    private fun toRootBrowserEntry(item: TrashItem): TrashBrowserEntry {
        val stored = File(item.storedPath)
        val name = File(item.originalPath).name.ifBlank { item.originalPath }
        return TrashBrowserEntry(
            itemId = item.id,
            relativePath = "",
            storedPath = stored.absolutePath,
            name = name,
            kind = FileSystemRules.detectKind(name, mimeType = null, isDirectory = item.directory),
            sizeBytes = item.sizeBytes.coerceAtLeast(0),
            modifiedAtMillis = stored.lastModified().coerceAtLeast(0),
            isReadable = stored.canRead(),
            deletedAtMillis = item.deletedAtMillis,
            originalPath = item.originalPath,
            topLevel = true,
        )
    }

    private suspend fun deleteTrackedItem(
        metadata: File,
        item: TrashItem,
        operation: OperationContext? = null,
        budget: EntryBudget? = null,
    ) {
        require(FileSystemRules.isContained(root, metadata)) { "Netinkamas šiukšliadėžės metaduomenų kelias" }
        val stored = File(item.storedPath)
        require(FileSystemRules.isContained(root, stored)) { "Netinkamas šiukšliadėžės turinio kelias" }
        if (stored.exists()) deleteTree(stored, stored, 0, operation, budget)
        check(metadata.delete() || !metadata.exists()) { "Nepavyko pašalinti metaduomenų" }
    }

    private fun writeMetadata(item: TrashItem) {
        val target = File(root, "${item.id}.json")
        val temporary = File(root, "${item.id}.json.partial")
        val json = JSONObject()
            .put("id", item.id)
            .put("originalPath", item.originalPath)
            .put("storedPath", item.storedPath)
            .put("deletedAtMillis", item.deletedAtMillis)
            .put("sizeBytes", item.sizeBytes)
            .put("directory", item.directory)
        try {
            temporary.writeText(json.toString(), Charsets.UTF_8)
            require(temporary.renameTo(target)) { "Nepavyko įrašyti šiukšlinės metaduomenų" }
        } catch (error: Throwable) {
            runCatching { temporary.delete() }
            throw error
        }
    }

    private fun readMetadata(file: File): TrashItem {
        require(FileSystemRules.isContained(root, file)) { "Netinkamas šiukšlinės kelias" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val storedPath = json.getString("storedPath")
        require(FileSystemRules.isContained(root, File(storedPath))) { "Netinkamas šiukšlinės turinio kelias" }
        return TrashItem(
            id = json.getString("id"),
            originalPath = json.getString("originalPath"),
            storedPath = storedPath,
            deletedAtMillis = json.getLong("deletedAtMillis"),
            sizeBytes = json.getLong("sizeBytes"),
            directory = json.getBoolean("directory"),
        )
    }

    private suspend fun copyTree(source: File, target: File, operation: OperationContext, depth: Int) {
        require(depth <= 64) { "Per gilus aplankų medis" }
        operation.checkpoint()
        if (source.isDirectory) {
            require(target.mkdir()) { "Nepavyko sukurti laikino aplanko" }
            source.listFiles()?.forEach { copyTree(it, File(target, it.name), operation, depth + 1) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        } else {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        operation.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private suspend fun deleteTree(
        containmentRoot: File,
        file: File,
        depth: Int,
        operation: OperationContext? = null,
        budget: EntryBudget? = null,
    ) {
        require(depth <= 64) { "Per gilus aplankų medis" }
        operation?.checkpoint()
        budget?.claim()
        val rootPath = containmentRoot.absoluteFile.toPath().normalize()
        val candidatePath = file.absoluteFile.toPath().normalize()
        require(candidatePath.startsWith(rootPath)) { "Trynimo kelias išeina už leistinos ribos" }
        if (Files.isSymbolicLink(candidatePath)) {
            require(file.delete()) { "Nepavyko ištrinti ${file.name}" }
            operation?.progress(itemDelta = 1, currentName = file.name)
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(containmentRoot, it, depth + 1, operation, budget) }
        val bytes = if (file.isFile) file.length().coerceAtLeast(0L) else 0L
        require(file.delete()) { "Nepavyko ištrinti ${file.name}" }
        operation?.progress(itemDelta = 1, byteDelta = bytes, currentName = file.name)
    }

    private fun scan(rootFile: File): Pair<Int, Long> {
        var entries = 0
        var bytes = 0L
        val pending = ArrayDeque<File>()
        pending.add(rootFile)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            entries += 1
            require(entries <= MAX_SCAN_ENTRIES) { "Per daug elementų" }
            val currentPath = current.toPath()
            if (Files.isSymbolicLink(currentPath)) continue
            if (current.isDirectory) current.listFiles()?.forEach(pending::add)
                ?: throw SecurityException("Aplankas neperskaitomas")
            else bytes = Math.addExact(bytes, current.length())
        }
        return entries to bytes
    }

    private suspend fun scanAll(files: List<File>, operation: OperationContext?): Pair<Int, Long> {
        var entries = 0
        var bytes = 0L
        val pending = ArrayDeque<File>()
        files.asReversed().forEach(pending::add)
        while (pending.isNotEmpty()) {
            operation?.checkpoint()
            val current = pending.removeLast()
            entries += 1
            require(entries <= MAX_SCAN_ENTRIES) { "Per daug elementų" }
            val currentPath = current.toPath()
            if (Files.isSymbolicLink(currentPath)) continue
            if (current.isDirectory) current.listFiles()?.forEach(pending::add)
                ?: throw SecurityException("Aplankas neperskaitomas")
            else bytes = Math.addExact(bytes, current.length())
        }
        return entries to bytes
    }

    private suspend fun cleanupQuietly(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        runCatching { deleteTree(file, file, 0, budget = EntryBudget(MAX_SCAN_ENTRIES)) }
    }
}
