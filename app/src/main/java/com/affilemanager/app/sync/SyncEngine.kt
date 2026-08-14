package com.affilemanager.app.sync

import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

enum class SyncMode {
    LOCAL_TO_REMOTE,
    REMOTE_TO_LOCAL,
    TWO_WAY,
}

enum class SyncConflictPolicy {
    REPORT_ONLY,
    NEWEST_WINS,
    LOCAL_WINS,
    REMOTE_WINS,
    KEEP_BOTH,
}

enum class SyncActionType {
    CREATE_LOCAL_DIRECTORY,
    CREATE_REMOTE_DIRECTORY,
    UPLOAD,
    DOWNLOAD,
    CONFLICT,
    SKIP,
}

data class SyncAction(
    val relativePath: String,
    val type: SyncActionType,
    val reason: String,
    val sizeBytes: Long = 0,
    val targetRelativePath: String? = null,
)

data class SyncPreview(
    val actions: List<SyncAction>,
    val totalTransferBytes: Long,
    val truncated: Boolean,
)

class SyncEngine {
    companion object {
        private const val MAX_SYNC_ENTRIES = 100_000
        private const val MAX_DEPTH = 64
        private const val TIMESTAMP_TOLERANCE_MS = 2_000L
    }

    suspend fun preview(
        localRoot: File,
        remoteRoot: String,
        remote: RemoteClient,
        mode: SyncMode,
        conflictPolicy: SyncConflictPolicy,
    ): SyncPreview = withContext(Dispatchers.IO) {
        require(localRoot.isDirectory) { "Vietinis sinchronizavimo aplankas nepasiekiamas" }
        val local = scanLocal(localRoot)
        val remoteEntries = scanRemote(remote, RemotePath.normalize(remoteRoot))
        val paths = (local.keys + remoteEntries.keys).sorted()
        require(paths.size <= MAX_SYNC_ENTRIES) { "Sinchronizavimo elementų riba viršyta" }
        val actions = mutableListOf<SyncAction>()
        val reservedPaths = paths.toMutableSet()
        var totalBytes = 0L

        paths.forEach { relative ->
            coroutineContext.ensureActive()
            val localEntry = local[relative]
            val remoteEntry = remoteEntries[relative]
            val decisions = decide(relative, localEntry, remoteEntry, mode, conflictPolicy, reservedPaths)
            actions += decisions
            decisions.forEach { action ->
                if (action.type == SyncActionType.UPLOAD || action.type == SyncActionType.DOWNLOAD) {
                    totalBytes = Math.addExact(totalBytes, action.sizeBytes)
                }
            }
            require(actions.size <= MAX_SYNC_ENTRIES * 2) { "Sinchronizavimo veiksmų riba viršyta" }
        }
        SyncPreview(actions, totalBytes, truncated = false)
    }

    suspend fun execute(
        preview: SyncPreview,
        localRoot: File,
        remoteRoot: String,
        remote: RemoteClient,
        operation: OperationContext,
    ) = withContext(Dispatchers.IO) {
        require(preview.actions.none { it.type == SyncActionType.CONFLICT }) { "Pirmiausia išspręskite sinchronizavimo konfliktus" }
        operation.setTotals(preview.actions.count { it.type != SyncActionType.SKIP }, preview.totalTransferBytes)
        preview.actions.forEach { action ->
            operation.checkpoint()
            val sourceLocal = safeLocal(localRoot, action.relativePath)
            val sourceRemote = joinRelative(remoteRoot, action.relativePath)
            val targetRelative = action.targetRelativePath ?: action.relativePath
            val targetLocal = safeLocal(localRoot, targetRelative)
            val targetRemote = joinRelative(remoteRoot, targetRelative)
            when (action.type) {
                SyncActionType.CREATE_LOCAL_DIRECTORY -> require(targetLocal.isDirectory || targetLocal.mkdirs()) { "Nepavyko sukurti $targetRelative" }
                SyncActionType.CREATE_REMOTE_DIRECTORY -> remote.createDirectory(targetRemote)
                SyncActionType.UPLOAD -> {
                    ensureRemoteParents(remote, remoteRoot, targetRelative)
                    remote.upload(sourceLocal, targetRemote, operation)
                }
                SyncActionType.DOWNLOAD -> {
                    targetLocal.parentFile?.mkdirs()
                    remote.download(sourceRemote, targetLocal, operation)
                }
                SyncActionType.CONFLICT -> error("Neišspręstas konfliktas")
                SyncActionType.SKIP -> Unit
            }
            if (action.type != SyncActionType.SKIP && action.type != SyncActionType.UPLOAD && action.type != SyncActionType.DOWNLOAD) {
                operation.progress(itemDelta = 1, currentName = action.relativePath)
            }
        }
    }

