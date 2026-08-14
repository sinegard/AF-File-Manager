package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.File

class FtpRemoteClient private constructor(
    private val client: FTPClient,
) : RemoteClient {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val DATA_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 256 * 1_024
        private const val MAX_LIST_ENTRIES = 50_000
        private const val MAX_DELETE_ENTRIES = 100_000

        suspend fun connect(profile: NetworkProfile, password: CharArray): FtpRemoteClient = withContext(Dispatchers.IO) {
            require(profile.protocol == NetworkProtocol.FTP || profile.protocol == NetworkProtocol.FTPS)
            val client: FTPClient = if (profile.protocol == NetworkProtocol.FTPS) {
                FTPSClient(false).apply {
                    setEndpointCheckingEnabled(true)
                    setTrustManager(TrustManagerUtils.getValidateServerCertificateTrustManager())
                }
            } else FTPClient()
            try {
                client.connectTimeout = CONNECT_TIMEOUT_MS
                client.defaultTimeout = CONNECT_TIMEOUT_MS
                client.dataTimeout = java.time.Duration.ofMillis(DATA_TIMEOUT_MS.toLong())
                client.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(20))
                client.setControlKeepAliveReplyTimeout(java.time.Duration.ofSeconds(5))
                client.connect(profile.host, profile.port)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    throw FtpCommandException(FtpFailureStage.GREETING, client.replyCode)
                }
                if (!client.login(profile.username, password.concatToString())) {
                    throw FtpCommandException(FtpFailureStage.LOGIN, client.replyCode)
                }
                if (client is FTPSClient) {
                    client.execPBSZ(0)
                    client.execPROT("P")
                }
                client.enterLocalPassiveMode()
                if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                    throw FtpCommandException(FtpFailureStage.BINARY_MODE, client.replyCode)
                }
                FtpRemoteClient(client)
            } catch (error: Throwable) {
                if (client.isConnected) runCatching { client.disconnect() }
                throw error
            } finally {
                password.fill('\u0000')
            }
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureControlAlive()
        val normalized = RemotePath.normalize(path)
        val files = try {
            client.listFiles(normalized)
        } catch (error: Throwable) {
            throw FtpCommandException(FtpFailureStage.LIST, client.replyCode.takeIf { it > 0 }, error)
        }
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw FtpCommandException(FtpFailureStage.LIST, client.replyCode)
        }
        require(files.size <= MAX_LIST_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
        files.asSequence()
            .filterNot { it.name == "." || it.name == ".." }
            .map { it.toRemoteEntry(RemotePath.join(normalized, it.name)) }
            .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override suspend fun download(
        remotePath: String,
        localDestination: File,
        operation: OperationContext?,
        maxBytes: Long?,
    ) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        val normalized = RemotePath.normalize(remotePath)
        val expectedSize = client.mlistFile(normalized)?.size?.takeIf { it >= 0 }
        val limit = RemoteDownloadLimit(maxBytes).apply { checkExpected(expectedSize) }
        val partial = File(localDestination.parentFile, ".${localDestination.name}.partial")
        try {
            val input = client.retrieveFileStream(normalized) ?: throw IllegalStateException("FTP atsisiuntimas nepradėtas")
            input.use { stream ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operation?.checkpoint()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        limit.record(read)
                        output.write(buffer, 0, read)
                        operation?.progress(byteDelta = read.toLong(), currentName = localDestination.name)
                    }
                }
            }
            check(client.completePendingCommand()) { "FTP atsisiuntimas neužbaigtas" }
            if (expectedSize != null) require(partial.length() == expectedSize) { "Atsisiųsto failo dydis nesutampa" }
            if (localDestination.exists()) require(localDestination.delete()) { "Esamo failo pakeisti nepavyko" }
            require(partial.renameTo(localDestination)) { "Atsisiuntimo užbaigti nepavyko" }
            operation?.progress(itemDelta = 1, currentName = localDestination.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
        Unit
    }

    override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        require(localSource.isFile) { "Vietinis failas nepasiekiamas" }
        val normalized = RemotePath.normalize(remotePath)
        val partial = RemotePath.temporarySibling(normalized, "af-partial")
        try {
            val output = client.storeFileStream(partial) ?: throw IllegalStateException("FTP įkėlimas nepradėtas")
            output.use { stream ->
                localSource.inputStream().buffered().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operation?.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        operation?.progress(byteDelta = read.toLong(), currentName = localSource.name)
                    }
                }
            }
            check(client.completePendingCommand()) { "FTP įkėlimas neužbaigtas" }
            val uploadedSize = client.mlistFile(partial)?.size
            if (uploadedSize != null && uploadedSize >= 0) require(uploadedSize == localSource.length()) { "Įkelto failo dydis nesutampa" }
            client.deleteFile(normalized)
            check(client.rename(partial, normalized)) { "FTP failo pervadinti nepavyko" }
            operation?.progress(itemDelta = 1, currentName = localSource.name)
        } finally {
            client.deleteFile(partial)
        }
        Unit
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        check(client.makeDirectory(RemotePath.normalize(path))) { "FTP aplanko sukurti nepavyko" }
    }

    override suspend fun rename(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        check(client.rename(RemotePath.normalize(fromPath), RemotePath.normalize(toPath))) { "FTP pervadinti nepavyko" }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        deleteInternal(RemotePath.normalize(path), recursive, Counter(), 0)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { client.logout() }
        if (client.isConnected) runCatching { client.disconnect() }
    }

    private fun ensureControlAlive() {
        check(client.sendNoOp()) { "FTP connection closed without indication" }
    }

    private fun deleteInternal(path: String, recursive: Boolean, counter: Counter, depth: Int) {
        require(depth <= 64) { "Nuotolinių aplankų gylio riba viršyta" }
        counter.value += 1
        require(counter.value <= MAX_DELETE_ENTRIES) { "Trynimo elementų riba viršyta" }
        val metadata = client.mlistFile(path)
        if (metadata?.isDirectory == true) {
            require(recursive) { "Rekursinis aplanko trynimas nepatvirtintas" }
            client.listFiles(path)
                .filterNot { it.name == "." || it.name == ".." }
                .forEach { child -> deleteInternal(RemotePath.join(path, child.name), true, counter, depth + 1) }
            check(client.removeDirectory(path)) { "FTP aplanko ištrinti nepavyko" }
        } else {
            check(client.deleteFile(path)) { "FTP failo ištrinti nepavyko" }
        }
    }

    private fun FTPFile.toRemoteEntry(path: String) = RemoteEntry(
        name = name,
        path = path,
        directory = isDirectory,
        sizeBytes = size.coerceAtLeast(0),
        modifiedAtMillis = timestampInstant?.toEpochMilli(),
    )

    private class Counter(var value: Int = 0)
}
