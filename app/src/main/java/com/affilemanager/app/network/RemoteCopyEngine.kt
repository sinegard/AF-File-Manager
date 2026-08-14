package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

data class RemoteCopyFailure(
    val sourceName: String,
    val message: String,
)

data class RemoteCopyResult(
    val copiedRoots: Int,
    val failures: List<RemoteCopyFailure>,
)

class RemoteCopyEngine {
    companion object {
        const val MAX_SELECTED_ROOTS = 1_000
        const val MAX_VISITED_ENTRIES = 100_000
        const val MAX_DEPTH = 128
        private const val MAX_CREATE_ATTEMPTS = 8
        private const val MAX_NAME_ATTEMPTS = 10_000
    }

    suspend fun upload(
        sources: List<File>,
        remoteDirectory: String,
        remote: RemoteClient,
        operation: OperationContext,
    ): RemoteCopyResult {
        require(sources.size in 1..MAX_SELECTED_ROOTS) { "Pasirinkite nuo 1 iki $MAX_SELECTED_ROOTS failų ar aplankų" }
        val normalizedDirectory = RemotePath.normalize(remoteDirectory)
        val canonicalSources = sources.map(File::getCanonicalFile).distinctBy(File::getAbsolutePath)
        require(canonicalSources.size == sources.size) { "Tas pats vietinis šaltinis pasirinktas daugiau nei kartą" }
        val reserved = remoteNames(remote.list(normalizedDirectory)).toMutableSet()
        val counter = EntryCounter()
        val failures = ArrayList<RemoteCopyFailure>()
        var copied = 0
        operation.setTotals(items = null, bytes = null)

        canonicalSources.forEach { source ->
            operation.checkpoint()
            var ownedRemoteRoot: String? = null
            try {
                require(source.isFile || source.isDirectory) { "Vietinis šaltinis nepasiekiamas" }
                require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
                validateName(source.name)
                if (source.isDirectory) {
                    val root = createRemoteDirectory(normalizedDirectory, source.name, reserved, remote)
                    ownedRemoteRoot = root.second
                    counter.visit(0)
                    uploadDirectoryContents(source, root.second, remote, operation, counter, depth = 0)
                } else {
                    counter.visit(0)
                    uploadFileNoReplace(source, normalizedDirectory, source.name, reserved, remote, operation)
                }
                copied += 1
            } catch (cancelled: CancellationException) {
                ownedRemoteRoot?.let { cleanupRemoteRoot(remote, it) }
                throw cancelled
            } catch (error: Throwable) {
                ownedRemoteRoot?.let { cleanupRemoteRoot(remote, it) }
                failures += RemoteCopyFailure(source.name, safeError(error))
            }
        }
        return RemoteCopyResult(copied, failures)
    }

    suspend fun download(
        entries: List<RemoteEntry>,
        localDirectory: File,
        remote: RemoteClient,
        operation: OperationContext,
    ): RemoteCopyResult {
        require(entries.size in 1..MAX_SELECTED_ROOTS) { "Pasirinkite nuo 1 iki $MAX_SELECTED_ROOTS failų ar aplankų" }
        val destination = localDirectory.canonicalFile
        require(destination.isDirectory && destination.canWrite()) { "Vietinis paskirties aplankas nepasiekiamas" }
        val normalizedEntries = entries.distinctBy { RemotePath.normalize(it.path) }
        require(normalizedEntries.size == entries.size) { "Tas pats nuotolinis šaltinis pasirinktas daugiau nei kartą" }
        val reserved = localNames(destination).toMutableSet()
        val counter = EntryCounter()
        val failures = ArrayList<RemoteCopyFailure>()
        var copied = 0
        operation.setTotals(items = null, bytes = null)

        normalizedEntries.forEach { entry ->
            operation.checkpoint()
            var ownedLocalRoot: File? = null
            try {
                validateName(entry.name)
                if (entry.directory) {
                    val root = createLocalDirectory(destination, entry.name, reserved)
                    ownedLocalRoot = root
                    counter.visit(0)
                    downloadDirectoryContents(entry.path, root, remote, operation, counter, depth = 0)
                } else {
                    counter.visit(0)
                    downloadFileNoReplace(entry.path, destination, entry.name, reserved, remote, operation)
                }
                copied += 1
            } catch (cancelled: CancellationException) {
                ownedLocalRoot?.let(::cleanupLocalRoot)
                throw cancelled
            } catch (error: Throwable) {
                ownedLocalRoot?.let(::cleanupLocalRoot)
                failures += RemoteCopyFailure(entry.name, safeError(error))
            }
        }
        return RemoteCopyResult(copied, failures)
    }

