package com.affilemanager.app.data

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Environment
import android.os.storage.StorageManager
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
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

internal object DirectorySizePolicy {
    const val MAX_TOTAL_SCANNED_NODES = 50_000
    const val MAX_DEPTH = 64
    const val MAX_PROGRESS_UPDATES = 24

    fun publishEvery(directoryCount: Int): Int = maxOf(
        8,
        (directoryCount.coerceAtLeast(1) + MAX_PROGRESS_UPDATES - 1) / MAX_PROGRESS_UPDATES,
    )
}

private data class DirectorySizeBudget(var remainingNodes: Int)

private data class DirectorySizeResult(
    val bytes: Long,
    val complete: Boolean,
)

internal object StorageRootClassifier {
    private val usbToken = Regex("(^|[^a-z0-9])(usb|otg)([^a-z0-9]|$)")
    private val sdToken = Regex("(^|[^a-z0-9])(sd|memory[ _-]?card)([^a-z0-9]|$)")

    fun classify(
        primary: Boolean,
        removable: Boolean,
        description: String,
        path: String,
        usbMassStorageConnected: Boolean,
        removableVolumeCount: Int,
    ): StorageRootKind {
        if (primary || !removable) return StorageRootKind.INTERNAL
        val identity = "$description $path".lowercase(Locale.ROOT)
        return when {
            usbToken.containsMatchIn(identity) -> StorageRootKind.USB_STORAGE
            sdToken.containsMatchIn(identity) -> StorageRootKind.SD_CARD
            usbMassStorageConnected && removableVolumeCount == 1 -> StorageRootKind.USB_STORAGE
            else -> StorageRootKind.REMOVABLE
        }
    }
}

internal object StorageVolumeMountPolicy {
    fun isVisible(state: String, directoryExists: Boolean): Boolean = directoryExists &&
        state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
}

