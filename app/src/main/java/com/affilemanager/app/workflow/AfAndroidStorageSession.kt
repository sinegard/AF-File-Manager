package com.affilemanager.app.workflow

import android.content.Context
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.archive.ArchiveEntryInfo
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProfileStore
import com.affilemanager.app.network.ReconnectingRemoteClient
import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteClientFactory
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class AfStorageSessionFactory(
    context: Context,
    private val archives: ArchiveEngine,
    private val profiles: NetworkProfileStore,
    private val clients: RemoteClientFactory,
) {
    private val stagingRoot = File(context.cacheDir, "af-workflow-staging")

    suspend fun open(): AfStorageSession = AfAndroidStorageSession(
        stagingRoot = stagingRoot,
        archives = archives,
        profiles = profiles,
        clients = clients,
    )
}

private class AfAndroidStorageSession(
    private val stagingRoot: File,
    private val archives: ArchiveEngine,
    private val profiles: NetworkProfileStore,
    private val clients: RemoteClientFactory,
) : AfStorageSession {
    private val openedClients = linkedMapOf<String, RemoteClient>()
    private var knownProfiles: Map<String, NetworkProfile>? = null
    private var closed = false

    override suspend fun enumerate(source: AfSourceRef): List<AfEnumeratedEntry> = withContext(Dispatchers.IO) {
        checkOpen()
        when (source.kind) {
            AfSourceKind.FILE_SYSTEM -> when (source.location.kind) {
                AfLocationKind.LOCAL -> enumerateLocal(source)
                AfLocationKind.REMOTE -> enumerateRemote(source)
            }
            AfSourceKind.ARCHIVE_ENTRY -> enumerateArchive(source)
        }
    }

    override suspend fun stat(location: AfLocationRef): AfNodeSnapshot? = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = location.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> {
                val file = File(normalized.path)
                if (!file.exists()) null else {
                    require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported" }
                    AfNodeSnapshot(
                        location = normalized,
                        name = file.name.ifBlank { file.path },
                        directory = file.isDirectory,
                        sizeBytes = if (file.isFile) file.length().coerceAtLeast(0) else 0,
                        modifiedAtMillis = file.lastModified().takeIf { it > 0 },
                    )
                }
            }
            AfLocationKind.REMOTE -> remoteStat(normalized)
        }
    }

    override suspend fun listChildren(directory: AfLocationRef): List<AfNodeSnapshot> = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = directory.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> {
                val folder = File(normalized.path)
                require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) { "Folder is unavailable" }
                val children = folder.listFiles() ?: throw SecurityException("Folder cannot be read")
                require(children.size <= MAX_REMOTE_DIRECTORY_ENTRIES) { "Folder contains too many entries" }
                children.map { child ->
                    require(!Files.isSymbolicLink(child.toPath())) { "Symbolic links are not supported" }
                    AfNodeSnapshot(
                        location = AfLocationRef.local(child.path),
                        name = child.name,
                        directory = child.isDirectory,
                        sizeBytes = if (child.isFile) child.length().coerceAtLeast(0) else 0,
                        modifiedAtMillis = child.lastModified().takeIf { it > 0 },
                    )
                }
            }
            AfLocationKind.REMOTE -> remoteClient(normalized).list(normalized.path).also {
                require(it.size <= MAX_REMOTE_DIRECTORY_ENTRIES) { "Remote folder contains too many entries" }
            }.map { remoteNode(normalized, it) }
        }
    }

    override suspend fun availableBytes(directory: AfLocationRef): Long? = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = directory.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> File(normalized.path).usableSpace.takeIf { it > 0 }
            AfLocationKind.REMOTE -> null
        }
    }

    override suspend fun stagingAvailableBytes(): Long? = withContext(Dispatchers.IO) {
        checkOpen()
        if (stagingRoot.isDirectory || stagingRoot.mkdirs()) stagingRoot.usableSpace else null
    }

    override suspend fun sha256(location: AfLocationRef, operation: OperationContext?): String = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = location.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> {
                val file = File(normalized.path)
                require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "File is unavailable for verification" }
                sha256(file, operation)
            }
            AfLocationKind.REMOTE -> {
                val snapshot = remoteStat(normalized) ?: error("Remote file is unavailable for verification")
                require(!snapshot.directory) { "A folder cannot be content-verified" }
                val temporary = newStagingFile("remote-hash")
                try {
                    requireStagingCapacity(snapshot.sizeBytes, "remote verification")
                    remoteClient(normalized).download(
                        normalized.path,
                        temporary,
                        operation,
                        snapshot.sizeBytes,
                    )
                    require(temporary.length() == snapshot.sizeBytes) { "Remote verification download size mismatch" }
                    sha256(temporary, null)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    override suspend fun sourceSha256(source: AfSourceRef, entry: AfEnumeratedEntry): String = withContext(Dispatchers.IO) {
        checkOpen()
        require(!entry.snapshot.directory) { "A folder cannot be content-verified" }
        when (source.kind) {
            AfSourceKind.FILE_SYSTEM -> sha256(entry.snapshot.location, null)
            AfSourceKind.ARCHIVE_ENTRY -> {
                val temporary = newStagingFile("archive-hash")
                try {
                    materialize(source, entry, temporary, OperationContext.background())
                    sha256(temporary, null)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    override suspend fun materialize(
        source: AfSourceRef,
        entry: AfEnumeratedEntry,
        destination: File,
        operation: OperationContext,
    ) = withContext(Dispatchers.IO) {
        checkOpen()
        require(!entry.snapshot.directory) { "A folder cannot be materialized as a file" }
        val stagingParent = requireNotNull(destination.parentFile) { "Private staging folder is unavailable" }
        require(stagingParent.isDirectory || stagingParent.mkdirs()) { "Private staging folder is unavailable" }
        requireStagingCapacity(entry.snapshot.sizeBytes, "copying")
        if (destination.exists()) require(destination.delete()) { "Could not reset private staging file" }
        when (source.kind) {
            AfSourceKind.ARCHIVE_ENTRY -> {
                val rootEntry = requireNotNull(source.archiveEntryPath)
                val selectedEntry = if (entry.relativePath.isEmpty()) rootEntry else "$rootEntry/${entry.relativePath}"
                archives.extractEntry(File(source.location.path), selectedEntry, destination, operation)
            }
            AfSourceKind.FILE_SYSTEM -> when (source.location.kind) {
                AfLocationKind.LOCAL -> copyLocalSource(entry, destination, operation)
                AfLocationKind.REMOTE -> {
                    remoteClient(entry.snapshot.location).download(
                        entry.snapshot.location.path,
                        destination,
                        operation,
                        entry.snapshot.sizeBytes,
                    )
                    require(destination.length() == entry.snapshot.sizeBytes) { "Remote source changed while copying" }
                }
            }
        }
    }

    override suspend fun createDirectory(location: AfLocationRef) = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = location.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> {
                val directory = File(normalized.path)
                require(directory.isDirectory || directory.mkdirs()) { "Could not create destination folder" }
            }
            AfLocationKind.REMOTE -> {
                val existing = remoteStat(normalized)
                when {
                    existing?.directory == true -> Unit
                    existing != null -> error("A file already occupies the destination folder")
                    else -> remoteClient(normalized).createDirectory(normalized.path)
                }
            }
        }
    }

    override suspend fun install(
        sourceFile: File,
        destination: AfLocationRef,
        replace: Boolean,
        operation: OperationContext,
    ) = withContext(Dispatchers.IO) {
        checkOpen()
        require(sourceFile.isFile && !Files.isSymbolicLink(sourceFile.toPath())) { "Private staged file is unavailable" }
        val normalized = destination.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> installLocal(sourceFile, File(normalized.path), replace, operation)
            AfLocationKind.REMOTE -> installRemote(sourceFile, normalized, replace, operation)
        }
    }

    override suspend fun delete(location: AfLocationRef, recursive: Boolean) = withContext(Dispatchers.IO) {
        checkOpen()
        val normalized = location.normalized()
        when (normalized.kind) {
            AfLocationKind.LOCAL -> deleteLocal(File(normalized.path), recursive)
            AfLocationKind.REMOTE -> remoteClient(normalized).delete(normalized.path, recursive)
        }
    }

    override suspend fun rename(from: AfLocationRef, to: AfLocationRef) = withContext(Dispatchers.IO) {
        checkOpen()
        val source = from.normalized()
        val target = to.normalized()
        require(source.kind == target.kind && source.profileId == target.profileId) { "Rename must stay in the same storage" }
        when (source.kind) {
            AfLocationKind.LOCAL -> {
                val fromFile = File(source.path)
                val toFile = File(target.path)
                require(fromFile.exists() && !toFile.exists()) { "Rename source or destination changed" }
                toFile.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Rename destination is unavailable" } }
                try {
                    Files.move(fromFile.toPath(), toFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(fromFile.toPath(), toFile.toPath())
                }
            }
            AfLocationKind.REMOTE -> remoteClient(source).rename(source.path, target.path)
        }
        Unit
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable + Dispatchers.IO) {
            openedClients.values.toList().asReversed().forEach { client -> runCatching { client.close() } }
            openedClients.clear()
            stagingRoot.listFiles { file -> file.name.startsWith("af-stage-") }?.forEach(File::delete)
        }
    }

    private suspend fun enumerateLocal(source: AfSourceRef): List<AfEnumeratedEntry> {
        val root = File(source.location.path).canonicalFile
        require(root.exists() && !Files.isSymbolicLink(root.toPath())) { "Local source is unavailable" }
        data class Pending(val file: File, val relative: String, val depth: Int)
        val pending = ArrayDeque<Pending>()
        val result = ArrayList<AfEnumeratedEntry>()
        pending.add(Pending(root, "", 0))
        while (pending.isNotEmpty()) {
            if (result.size % CANCELLATION_CHECK_ITEMS == 0) currentCoroutineContext().ensureActive()
            val current = pending.removeLast()
            require(current.depth <= AfWorkflowLimits.MAX_TREE_DEPTH) { "Source tree is too deep" }
            require(result.size < AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "Source tree is too large" }
            require(!Files.isSymbolicLink(current.file.toPath())) { "Symbolic links are not supported" }
            result += AfEnumeratedEntry(
                relativePath = current.relative,
                snapshot = AfNodeSnapshot(
                    location = AfLocationRef.local(current.file.path),
                    name = current.file.name.ifBlank { source.displayName },
                    directory = current.file.isDirectory,
                    sizeBytes = if (current.file.isFile) current.file.length().coerceAtLeast(0) else 0,
                    modifiedAtMillis = current.file.lastModified().takeIf { it > 0 },
                ),
                depth = current.depth,
            )
            if (current.file.isDirectory) {
                val children = current.file.listFiles()?.sortedBy(File::getName)
                    ?: throw SecurityException("Local source folder cannot be read")
                children.asReversed().forEach { child ->
                    val relative = if (current.relative.isEmpty()) child.name else "${current.relative}/${child.name}"
                    pending.add(Pending(child, relative, current.depth + 1))
                }
            }
        }
        return result
    }

    private suspend fun enumerateRemote(source: AfSourceRef): List<AfEnumeratedEntry> {
        val root = remoteStat(source.location) ?: error("Remote source is unavailable")
        data class Pending(val entry: AfNodeSnapshot, val relative: String, val depth: Int)
        val pending = ArrayDeque<Pending>()
        val result = ArrayList<AfEnumeratedEntry>()
        pending.add(Pending(root, "", 0))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = pending.removeLast()
            require(current.depth <= AfWorkflowLimits.MAX_TREE_DEPTH) { "Remote source tree is too deep" }
            require(result.size < AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "Remote source tree is too large" }
            result += AfEnumeratedEntry(current.relative, current.entry, current.depth)
            if (current.entry.directory) {
                val children = remoteClient(current.entry.location).list(current.entry.location.path)
                require(children.size <= MAX_REMOTE_DIRECTORY_ENTRIES) { "Remote folder contains too many entries" }
                children.sortedBy(RemoteEntry::name).asReversed().forEach { child ->
                    val normalizedChild = remoteNode(current.entry.location, child)
                    val relative = if (current.relative.isEmpty()) child.name else "${current.relative}/${child.name}"
                    validateRelativePath(relative)
                    pending.add(Pending(normalizedChild, relative, current.depth + 1))
                }
            }
        }
        return result
    }

    private suspend fun enumerateArchive(source: AfSourceRef): List<AfEnumeratedEntry> {
        val archiveFile = File(source.location.path).canonicalFile
        require(archiveFile.isFile && !Files.isSymbolicLink(archiveFile.toPath())) { "Archive source is unavailable" }
        val selected = normalizeArchivePath(requireNotNull(source.archiveEntryPath))
        val listed = archives.list(archiveFile).take(AfWorkflowLimits.MAX_PLANNED_ENTRIES)
        val normalized = listed.associateBy { normalizeArchivePath(it.name) }
        val selectedInfo = normalized[selected]
        val isDirectory = selectedInfo?.directory == true || normalized.keys.any { it.startsWith("$selected/") }
        require(isDirectory || selectedInfo != null) { "Archive entry is unavailable" }
        val result = linkedMapOf<String, AfEnumeratedEntry>()
        fun add(relative: String, directory: Boolean, info: ArchiveEntryInfo?, depth: Int) {
            if (relative in result) return
            require(result.size < AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "Archive selection is too large" }
            result[relative] = AfEnumeratedEntry(
                relativePath = relative,
                snapshot = AfNodeSnapshot(
                    location = source.location,
                    name = if (relative.isEmpty()) source.displayName else relative.substringAfterLast('/'),
                    directory = directory,
                    sizeBytes = if (directory) 0 else info?.sizeBytes?.coerceAtLeast(0) ?: 0,
                    modifiedAtMillis = info?.modifiedAtMillis,
                ),
                depth = depth,
            )
        }
        add("", isDirectory, selectedInfo, 0)
        if (isDirectory) {
            normalized.entries.filter { (path, _) -> path.startsWith("$selected/") }.sortedBy(Map.Entry<String, ArchiveEntryInfo>::key)
                .forEach { (path, info) ->
                    val relative = path.removePrefix("$selected/")
                    val parts = relative.split('/')
                    for (index in 1 until parts.size) {
                        add(parts.take(index).joinToString("/"), true, null, index)
                    }
                    add(relative, info.directory, info, parts.size)
                }
        }
        return result.values.sortedWith(compareBy<AfEnumeratedEntry> { it.depth }.thenBy { it.relativePath })
    }

    private suspend fun remoteStat(location: AfLocationRef): AfNodeSnapshot? {
        val normalized = location.normalized()
        if (normalized.path == "/") {
            return AfNodeSnapshot(normalized, normalized.profileName.orEmpty().ifBlank { "/" }, true, 0, null)
        }
        val parent = RemotePath.normalize("${normalized.path}/..")
        val entry = remoteClient(normalized).list(parent).firstOrNull {
            RemotePath.normalize(it.path) == normalized.path
        } ?: return null
        return remoteNode(normalized, entry)
    }

    private fun remoteNode(context: AfLocationRef, entry: RemoteEntry): AfNodeSnapshot = AfNodeSnapshot(
        location = context.copy(path = RemotePath.normalize(entry.path)).normalized(),
        name = entry.name,
        directory = entry.directory,
        sizeBytes = entry.sizeBytes.coerceAtLeast(0),
        modifiedAtMillis = entry.modifiedAtMillis,
    )

    private suspend fun remoteClient(location: AfLocationRef): RemoteClient {
        checkOpen()
        val profileId = requireNotNull(location.profileId) { "Remote profile is missing" }
        openedClients[profileId]?.let { return it }
        val profile = profileMap()[profileId] ?: throw IllegalArgumentException("Saved remote profile is unavailable")
        val initial = openConnection(profile)
        val reconnecting = ReconnectingRemoteClient(
            initial = initial,
            reconnect = {
                knownProfiles = null
                val latest = profileMap()[profileId] ?: throw IllegalArgumentException("Saved remote profile is unavailable")
                openConnection(latest)
            },
        )
        openedClients[profileId] = reconnecting
        return reconnecting
    }

    private suspend fun openConnection(profile: NetworkProfile): RemoteClient {
        val client = profiles.secret(profile.id).getOrThrow().use { secret -> clients.connect(profile, secret) }
        val fingerprint = client.verifiedHostFingerprint
        if (fingerprint != null && profile.expectedHostKeySha256 == null) {
            profiles.updateSftpFingerprint(profile.id, fingerprint).getOrThrow()
            knownProfiles = null
        }
        return client
    }

    private suspend fun profileMap(): Map<String, NetworkProfile> {
        knownProfiles?.let { return it }
        return profiles.list().associateBy(NetworkProfile::id).also { knownProfiles = it }
    }

    private suspend fun copyLocalSource(entry: AfEnumeratedEntry, destination: File, operation: OperationContext) {
        val source = File(entry.snapshot.location.path).canonicalFile
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Local source is unavailable" }
        require(source.length() == entry.snapshot.sizeBytes && source.lastModified().takeIf { it > 0 } == entry.snapshot.modifiedAtMillis) {
            "Local source changed after preview"
        }
        FileInputStream(source).buffered().use { input ->
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    operation.checkpoint()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    operation.progress(byteDelta = read.toLong(), currentName = source.name)
                }
            }
        }
        require(destination.length() == entry.snapshot.sizeBytes) { "Private staging size mismatch" }
    }

    private suspend fun installLocal(source: File, target: File, replace: Boolean, operation: OperationContext) {
        val parent = requireNotNull(target.parentFile).canonicalFile
        require(parent.isDirectory || parent.mkdirs()) { "Destination folder is unavailable" }
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.toPath().startsWith(parent.toPath()) && canonicalTarget != parent) { "Destination escaped its folder" }
        if (!replace) require(!canonicalTarget.exists()) { "Destination changed after preview" }
        val partial = File(parent, ".${canonicalTarget.name}.af-stage-${UUID.randomUUID()}")
        try {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(partial).use { raw ->
                    val output = raw.buffered()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operation.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            require(partial.length() == source.length()) { "Local staged destination size mismatch" }
            val options = if (replace) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(partial.toPath(), canonicalTarget.toPath(), *options)
            } catch (_: AtomicMoveNotSupportedException) {
                if (replace) Files.move(partial.toPath(), canonicalTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
                else Files.move(partial.toPath(), canonicalTarget.toPath())
            } catch (exists: FileAlreadyExistsException) {
                throw IllegalStateException("Destination changed after preview", exists)
            }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private suspend fun installRemote(source: File, target: AfLocationRef, replace: Boolean, operation: OperationContext) {
        val client = remoteClient(target)
        if (!replace) require(remoteStat(target) == null) { "Remote destination changed after preview" }
        val stagedPath = RemotePath.temporarySibling(target.path, "af-plan")
        try {
            client.upload(source, stagedPath, operation)
            val staged = remoteStat(target.copy(path = stagedPath))
            require(staged != null && !staged.directory && staged.sizeBytes == source.length()) {
                "Remote staged destination verification failed"
            }
            if (!replace) require(remoteStat(target) == null) { "Remote destination changed before commit" }
            client.rename(stagedPath, target.path)
            val installed = remoteStat(target)
            require(installed != null && !installed.directory && installed.sizeBytes == source.length()) {
                "Remote destination size verification failed"
            }
        } finally {
            runCatching { client.delete(stagedPath, recursive = false) }
        }
    }

    private fun deleteLocal(file: File, recursive: Boolean) {
        if (!file.exists()) return
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported" }
        if (!file.isDirectory) {
            require(file.delete()) { "Could not delete local file" }
            return
        }
        if (!recursive) {
            require(file.delete()) { "Folder is not empty or could not be deleted" }
            return
        }
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun newStagingFile(label: String): File {
        require(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Private workflow cache is unavailable" }
        return File(stagingRoot, "af-stage-$label-${UUID.randomUUID()}.tmp")
    }

    private fun requireStagingCapacity(sizeBytes: Long, purpose: String) {
        require(sizeBytes >= 0L) { "Invalid staging size" }
        require(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Private workflow cache is unavailable" }
        val available = stagingRoot.usableSpace
        require(
            available >= AfWorkflowLimits.MIN_STAGING_RESERVE_BYTES &&
                sizeBytes <= available - AfWorkflowLimits.MIN_STAGING_RESERVE_BYTES,
        ) { "Not enough private cache space for $purpose" }
    }

    private fun normalizeArchivePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && '\u0000' !in normalized) { "Invalid archive entry path" }
        val pieces = normalized.split('/')
        require(pieces.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe archive entry path" }
        return pieces.joinToString("/")
    }

    private fun checkOpen() = check(!closed) { "AF storage session is closed" }

    private companion object {
        const val CANCELLATION_CHECK_ITEMS = 64
        const val BUFFER_SIZE = 256 * 1_024
        const val MAX_REMOTE_DIRECTORY_ENTRIES = 100_000
    }
}