    private suspend fun uploadDirectoryContents(
        localDirectory: File,
        remoteDirectory: String,
        remote: RemoteClient,
        operation: OperationContext,
        counter: EntryCounter,
        depth: Int,
    ) {
        require(depth < MAX_DEPTH) { "Aplankų gylio riba viršyta" }
        val children = localDirectory.listFiles() ?: throw IllegalStateException("Vietinio aplanko perskaityti nepavyko")
        val reserved = mutableSetOf<String>()
        children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }).forEach { child ->
            operation.checkpoint()
            counter.visit(depth + 1)
            require(!Files.isSymbolicLink(child.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
            validateName(child.name)
            when {
                child.isDirectory -> {
                    val target = createRemoteDirectory(remoteDirectory, child.name, reserved, remote)
                    uploadDirectoryContents(child, target.second, remote, operation, counter, depth + 1)
                }
                child.isFile -> uploadFileNoReplace(child, remoteDirectory, child.name, reserved, remote, operation)
                else -> throw IllegalArgumentException("Vietinis elementas nepasiekiamas")
            }
        }
    }

    private suspend fun downloadDirectoryContents(
        remoteDirectory: String,
        localDirectory: File,
        remote: RemoteClient,
        operation: OperationContext,
        counter: EntryCounter,
        depth: Int,
    ) {
        require(depth < MAX_DEPTH) { "Aplankų gylio riba viršyta" }
        val children = remote.list(RemotePath.normalize(remoteDirectory))
        val reserved = localNames(localDirectory).toMutableSet()
        children.forEach { child ->
            operation.checkpoint()
            counter.visit(depth + 1)
            validateName(child.name)
            if (child.directory) {
                val target = createLocalDirectory(localDirectory, child.name, reserved)
                downloadDirectoryContents(child.path, target, remote, operation, counter, depth + 1)
            } else {
                downloadFileNoReplace(child.path, localDirectory, child.name, reserved, remote, operation)
            }
        }
    }

    private suspend fun uploadFileNoReplace(
        source: File,
        remoteDirectory: String,
        requestedName: String,
        reserved: MutableSet<String>,
        remote: RemoteClient,
        operation: OperationContext,
    ): String {
        val stagingName = uniqueStagingName(".af-upload", reserved)
        val stagingPath = RemotePath.join(remoteDirectory, stagingName)
        var stagingExists = false
        try {
            remote.upload(source, stagingPath, operation)
            stagingExists = true
            var lastError: Throwable? = null
            repeat(MAX_CREATE_ATTEMPTS) {
                val candidate = availableName(requestedName, reserved)
                val target = RemotePath.join(remoteDirectory, candidate)
                try {
                    remote.rename(stagingPath, target)
                    stagingExists = false
                    reserved += nameKey(candidate)
                    return candidate
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastError = error
                    reserved += remoteNames(remote.list(remoteDirectory))
                    reserved += nameKey(candidate)
                }
            }
            throw lastError ?: IllegalStateException("Nuotolinio failo vardo rezervuoti nepavyko")
        } finally {
            if (stagingExists) cleanupRemoteEntry(remote, stagingPath, directory = false)
        }
    }

    private suspend fun downloadFileNoReplace(
        remotePath: String,
        localDirectory: File,
        requestedName: String,
        reserved: MutableSet<String>,
        remote: RemoteClient,
        operation: OperationContext,
    ): File {
        val staging = containedChild(localDirectory, uniqueStagingName(".af-download", reserved))
        check(staging.createNewFile()) { "Laikino vietinio failo sukurti nepavyko" }
        try {
            remote.download(RemotePath.normalize(remotePath), staging, operation)
            repeat(MAX_CREATE_ATTEMPTS) {
                val candidate = availableName(requestedName, reserved)
                val target = containedChild(localDirectory, candidate)
                try {
                    Files.move(staging.toPath(), target.toPath())
                    reserved += nameKey(candidate)
                    return target
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!target.exists()) throw error
                    reserved += nameKey(candidate)
                }
            }
            throw IllegalStateException("Vietinio failo vardo rezervuoti nepavyko")
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private suspend fun createRemoteDirectory(
        parent: String,
        requestedName: String,
        reserved: MutableSet<String>,
        remote: RemoteClient,
    ): Pair<String, String> {
        var lastError: Throwable? = null
        repeat(MAX_CREATE_ATTEMPTS) {
            val candidate = availableName(requestedName, reserved)
            val target = RemotePath.join(parent, candidate)
            try {
                remote.createDirectory(target)
                reserved += nameKey(candidate)
                return candidate to target
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                reserved += nameKey(candidate)
                reserved += remoteNames(remote.list(parent))
            }
        }
        throw lastError ?: IllegalStateException("Nuotolinio aplanko sukurti nepavyko")
    }

    private fun createLocalDirectory(parent: File, requestedName: String, reserved: MutableSet<String>): File {
        repeat(MAX_CREATE_ATTEMPTS) {
            val candidate = availableName(requestedName, reserved)
            val target = containedChild(parent, candidate)
            if (target.mkdir()) {
                reserved += nameKey(candidate)
                return target
            }
            reserved += nameKey(candidate)
            if (!target.exists()) throw IllegalStateException("Vietinio aplanko sukurti nepavyko")
        }
        throw IllegalStateException("Vietinio aplanko vardo rezervuoti nepavyko")
    }

    private fun availableName(requestedName: String, reserved: Set<String>): String {
        validateName(requestedName)
        if (nameKey(requestedName) !in reserved) return requestedName
        val dot = requestedName.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < requestedName.lastIndex
        val stem = if (hasExtension) requestedName.substring(0, dot) else requestedName
        val extension = if (hasExtension) requestedName.substring(dot) else ""
        for (index in 1 until MAX_NAME_ATTEMPTS) {
            val suffix = " ($index)$extension"
            val maxStem = (255 - suffix.length).coerceAtLeast(1)
            val candidate = stem.take(maxStem) + suffix
            if (nameKey(candidate) !in reserved) return candidate
        }
        throw IllegalStateException("Laisvo kopijos pavadinimo rasti nepavyko")
    }

    private fun uniqueStagingName(prefix: String, reserved: MutableSet<String>): String {
        repeat(MAX_CREATE_ATTEMPTS) {
            val token = UUID.randomUUID().toString().replace("-", "")
            val candidate = "$prefix-$token"
            if (reserved.add(nameKey(candidate))) return candidate
        }
        throw IllegalStateException("Laikino pavadinimo sukurti nepavyko")
    }

    private fun remoteNames(entries: List<RemoteEntry>): Set<String> {
        require(entries.size <= MAX_VISITED_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
        return entries.mapTo(HashSet(entries.size)) { entry ->
            validateName(entry.name)
            nameKey(entry.name)
        }
    }

    private fun localNames(directory: File): Set<String> {
        val names = directory.list() ?: throw IllegalStateException("Vietinio aplanko perskaityti nepavyko")
        require(names.size <= MAX_VISITED_ENTRIES) { "Vietiniame aplanke per daug elementų" }
        return names.mapTo(HashSet(names.size), ::nameKey)
    }

    private fun containedChild(parent: File, name: String): File {
        validateName(name)
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name).canonicalFile
        require(child.parentFile == canonicalParent) { "Paskirties kelias išeina už pasirinkto aplanko" }
        return child
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 255) { "Netinkamas failo ar aplanko vardas" }
        require(name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Nesaugus failo ar aplanko vardas"
        }
    }

    private fun nameKey(name: String): String = name.lowercase(Locale.ROOT)

    private suspend fun cleanupRemoteRoot(remote: RemoteClient, path: String) = cleanupRemoteEntry(remote, path, directory = true)

    private suspend fun cleanupRemoteEntry(remote: RemoteClient, path: String, directory: Boolean) {
        withContext(NonCancellable) { runCatching { remote.delete(path, recursive = directory) } }
    }

    private fun cleanupLocalRoot(root: File) {
        runCatching { root.deleteRecursively() }
    }

    private fun safeError(error: Throwable): String = (error.message ?: error::class.java.simpleName).take(500)

    private class EntryCounter {
        private var count = 0

        fun visit(depth: Int) {
            require(depth <= MAX_DEPTH) { "Aplankų gylio riba viršyta" }
            count += 1
            require(count <= MAX_VISITED_ENTRIES) { "Kopijuojamų elementų riba viršyta" }
        }
    }
}