class LocalFileRepository(private val context: Context) {
    suspend fun roots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val mountedVolumes = storageManager.storageVolumes.mapNotNull { volume ->
            val directory = if (android.os.Build.VERSION.SDK_INT >= 30) volume.directory else null
            directory
                ?.takeIf { root -> StorageVolumeMountPolicy.isVisible(volume.state, root.exists()) }
                ?.let { root -> volume to root }
        }
        val removableVolumeCount = mountedVolumes.count { (volume, _) -> volume.isRemovable && !volume.isPrimary }
        val usbMassStorageConnected = runCatching { hasUsbMassStorageDevice() }.getOrDefault(false)
        val roots = mountedVolumes.map { (volume, root) ->
            val description = volume.getDescription(context)
            StorageRoot(
                id = volume.uuid ?: "primary",
                title = if (volume.isPrimary) "Vidinė atmintis" else description,
                path = root.absolutePath,
                totalBytes = root.totalSpace.coerceAtLeast(0),
                freeBytes = root.usableSpace.coerceAtLeast(0),
                removable = volume.isRemovable,
                kind = StorageRootClassifier.classify(
                    primary = volume.isPrimary,
                    removable = volume.isRemovable,
                    description = description,
                    path = root.absolutePath,
                    usbMassStorageConnected = usbMassStorageConnected,
                    removableVolumeCount = removableVolumeCount,
                ),
            )
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
                    kind = StorageRootKind.INTERNAL,
                ),
            )
        }
        roots.distinctBy(StorageRoot::path)
    }

    private fun hasUsbMassStorageDevice(): Boolean {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return false
        return usbManager.deviceList.values.any { device ->
            device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                }
        }
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
            var rootEnumerationError: Throwable? = null

            try {
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
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val eligibleRootFailure = directory.absolutePath == File.separator &&
                    (error is IOException || error is SecurityException)
                if (!eligibleRootFailure) throw error

                rootEnumerationError = error
            }

            if (directory.absolutePath == File.separator && basicEntries.isEmpty()) {
                val detected = RootDirectoryFallback.existingChildren(directory)
                    .filter { includeHidden || !it.isHidden }
                if (detected.isEmpty()) {
                    rootEnumerationError?.let { throw it }
                } else {
                    files.clear()
                    basicEntries.clear()
                    files.addAll(detected)
                    basicEntries.addAll(detected.map(::toBasicEntry))
                    scanned = detected.size
                    // The fallback is intentionally bounded and cannot claim to enumerate vendor-specific names.
                    truncated = true
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
                val entry = toEntry(child)
                entries += if (sortMode == SortMode.SIZE && entry.isDirectory) {
                    entry.copy(sizeBytes = 0L, metadataComplete = false)
                } else {
                    entry
                }
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

            val finalEntries = if (sortMode == SortMode.SIZE && entries.any(FileEntry::isDirectory)) {
                val sizeEntries = entries.mapTo(ArrayList(entries.size)) { entry ->
                    if (entry.isDirectory) entry.copy(sizeBytes = 0L, metadataComplete = false) else entry
                }
                onProgress(
                    DirectoryListingUpdate(
                        entries = orderedSnapshot(sizeEntries, sortMode, sortDirection),
                        scannedEntries = scanned,
                        metadataEntries = sizeEntries.count(FileEntry::metadataComplete),
                        complete = false,
                        truncated = truncated,
                    ),
                )
                val budget = DirectorySizeBudget(DirectorySizePolicy.MAX_TOTAL_SCANNED_NODES)
                val directoryIndexes = sizeEntries.indices.filter { sizeEntries[it].isDirectory }
                val publishEvery = DirectorySizePolicy.publishEvery(directoryIndexes.size)
                var processedDirectories = 0
                for (entryIndex in directoryIndexes) {
                    if (budget.remainingNodes <= 0) break
                    coroutineContext.ensureActive()
                    val result = calculateDirectorySizeBounded(
                        root = File(sizeEntries[entryIndex].absolutePath).toPath(),
                        budget = budget,
                    )
                    if (result.complete) {
                        sizeEntries[entryIndex] = sizeEntries[entryIndex].copy(
                            sizeBytes = result.bytes,
                            metadataComplete = true,
                        )
                    }
                    processedDirectories += 1
                    if (
                        processedDirectories % publishEvery == 0 ||
                        processedDirectories == directoryIndexes.size ||
                        budget.remainingNodes <= 0
                    ) {
                        onProgress(
                            DirectoryListingUpdate(
                                entries = orderedSnapshot(sizeEntries, sortMode, sortDirection),
                                scannedEntries = scanned,
                                metadataEntries = sizeEntries.count(FileEntry::metadataComplete),
                                complete = false,
                                truncated = truncated,
                            ),
                        )
                    }
                }
                sizeEntries
            } else {
                entries
            }

            val ordered = orderEntries(finalEntries, sortMode, sortDirection)
            onProgress(
                DirectoryListingUpdate(
                    entries = ordered,
                    scannedEntries = scanned,
                    metadataEntries = ordered.count(FileEntry::metadataComplete),
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

    private suspend fun calculateDirectorySizeBounded(
        root: Path,
        budget: DirectorySizeBudget,
    ): DirectorySizeResult {
        if (budget.remainingNodes <= 0) return DirectorySizeResult(0L, complete = false)
        var bytes = 0L
        val pending = ArrayDeque<Pair<Path, Int>>()
        budget.remainingNodes -= 1
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (current, depth) = pending.removeLast()
            if (Files.isSymbolicLink(current)) continue
            try {
                if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (depth >= DirectorySizePolicy.MAX_DEPTH) {
                        return DirectorySizeResult(bytes, complete = false)
                    }
                    Files.newDirectoryStream(current).use { stream ->
                        val iterator = stream.iterator()
                        while (iterator.hasNext()) {
                            coroutineContext.ensureActive()
                            if (budget.remainingNodes <= 0) return DirectorySizeResult(bytes, complete = false)
                            budget.remainingNodes -= 1
                            pending.add(iterator.next() to depth + 1)
                        }
                    }
                } else if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = Math.addExact(bytes, Files.size(current).coerceAtLeast(0L))
                }
            } catch (_: SecurityException) {
                return DirectorySizeResult(bytes, complete = false)
            } catch (_: java.io.IOException) {
                return DirectorySizeResult(bytes, complete = false)
            } catch (_: ArithmeticException) {
                return DirectorySizeResult(Long.MAX_VALUE, complete = false)
            }
        }
        return DirectorySizeResult(bytes, complete = true)
    }

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
