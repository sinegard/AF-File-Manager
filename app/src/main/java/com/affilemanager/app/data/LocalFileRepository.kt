package com.affilemanager.app.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

data class DirectoryListingUpdate(
    val entries: List<FileEntry>,
    val scannedEntries: Int,
    val metadataEntries: Int,
    val complete: Boolean,
    val truncated: Boolean,
)

internal object ProgressiveListingPolicy {
    const val MAX_VISIBLE_ENTRIES = 100_000
    const val MAX_SCANNED_ENTRIES = 200_000
    private const val EARLY_BATCH = 256
    private const val LATER_BATCH = 4_096

    fun shouldPublish(count: Int): Boolean = count == 1 ||
        (count <= LATER_BATCH && count % EARLY_BATCH == 0) ||
        count % LATER_BATCH == 0
}

class LocalFileRepository(private val context: Context) {
    suspend fun roots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val roots = storageManager.storageVolumes.mapNotNull { volume ->
            val directory = if (android.os.Build.VERSION.SDK_INT >= 30) volume.directory else null
            directory?.takeIf(File::exists)?.let { root ->
                StorageRoot(
                    id = volume.uuid ?: "primary",
                    title = if (volume.isPrimary) "Vidinė atmintis" else volume.getDescription(context),
                    path = root.absolutePath,
                    totalBytes = root.totalSpace.coerceAtLeast(0),
                    freeBytes = root.usableSpace.coerceAtLeast(0),
                    removable = volume.isRemovable,
                )
            }
        }.toMutableList()