    private fun decide(
        path: String,
        local: LocalNode?,
        remote: RemoteEntry?,
        mode: SyncMode,
        policy: SyncConflictPolicy,
        reservedPaths: MutableSet<String>,
    ): List<SyncAction> {
        if (local == null && remote != null) {
            return listOf(when (mode) {
                SyncMode.LOCAL_TO_REMOTE -> SyncAction(path, SyncActionType.SKIP, "Tik nuotolinis elementas")
                SyncMode.REMOTE_TO_LOCAL, SyncMode.TWO_WAY -> if (remote.directory) {
                    SyncAction(path, SyncActionType.CREATE_LOCAL_DIRECTORY, "Trūksta vietinio aplanko")
                } else {
                    SyncAction(path, SyncActionType.DOWNLOAD, "Trūksta vietinio failo", remote.sizeBytes)
                }
            })
        }
        if (local != null && remote == null) {
            return listOf(when (mode) {
                SyncMode.REMOTE_TO_LOCAL -> SyncAction(path, SyncActionType.SKIP, "Tik vietinis elementas")
                SyncMode.LOCAL_TO_REMOTE, SyncMode.TWO_WAY -> if (local.directory) {
                    SyncAction(path, SyncActionType.CREATE_REMOTE_DIRECTORY, "Trūksta nuotolinio aplanko")
                } else {
                    SyncAction(path, SyncActionType.UPLOAD, "Trūksta nuotolinio failo", local.size)
                }
            })
        }
        require(local != null && remote != null)
        if (local.directory != remote.directory) return listOf(SyncAction(path, SyncActionType.CONFLICT, "Failo ir aplanko tipai nesutampa"))
        if (local.directory) return listOf(SyncAction(path, SyncActionType.SKIP, "Aplankas yra abiejose pusėse"))
        val timeDifference = kotlin.math.abs(local.modified - (remote.modifiedAtMillis ?: 0L))
        if (local.size == remote.sizeBytes && timeDifference <= TIMESTAMP_TOLERANCE_MS) {
            return listOf(SyncAction(path, SyncActionType.SKIP, "Failai sutampa pagal dydį ir laiką"))
        }
        return when (mode) {
            SyncMode.LOCAL_TO_REMOTE -> listOf(SyncAction(path, SyncActionType.UPLOAD, "Vietinis šaltinis yra autoritetingas", local.size))
            SyncMode.REMOTE_TO_LOCAL -> listOf(SyncAction(path, SyncActionType.DOWNLOAD, "Nuotolinis šaltinis yra autoritetingas", remote.sizeBytes))
            SyncMode.TWO_WAY -> resolveConflict(path, local, remote, policy, reservedPaths)
        }
    }

