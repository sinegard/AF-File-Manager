package com.affilemanager.app.data

import android.content.Context
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
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
        private const val MAX_DIRECTORY_ENTRIES = 50_000
        private const val COPY_BUFFER = 256 * 1_024
    }

    private val root: File by lazy {
        (configuredRoot ?: requireNotNull(context.getExternalFilesDir("trash")) { "Šiukšliadėžės vieta nepasiekiama" })
            .apply { require(isDirectory || mkdirs()) { "Šiukšliadėžės vietos sukurti nepavyko" } }
    }

    suspend fun list(): List<TrashItem> = withContext(Dispatchers.IO) {
        listInternal()
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

    suspend fun moveToTrash(paths: List<String>, operation: OperationContext) = withContext(Dispatchers.IO) {
        require(paths.isNotEmpty()) { "Nepasirinkta failų" }
        paths.distinct().forEach { sourcePath ->
            operation.checkpoint()
            val source = File(sourcePath).canonicalFile
            require(source.exists()) { "Failas nebeegzistuoja: ${source.name}" }
            val id = UUID.randomUUID().toString()
            val stored = File(root, "$id.payload")
            val partial = File(root, "$id.partial")
            val scan = scan(source)
            operation.setTotals(null, null)

            if (!source.renameTo(stored)) {
                copyTree(source, partial, operation, 0)
                val copied = scan(partial)
                require(copied == scan) { "Šiukšlinės kopija nepatikrinta" }
                require(partial.renameTo(stored)) { "Nepavyko užbaigti šiukšlinės kopijos" }
                deleteTree(source, source, 0)
            }

            val item = TrashItem(
                id = id,
                originalPath = source.absolutePath,
                storedPath = stored.absolutePath,
                deletedAtMillis = System.currentTimeMillis(),
                sizeBytes = scan.second,
                directory = source.isDirectory || stored.isDirectory,
            )
            writeMetadata(item)
            operation.progress(itemDelta = scan.first, byteDelta = scan.second, currentName = source.name)
        }
    }

    suspend fun restore(id: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = File(root, "$id.json")
            val item = readMetadata(metadata)
            val stored = File(item.storedPath)
            require(stored.exists()) { "Šiukšlinės turinys neberastas" }
            val requested = File(item.originalPath)
            requested.parentFile?.mkdirs()
            val target = if (requested.exists()) FileSystemRules.keepBothTarget(requested) else requested
            require(stored.renameTo(target)) { "Atkurti nepavyko" }
            metadata.delete()
            target.absolutePath
        }
    }

    suspend fun deleteForever(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = File(root, "$id.json")
            val item = readMetadata(metadata)
            deleteTrackedItem(metadata, item)
        }
    }

    suspend fun emptyAll(): EmptyTrashResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        metadataItems().forEach { (metadata, item) ->
            runCatching { deleteTrackedItem(metadata, item) }
                .onSuccess { deleted += 1 }
                .onFailure { failed += 1 }
        }
        EmptyTrashResult(deletedItems = deleted, failedItems = failed)
    }

    private fun listInternal(): List<TrashItem> = metadataItems()
        .map { it.second }
        .sortedByDescending(TrashItem::deletedAtMillis)

    private fun metadataItems(): List<Pair<File, TrashItem>> = root.listFiles { file -> file.extension == "json" }
        ?.take(MAX_TRASH_ITEMS)
        ?.mapNotNull { metadata -> runCatching { metadata to readMetadata(metadata) }.getOrNull() }
        .orEmpty()

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

    private fun deleteTrackedItem(metadata: File, item: TrashItem) {
        require(FileSystemRules.isContained(root, metadata)) { "Netinkamas šiukšliadėžės metaduomenų kelias" }
        val stored = File(item.storedPath)
        require(FileSystemRules.isContained(root, stored)) { "Netinkamas šiukšliadėžės turinio kelias" }
        if (stored.exists()) deleteTree(stored, stored, 0)
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
        temporary.writeText(json.toString(), Charsets.UTF_8)
        require(temporary.renameTo(target)) { "Nepavyko įrašyti šiukšlinės metaduomenų" }
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

    private fun deleteTree(containmentRoot: File, file: File, depth: Int) {
        require(depth <= 64) { "Per gilus aplankų medis" }
        val rootPath = containmentRoot.absoluteFile.toPath().normalize()
        val candidatePath = file.absoluteFile.toPath().normalize()
        require(candidatePath.startsWith(rootPath)) { "Trynimo kelias išeina už leistinos ribos" }
        if (Files.isSymbolicLink(candidatePath)) {
            require(file.delete()) { "Nepavyko ištrinti ${file.name}" }
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(containmentRoot, it, depth + 1) }
        require(file.delete()) { "Nepavyko ištrinti ${file.name}" }
    }

    private fun scan(rootFile: File): Pair<Int, Long> {
        var entries = 0
        var bytes = 0L
        val pending = ArrayDeque<File>()
        pending.add(rootFile)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            entries += 1
            require(entries <= 200_000) { "Per daug elementų" }
            if (current.isDirectory) current.listFiles()?.forEach(pending::add)
                ?: throw SecurityException("Aplankas neperskaitomas")
            else bytes = Math.addExact(bytes, current.length())
        }
        return entries to bytes
    }
}
