package com.affilemanager.app.network

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Vector

class SftpRemoteClient private constructor(
    private val session: Session,
    private val channel: ChannelSftp,
    val trustedFingerprint: String,
) : RemoteClient {
    override val verifiedHostFingerprint: String
        get() = trustedFingerprint

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val IO_BUFFER = 256 * 1_024
        private const val MAX_LIST_ENTRIES = 50_000
        private const val MAX_RECURSIVE_DELETE_ENTRIES = 100_000

        suspend fun connect(profile: NetworkProfile, password: CharArray, privateKeyPem: CharArray): SftpRemoteClient = withContext(Dispatchers.IO) {
            require(profile.protocol == NetworkProtocol.SFTP)
            val verified = VerifiedSshSessionFactory.connect(profile, password, privateKeyPem)
            val session = verified.session
            try {
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(CONNECT_TIMEOUT_MS)
                SftpRemoteClient(session, channel, verified.fingerprint)
            } catch (error: Throwable) {
                session.disconnect()
                throw error
            }
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(path)
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(normalized) as Vector<ChannelSftp.LsEntry>
        require(entries.size <= MAX_LIST_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
        entries.asSequence()
            .filterNot { it.filename == "." || it.filename == ".." }
            .map { entry -> entry.toRemoteEntry(RemotePath.join(normalized, entry.filename)) }
            .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override suspend fun download(
        remotePath: String,
        localDestination: File,
        operation: OperationContext?,
        maxBytes: Long?,
    ) = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(remotePath)
        val attributes = channel.stat(normalized)
        require(!attributes.isDir) { "Aplanką atsisiųskite sinchronizavimo funkcija" }
        val limit = RemoteDownloadLimit(maxBytes).apply { checkExpected(attributes.size) }
        val partial = File(localDestination.parentFile, ".${localDestination.name}.partial")
        try {
            channel.get(normalized).use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        operation?.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        limit.record(read)
                        output.write(buffer, 0, read)
                        operation?.progress(byteDelta = read.toLong(), currentName = localDestination.name)
                    }
                }
            }
            require(partial.length() == attributes.size) { "Atsisiųsto failo dydis nesutampa" }
            if (localDestination.exists()) require(localDestination.delete()) { "Esamo failo pakeisti nepavyko" }
            require(partial.renameTo(localDestination)) { "Atsisiuntimo užbaigti nepavyko" }
            operation?.progress(itemDelta = 1, currentName = localDestination.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
        Unit
    }

    override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) = withContext(Dispatchers.IO) {
        require(localSource.isFile) { "Vietinis failas nepasiekiamas" }
        val normalized = RemotePath.normalize(remotePath)
        val partial = RemotePath.temporarySibling(normalized, "af-partial")
        try {
            channel.put(partial).use { output ->
                localSource.inputStream().buffered().use { input ->
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        operation?.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        operation?.progress(byteDelta = read.toLong(), currentName = localSource.name)
                    }
                }
            }
            require(channel.stat(partial).size == localSource.length()) { "Įkelto failo dydis nesutampa" }
            runCatching { channel.rm(normalized) }
            channel.rename(partial, normalized)
            operation?.progress(itemDelta = 1, currentName = localSource.name)
        } finally {
            runCatching { channel.rm(partial) }
        }
        Unit
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        channel.mkdir(RemotePath.normalize(path))
    }

    override suspend fun rename(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        channel.rename(RemotePath.normalize(fromPath), RemotePath.normalize(toPath))
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        deleteInternal(RemotePath.normalize(path), recursive, Counter(), 0)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        channel.disconnect()
        session.disconnect()
    }

    private fun deleteInternal(path: String, recursive: Boolean, counter: Counter, depth: Int) {
        require(depth <= 64) { "Nuotolinių aplankų gylis viršytas" }
        counter.value += 1
        require(counter.value <= MAX_RECURSIVE_DELETE_ENTRIES) { "Trynimo elementų riba viršyta" }
        val attrs = channel.stat(path)
        if (attrs.isDir) {
            require(recursive) { "Aplankas nėra tuščias arba rekursinis trynimas nepatvirtintas" }
            @Suppress("UNCHECKED_CAST")
            val children = channel.ls(path) as Vector<ChannelSftp.LsEntry>
            children.filterNot { it.filename == "." || it.filename == ".." }.forEach { child ->
                deleteInternal(RemotePath.join(path, child.filename), true, counter, depth + 1)
            }
            channel.rmdir(path)
        } else {
            channel.rm(path)
        }
    }

    private fun ChannelSftp.LsEntry.toRemoteEntry(path: String): RemoteEntry {
        val attributes: SftpATTRS = attrs
        return RemoteEntry(
            name = filename,
            path = path,
            directory = attributes.isDir,
            sizeBytes = attributes.size.coerceAtLeast(0),
            modifiedAtMillis = attributes.mTime.toLong() * 1_000,
        )
    }

    private class Counter(var value: Int = 0)
}
