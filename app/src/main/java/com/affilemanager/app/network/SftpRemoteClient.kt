package com.affilemanager.app.network

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
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
            val repository = FingerprintHostKeyRepository(
                expected = profile.expectedHostKeySha256,
                allowFirstUse = profile.allowFirstUseTrust,
            )
            val jsch = JSch().apply { hostKeyRepository = repository }
            val session = jsch.getSession(profile.username, profile.host, profile.port)
            session.setServerAliveInterval(15_000)
            session.setServerAliveCountMax(3)
            val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
            val privateKeyBytes = privateKeyPem.concatToString().toByteArray(Charsets.UTF_8)
            try {
                if (privateKeyBytes.isNotEmpty()) {
                    jsch.addIdentity(
                        "af-file-manager-${profile.id}",
                        privateKeyBytes,
                        null,
                        passwordBytes.takeIf(ByteArray::isNotEmpty),
                    )
                } else {
                    require(passwordBytes.isNotEmpty()) { "Reikalingas SFTP slaptažodis arba privatus SSH raktas" }
                    session.setPassword(passwordBytes)
                }
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig(
                    "PreferredAuthentications",
                    if (privateKeyBytes.isNotEmpty()) "publickey" else "password,keyboard-interactive",
                )
                session.timeout = CONNECT_TIMEOUT_MS
                session.connect(CONNECT_TIMEOUT_MS)
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(CONNECT_TIMEOUT_MS)
                val fingerprint = repository.observed
                    ?: throw SecurityException("SSH serverio raktas nepatikrintas")
                SftpRemoteClient(session, channel, fingerprint)
            } catch (error: Throwable) {
                session.disconnect()
                throw error
            } finally {
                passwordBytes.fill(0)
                privateKeyBytes.fill(0)
                password.fill('\u0000')
                privateKeyPem.fill('\u0000')
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

private class FingerprintHostKeyRepository(
    private val expected: String?,
    private val allowFirstUse: Boolean,
) : HostKeyRepository {
    @Volatile
    var observed: String? = null
        private set

    override fun check(host: String?, key: ByteArray): Int {
        val actual = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        observed = actual
        return when {
            expected != null && constantTimeEquals(expected, actual) -> HostKeyRepository.OK
            expected == null && allowFirstUse -> HostKeyRepository.OK
            expected != null -> HostKeyRepository.CHANGED
            else -> HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey?, userinfo: com.jcraft.jsch.UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "AF File Manager SHA-256 pinning"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.US_ASCII)
        val rightBytes = right.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
