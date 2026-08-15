package com.affilemanager.app.editing

import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Locale

class RemoteEditSaver(private val sessions: EditSessionStore) {
    suspend fun saveOrigin(
        session: EditSession,
        client: RemoteClient,
        forceOverwrite: Boolean,
    ): EditSaveResult {
        val origin = session.origin as? EditOrigin.Remote ?: error("Edit session is not remote")
        val current = revision(client, origin.path, session)
        if (!forceOverwrite && !session.originRevision.hasSameContent(current)) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, current))
        }
        return replaceRemoteOrigin(session, client, origin, forceOverwrite)
    }

    suspend fun saveAs(
        session: EditSession,
        client: RemoteClient,
        profileId: String,
        connectionName: String,
        directoryPath: String,
        requestedName: String,
        policy: EditExistingPolicy,
    ): EditSaveAsResult {
        val directory = RemotePath.normalize(directoryPath)
        val safeName = EditDestinationRules.validateFileName(requestedName)
        val entries = listBounded(client, directory)
        val existing = findByName(entries, safeName)
        require(existing?.directory != true) { "A folder already uses this name" }
        val requestedPath = RemotePath.join(directory, safeName)
        val requestedDestination = EditDestination.Remote(profileId, connectionName, requestedPath)

        if (policy == EditExistingPolicy.ASK && existing != null) {
            return EditSaveAsResult.Conflict(remoteDestinationConflict(requestedDestination, existing))
        }

        return when (policy) {
            EditExistingPolicy.ASK -> installRemoteNew(
                session = session,
                client = client,
                initialDestination = requestedDestination,
                keepBoth = false,
            )
            EditExistingPolicy.KEEP_BOTH -> {
                val reserved = entries.mapTo(HashSet(entries.size)) { it.name.lowercase(Locale.ROOT) }
                val available = nextAvailableName(safeName, reserved)
                installRemoteNew(
                    session = session,
                    client = client,
                    initialDestination = EditDestination.Remote(
                        profileId = profileId,
                        connectionName = connectionName,
                        path = RemotePath.join(directory, available),
                    ),
                    keepBoth = true,
                )
            }
            EditExistingPolicy.REPLACE -> {
                val commit = replaceRemotePath(
                    session = session,
                    client = client,
                    targetPath = requestedPath,
                    expectedRevision = null,
                    forceOverwrite = true,
                )
                when (commit) {
                    is EditSaveResult.Conflict -> error("Forced replacement unexpectedly produced a conflict")
                    is EditSaveResult.Saved -> EditSaveAsResult.Saved(
                        destination = requestedDestination,
                        revision = commit.revision,
                        warning = commit.warning,
                    )
                }
            }
        }
    }

    suspend fun listDirectories(client: RemoteClient, path: String): List<RemoteEntry> =
        listBounded(client, RemotePath.normalize(path)).filter(RemoteEntry::directory)

    private suspend fun replaceRemoteOrigin(
        session: EditSession,
        client: RemoteClient,
        origin: EditOrigin.Remote,
        forceOverwrite: Boolean,
    ): EditSaveResult = replaceRemotePath(
        session = session,
        client = client,
        targetPath = origin.path,
        expectedRevision = session.originRevision,
        forceOverwrite = forceOverwrite,
        originLabel = origin.label,
    )

    private suspend fun replaceRemotePath(
        session: EditSession,
        client: RemoteClient,
        targetPath: String,
        expectedRevision: FileRevision?,
        forceOverwrite: Boolean,
        originLabel: String = RemotePath.normalize(targetPath),
    ): EditSaveResult {
        val normalizedTarget = RemotePath.normalize(targetPath)
        val stagedPath = RemotePath.temporarySibling(normalizedTarget, "af-edit")
        var stagedExists = false
        var backupPath: String? = null
        var targetInstalled = false
        try {
            client.upload(session.workingFile, stagedPath, OperationContext.background())
            stagedExists = true
            val stagedRevision = requireNotNull(revision(client, stagedPath, session)) {
                "Server did not return the staged file for verification"
            }
            require(session.workingRevision.hasSameContent(stagedRevision)) {
                "Staged server file verification failed"
            }

            val latest = revision(client, normalizedTarget, session)
            if (!forceOverwrite && expectedRevision != null && !expectedRevision.hasSameContent(latest)) {
                return EditSaveResult.Conflict(EditConflict(originLabel, expectedRevision, latest))
            }

            val targetEntry = findEntry(client, normalizedTarget)
            require(targetEntry?.directory != true) { "The remote destination became a folder" }
            if (targetEntry != null) {
                backupPath = RemotePath.temporarySibling(normalizedTarget, "af-backup")
                client.rename(normalizedTarget, requireNotNull(backupPath))
            }

            try {
                client.rename(stagedPath, normalizedTarget)
                stagedExists = false
                targetInstalled = true
            } catch (error: Throwable) {
                restoreBackup(client, normalizedTarget, backupPath, error)
                throw error
            }

            val verified = try {
                requireNotNull(revision(client, normalizedTarget, session)) {
                    "Server did not return the saved file for verification"
                }.also { revision ->
                    require(session.workingRevision.hasSameContent(revision)) {
                        "Saved server file verification failed"
                    }
                }
            } catch (error: Throwable) {
                val hadBackup = backupPath != null
                if (targetInstalled) cleanupRemote(client, normalizedTarget)
                targetInstalled = false
                restoreBackup(client, normalizedTarget, backupPath, error)
                backupPath = null
                throw IllegalStateException(
                    if (hadBackup) "Remote save verification failed; the previous version was restored"
                    else "Remote save verification failed; the incomplete destination was removed",
                    error,
                )
            }
            targetInstalled = false
            val cleanupWarning = backupPath?.let { backup ->
                runCatching { client.delete(backup, recursive = false) }
                    .exceptionOrNull()
                    ?.let { "File was saved and verified, but the recovery backup could not be removed: $backup" }
            }
            backupPath = null
            return EditSaveResult.Saved(verified, cleanupWarning)
        } catch (cancelled: CancellationException) {
            if (targetInstalled) cleanupRemote(client, normalizedTarget)
            restoreBackupQuietly(client, normalizedTarget, backupPath)
            throw cancelled
        } finally {
            if (stagedExists) cleanupRemote(client, stagedPath)
        }
    }

    private suspend fun installRemoteNew(
        session: EditSession,
        client: RemoteClient,
        initialDestination: EditDestination.Remote,
        keepBoth: Boolean,
    ): EditSaveAsResult {
        val initialPath = RemotePath.normalize(initialDestination.path)
        val directory = RemotePath.normalize("$initialPath/..")
        val requestedName = initialPath.substringAfterLast('/')
        val stagedPath = RemotePath.temporarySibling(initialPath, "af-edit")
        var stagedExists = false
        try {
            client.upload(session.workingFile, stagedPath, OperationContext.background())
            stagedExists = true
            val stagedRevision = requireNotNull(revision(client, stagedPath, session)) {
                "Server did not return the staged file for verification"
            }
            require(session.workingRevision.hasSameContent(stagedRevision)) {
                "Staged server file verification failed"
            }

            val reserved = listBounded(client, directory)
                .mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
            if (!keepBoth && requestedName.lowercase(Locale.ROOT) in reserved) {
                val existing = requireNotNull(findByName(listBounded(client, directory), requestedName))
                return EditSaveAsResult.Conflict(remoteDestinationConflict(initialDestination, existing))
            }

            for (attempt in 0 until MAX_NAME_ATTEMPTS) {
                val candidateName = if (keepBoth) nextAvailableName(requestedName, reserved) else requestedName
                val candidatePath = RemotePath.join(directory, candidateName)
                val candidateDestination = initialDestination.copy(path = candidatePath)
                try {
                    client.rename(stagedPath, candidatePath)
                    stagedExists = false
                    val verified = try {
                        requireNotNull(revision(client, candidatePath, session)) {
                            "Server did not return the saved file for verification"
                        }.also { revision ->
                            require(session.workingRevision.hasSameContent(revision)) {
                                "Saved server file verification failed"
                            }
                        }
                    } catch (error: Throwable) {
                        cleanupRemote(client, candidatePath)
                        throw error
                    }
                    return EditSaveAsResult.Saved(candidateDestination, verified)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val appeared = findEntry(client, candidatePath)
                    if (!keepBoth || appeared == null) throw error
                    reserved += candidateName.lowercase(Locale.ROOT)
                }
            }
            error("A free remote destination name could not be found")
        } finally {
            if (stagedExists) cleanupRemote(client, stagedPath)
        }
    }

    private suspend fun revision(
        client: RemoteClient,
        remotePath: String,
        session: EditSession,
    ): FileRevision? {
        val normalizedPath = RemotePath.normalize(remotePath)
        val remoteEntry = findEntry(client, normalizedPath) ?: return null
        require(!remoteEntry.directory) { "The remote path is now a folder" }
        require(remoteEntry.sizeBytes in 0..EditLimits.MAX_FILE_BYTES) {
            "The remote file is too large for safe verification"
        }
        val verification = withContext(Dispatchers.IO) { sessions.verificationFile(session) }
        return try {
            client.download(
                remotePath = normalizedPath,
                localDestination = verification,
                operation = OperationContext.background(),
                maxBytes = EditLimits.MAX_FILE_BYTES,
            )
            withContext(Dispatchers.IO) {
                sessions.revisionFromStream(remoteEntry.modifiedAtMillis, verification::inputStream)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { sessions.discardVerification(verification) }
        }
    }

    private suspend fun findEntry(client: RemoteClient, path: String): RemoteEntry? {
        val normalized = RemotePath.normalize(path)
        val parent = RemotePath.normalize("$normalized/..")
        val requestedName = normalized.substringAfterLast('/')
        val entries = listBounded(client, parent)
        return entries.firstOrNull { RemotePath.normalize(it.path) == normalized }
            ?: findByName(entries, requestedName)
    }

    private suspend fun listBounded(client: RemoteClient, path: String): List<RemoteEntry> =
        client.list(RemotePath.normalize(path)).also {
            require(it.size <= MAX_REMOTE_ENTRIES) { "Remote folder contains too many entries" }
        }

    private fun findByName(entries: List<RemoteEntry>, name: String): RemoteEntry? =
        entries.firstOrNull { it.name == name }
            ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun remoteDestinationConflict(
        destination: EditDestination.Remote,
        existing: RemoteEntry,
    ): EditSaveAsConflict = EditSaveAsConflict(
        destination = destination,
        existing = EditDestinationSnapshot(
            sizeBytes = existing.sizeBytes.coerceAtLeast(0),
            modifiedAtMillis = existing.modifiedAtMillis,
        ),
    )

    private suspend fun restoreBackup(
        client: RemoteClient,
        targetPath: String,
        backupPath: String?,
        originalError: Throwable,
    ) {
        if (backupPath == null) return
        try {
            client.rename(backupPath, targetPath)
        } catch (restoreError: Throwable) {
            originalError.addSuppressed(restoreError)
            throw IllegalStateException(
                "Remote save failed and automatic recovery also failed. Previous data remains at $backupPath",
                originalError,
            )
        }
    }

    private suspend fun restoreBackupQuietly(client: RemoteClient, targetPath: String, backupPath: String?) {
        if (backupPath == null) return
        withContext(NonCancellable) { runCatching { client.rename(backupPath, targetPath) } }
    }

    private suspend fun cleanupRemote(client: RemoteClient, path: String) {
        withContext(NonCancellable) { runCatching { client.delete(path, recursive = false) } }
    }

    private fun nextAvailableName(requestedName: String, reserved: Set<String>): String {
        for (attempt in 0 until MAX_NAME_ATTEMPTS) {
            val candidate = keepBothName(requestedName, attempt)
            if (candidate.lowercase(Locale.ROOT) !in reserved) return candidate
        }
        error("A free remote destination name could not be found")
    }

    private fun keepBothName(name: String, attempt: Int): String {
        if (attempt == 0) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        val suffix = " ($attempt)$extension"
        return stem.take((255 - suffix.length).coerceAtLeast(1)) + suffix
    }

    private companion object {
        const val MAX_REMOTE_ENTRIES = 100_000
        const val MAX_NAME_ATTEMPTS = 10_000
    }
}