        if (roots.none { it.path == Environment.getExternalStorageDirectory().absolutePath }) {
            val root = Environment.getExternalStorageDirectory()
            roots.add(
                0,
                StorageRoot(
                    id = "primary",
                    title = "Vidinė atmintis",
                    path = root.absolutePath,
                    totalBytes = root.totalSpace.coerceAtLeast(0),
                    freeBytes = root.usableSpace.coerceAtLeast(0),
                    removable = false,
                ),
            )
        }
        roots.distinctBy(StorageRoot::path)
    }

    suspend fun list(
        directoryPath: String,
        includeHidden: Boolean,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): Result<List<FileEntry>> = listProgressively(
        directoryPath = directoryPath,
        includeHidden = includeHidden,
        sortMode = sortMode,
        sortDirection = sortDirection,
        onProgress = {},
    )

    suspend fun listProgressively(
        directoryPath: String,
        includeHidden: Boolean,
        sortMode: SortMode,
        sortDirection: SortDirection,
        onProgress: (DirectoryListingUpdate) -> Unit,
    ): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        try {
            val directory = File(directoryPath).canonicalFile
            require(directory.isDirectory) { "Tai nėra aplankas" }
            val files = ArrayList<File>()
            val basicEntries = ArrayList<FileEntry>()
            var scanned = 0
            var truncated = false

            Files.newDirectoryStream(directory.toPath()).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    if (scanned % 128 == 0) coroutineContext.ensureActive()
                    val child = iterator.next().toFile()
                    scanned = Math.addExact(scanned, 1)
                    if (scanned > ProgressiveListingPolicy.MAX_SCANNED_ENTRIES) {
                        truncated = true
                        break
                    }
                    if (!includeHidden && child.isHidden) continue
                    if (files.size >= ProgressiveListingPolicy.MAX_VISIBLE_ENTRIES) {
                        truncated = true
                        break
                    }
                    files += child
                    basicEntries += toBasicEntry(child)
                    if (ProgressiveListingPolicy.shouldPublish(basicEntries.size)) {
                        onProgress(
                            DirectoryListingUpdate(
                                entries = orderedSnapshot(basicEntries, sortMode, sortDirection),
                                scannedEntries = scanned,
                                metadataEntries = 0,
                                complete = false,
                                truncated = false,
                            ),
                        )
                    }
                }
            }

            if (basicEntries.isNotEmpty()) {
                onProgress(
                    DirectoryListingUpdate(
                        entries = orderedSnapshot(basicEntries, sortMode, sortDirection),
                        scannedEntries = scanned,
                        metadataEntries = 0,
                        complete = false,
                        truncated = truncated,
                    ),
                )
            }

            val entries = ArrayList<FileEntry>(files.size)
            files.forEachIndexed { index, child ->
                if (index % 128 == 0) coroutineContext.ensureActive()
                entries += toEntry(child)
                val ready = index + 1
                if (ProgressiveListingPolicy.shouldPublish(ready)) {
                    val combined = ArrayList<FileEntry>(files.size).apply {
                        addAll(entries)
                        for (pendingIndex in ready until basicEntries.size) add(basicEntries[pendingIndex])
                    }
                    onProgress(
                        DirectoryListingUpdate(
                            entries = orderedSnapshot(combined, sortMode, sortDirection),
                            scannedEntries = scanned,
                            metadataEntries = ready,
                            complete = false,
                            truncated = truncated,
                        ),
                    )
                }
            }

            val ordered = orderEntries(entries, sortMode, sortDirection)
            onProgress(
                DirectoryListingUpdate(
                    entries = ordered,
                    scannedEntries = scanned,
                    metadataEntries = ordered.size,
                    complete = true,
                    truncated = truncated,
                ),
            )
            Result.success(ordered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun orderedSnapshot(
        entries: List<FileEntry>,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): List<FileEntry> = FileEntryOrdering.order(ArrayList(entries), sortMode, sortDirection)

    private fun orderEntries(
        entries: List<FileEntry>,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): List<FileEntry> = FileEntryOrdering.order(entries, sortMode, sortDirection)

    suspend fun createDirectory(parentPath: String, requestedName: String): Result<FileEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val name = FileSystemRules.validateFileName(requestedName).getOrThrow()
            val parent = File(parentPath).canonicalFile
            require(parent.isDirectory) { "Tėvinis aplankas neegzistuoja" }
            val target = File(parent, name)
            require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
            check(target.mkdir()) { "Nepavyko sukurti aplanko" }
            toEntry(target)
        }
    }

    suspend fun createEmptyFile(parentPath: String, requestedName: String): Result<FileEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val name = FileSystemRules.validateFileName(requestedName).getOrThrow()
            val parent = File(parentPath).canonicalFile
            require(parent.isDirectory) { "Tėvinis aplankas neegzistuoja" }
            val target = File(parent, name)
            require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
            check(target.createNewFile()) { "Nepavyko sukurti failo" }
            toEntry(target)
        }
    }

    suspend fun rename(path: String, requestedName: String): Result<FileEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(path).canonicalFile
            require(source.exists()) { "Failas nebeegzistuoja" }
            val name = FileSystemRules.validateFileName(requestedName).getOrThrow()
            val parent = source.parentFile ?: throw IllegalArgumentException("Šakninio aplanko pervadinti negalima")
            val target = File(parent, name)
            require(!target.exists()) { "Toks pavadinimas jau naudojamas" }
            check(source.renameTo(target)) { "Pervadinti nepavyko" }
            toEntry(target)
        }
    }

    suspend fun readText(path: String, maxBytes: Long = 2L * 1_024 * 1_024): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            require(file.isFile) { "Failas nepasiekiamas" }
            require(file.length() <= maxBytes) { "Failas per didelis vidiniam redaktoriui" }
            file.readText(Charsets.UTF_8)
        }
    }

    suspend fun writeText(path: String, content: String, maxBytes: Int = 2 * 1_024 * 1_024): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = content.toByteArray(Charsets.UTF_8)
            try {
                require(bytes.size <= maxBytes) { "Turinys per didelis vidiniam redaktoriui" }
                val file = File(path)
                require(file.isFile && file.canWrite()) { "Failo negalima įrašyti" }
                val temporary = File(file.parentFile, ".${file.name}.af.tmp")
                try {
                    temporary.outputStream().use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
                Unit
            } finally {
                bytes.fill(0)
            }
        }
    }

    suspend fun calculateDirectorySize(path: String, maxEntries: Int = 200_000): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(path)
            var total = 0L
            var visited = 0
            val pending = ArrayDeque<File>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val current = pending.removeLast()
                visited += 1
                require(visited <= maxEntries) { "Aplankas viršijo skenavimo ribą" }
                if (current.isDirectory) {
                    current.listFiles()?.forEach(pending::add)
                } else {
                    total = Math.addExact(total, current.length().coerceAtLeast(0))
                }
            }
            total
        }
    }

    fun toEntry(file: File): FileEntry = FileEntry(
        absolutePath = file.absolutePath,
        name = file.name.ifBlank { file.absolutePath },
        kind = FileSystemRules.detectKind(file),
        sizeBytes = if (file.isFile) file.length().coerceAtLeast(0) else 0,
        modifiedAtMillis = file.lastModified().coerceAtLeast(0),
        isHidden = file.isHidden,
        isReadable = file.canRead(),
        isWritable = file.canWrite(),
    )

    private fun toBasicEntry(file: File): FileEntry {
        val directory = file.isDirectory
        return FileEntry(
            absolutePath = file.absolutePath,
            name = file.name.ifBlank { file.absolutePath },
            kind = FileSystemRules.detectKind(file.name, mimeType = null, isDirectory = directory),
            sizeBytes = 0,
            modifiedAtMillis = 0,
            isHidden = file.isHidden,
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
            metadataComplete = false,
        )
    }
}