    private fun resolveConflict(
        path: String,
        local: LocalNode,
        remote: RemoteEntry,
        policy: SyncConflictPolicy,
        reservedPaths: MutableSet<String>,
    ): List<SyncAction> = when (policy) {
        SyncConflictPolicy.REPORT_ONLY -> listOf(SyncAction(path, SyncActionType.CONFLICT, "Abi versijos skiriasi"))
        SyncConflictPolicy.LOCAL_WINS -> listOf(SyncAction(path, SyncActionType.UPLOAD, "Pasirinkta vietinė versija", local.size))
        SyncConflictPolicy.REMOTE_WINS -> listOf(SyncAction(path, SyncActionType.DOWNLOAD, "Pasirinkta nuotolinė versija", remote.sizeBytes))
        SyncConflictPolicy.NEWEST_WINS -> {
            val remoteTime = remote.modifiedAtMillis
            listOf(
                if (remoteTime == null) SyncAction(path, SyncActionType.CONFLICT, "Serveris nepateikė keitimo laiko")
                else if (local.modified >= remoteTime) SyncAction(path, SyncActionType.UPLOAD, "Vietinė versija naujesnė", local.size)
                else SyncAction(path, SyncActionType.DOWNLOAD, "Nuotolinė versija naujesnė", remote.sizeBytes),
            )
        }
        SyncConflictPolicy.KEEP_BOTH -> {
            val localCopy = reserveAlternative(path, "nuotolinis", reservedPaths)
            val remoteCopy = reserveAlternative(path, "vietinis", reservedPaths)
            listOf(
                SyncAction(path, SyncActionType.DOWNLOAD, "Nuotolinė versija paliekama atskiru vardu", remote.sizeBytes, localCopy),
                SyncAction(path, SyncActionType.UPLOAD, "Vietinė versija paliekama atskiru vardu", local.size, remoteCopy),
            )
        }
    }

    private fun reserveAlternative(path: String, label: String, reserved: MutableSet<String>): String {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var number = 1
        while (true) {
            val suffix = if (number == 1) " ($label)" else " ($label $number)"
            val candidateName = "$stem$suffix$extension"
            val candidate = if (parent.isBlank()) candidateName else "$parent/$candidateName"
            if (reserved.add(candidate)) return candidate
            number += 1
            require(number <= 10_000) { "Nepavyko parinkti unikalaus sinchronizavimo vardo" }
        }
    }

    private fun scanLocal(root: File): Map<String, LocalNode> {
        val result = linkedMapOf<String, LocalNode>()
        val pending = ArrayDeque<Pair<File, Int>>()
        root.listFiles()?.forEach { pending.add(it to 1) }
        while (pending.isNotEmpty()) {
            val (file, depth) = pending.removeLast()
            require(depth <= MAX_DEPTH) { "Vietinių aplankų gylio riba viršyta" }
            val relative = file.relativeTo(root).invariantSeparatorsPath
            result[relative] = LocalNode(file.isDirectory, file.length(), file.lastModified())
            require(result.size <= MAX_SYNC_ENTRIES) { "Sinchronizavimo elementų riba viršyta" }
            if (file.isDirectory) file.listFiles()?.forEach { pending.add(it to depth + 1) }
                ?: throw SecurityException("Aplankas neperskaitomas: ${file.name}")
        }
        return result
    }

    private suspend fun scanRemote(remote: RemoteClient, root: String): Map<String, RemoteEntry> {
        val result = linkedMapOf<String, RemoteEntry>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (directory, depth) = pending.removeLast()
            require(depth <= MAX_DEPTH) { "Nuotolinių aplankų gylio riba viršyta" }
            remote.list(directory).forEach { entry ->
                val relative = relativeRemote(root, entry.path)
                result[relative] = entry
                require(result.size <= MAX_SYNC_ENTRIES) { "Sinchronizavimo elementų riba viršyta" }
                if (entry.directory) pending.add(entry.path to depth + 1)
            }
        }
        return result
    }

    private suspend fun ensureRemoteParents(remote: RemoteClient, root: String, relative: String) {
        val parts = relative.split('/').dropLast(1)
        var current = RemotePath.normalize(root)
        parts.forEach { part ->
            current = RemotePath.join(current, part)
            runCatching { remote.createDirectory(current) }
        }
    }

    private fun safeLocal(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) { "Vietinis kelias išeina už sinchronizavimo šaknies" }
        return target
    }

    private fun joinRelative(root: String, relative: String): String = relative.split('/')
        .filter(String::isNotBlank)
        .fold(RemotePath.normalize(root), RemotePath::join)

    private fun relativeRemote(root: String, path: String): String {
        val normalizedRoot = RemotePath.normalize(root).trimEnd('/')
        val normalizedPath = RemotePath.normalize(path)
        require(normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")) {
            "Serveris grąžino kelią už sinchronizavimo šaknies"
        }
        return normalizedPath.removePrefix(normalizedRoot).trimStart('/')
    }

    private data class LocalNode(val directory: Boolean, val size: Long, val modified: Long)
}
