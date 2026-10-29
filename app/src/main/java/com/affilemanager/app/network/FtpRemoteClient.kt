package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPCmd
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.Charset

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
                FastFtpsClient().apply {
                    setEndpointCheckingEnabled(true)
                    setTrustManager(TrustManagerUtils.getDefaultTrustManager(null))
                }
            } else FastFtpClient()
            try {
                client.setAutodetectUTF8(true)
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
        try {
            val machineReadable = if (
                runCatching { client.hasFeature("MLST") || client.hasFeature("MLSD") }.getOrDefault(false)
            ) {
                fastMachineList(normalized)
            } else {
                null
            }
            machineReadable ?: client.listFiles(normalized).asSequence()
                .filterNot { it.name == "." || it.name == ".." }
                .map { it.toRemoteEntry(RemotePath.join(normalized, it.name)) }
                .toList()
        } catch (error: Throwable) {
            throw FtpCommandException(FtpFailureStage.LIST, client.replyCode.takeIf { it > 0 }, error)
        }.also { entries ->
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw FtpCommandException(FtpFailureStage.LIST, client.replyCode)
            }
            require(entries.size <= MAX_LIST_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
        }
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
        val stage = RemoteDownloadStage(localDestination)
        val partial = stage.file
        try {
            val input = client.retrieveFileStream(normalized) ?: throw IllegalStateException("FTP atsisiuntimas nepradėtas")
            input.use { stream ->
                stage.outputStream().use { output ->
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
            stage.publish()
            operation?.progress(itemDelta = 1, currentName = localDestination.name)
        } finally {
            stage.close()
        }
        Unit
    }

    override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        require(localSource.isFile) { "Vietinis failas nepasiekiamas" }
        val normalized = RemotePath.normalize(remotePath)
        val partial = RemotePath.temporarySibling(normalized, "af-partial")
        var primaryFailure: Throwable? = null
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
            val previous = remoteMetadata(normalized)
            require(previous?.isDirectory != true) { "Esamo failo pakeisti nepavyko" }
            require(previous?.isSymbolicLink != true) { "Simbolinės nuorodos nekopijuojamos" }
            RemoteUploadPublication.publish(partial, normalized, operation,
                exists = { remotePathExists(it) },
                rename = { from, to -> check(client.rename(from, to)) { "FTP failo pervadinti nepavyko" } },
                remove = { check(client.deleteFile(it)) { "FTP failo ištrinti nepavyko" } })
            operation?.progress(itemDelta = 1, currentName = localSource.name)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { client.deleteFile(partial) }
            catch (cleanup: Throwable) {
                val original = primaryFailure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
        Unit
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        check(client.makeDirectory(RemotePath.normalize(path))) { "FTP aplanko sukurti nepavyko" }
    }

    override suspend fun createFile(path: String) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        val normalized = RemotePath.normalize(path)
        check(!remotePathExists(normalized)) { "Toks pavadinimas jau naudojamas" }
        val partial = RemotePath.temporarySibling(normalized, "af-create")
        try {
            ByteArrayInputStream(ByteArray(0)).use { empty ->
                check(client.storeFile(partial, empty)) { "Failo sukurti nepavyko" }
            }
            check(!remotePathExists(normalized)) { "Toks pavadinimas jau naudojamas" }
            check(client.rename(partial, normalized)) { "Pervadinti nepavyko" }
        } finally {
            client.deleteFile(partial)
        }
    }

    override suspend fun rename(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        val from = RemotePath.normalize(fromPath)
        val to = RemotePath.normalize(toPath)
        if (from == to) return@withContext
        check(!remotePathExists(to)) { "Toks pavadinimas jau naudojamas" }
        check(client.rename(from, to)) { "FTP pervadinti nepavyko" }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        ensureControlAlive()
        val normalized = RemotePath.normalize(path)
        require(normalized != "/") { "Šakninio bendrinimo katalogo keisti negalima" }
        deleteInternal(normalized, recursive, Counter(), 0)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { client.logout() }
        if (client.isConnected) runCatching { client.disconnect() }
    }

    private fun remotePathExists(path: String): Boolean =
        remoteMetadata(path) != null

    private fun remoteMetadata(path: String): FTPFile? {
        client.mlistFile(path)?.let { return it }
        val normalized = RemotePath.normalize(path)
        if (normalized == "/") return FTPFile().apply { name = "/"; type = FTPFile.DIRECTORY_TYPE }
        // MLST is optional. LIST of a target can be interpreted as a pattern or
        // a directory by different servers; inspect its successful parent list
        // and match the exact name instead of treating any returned row as proof.
        val parent = normalized.substringBeforeLast('/').ifEmpty { "/" }
        val children = client.listFiles(parent)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw FtpCommandException(FtpFailureStage.LIST, client.replyCode.takeIf { it > 0 })
        }
        require(children.size <= MAX_LIST_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
        return children.firstOrNull { it.name == normalized.substringAfterLast('/') }
    }

    private fun ensureControlAlive() {
        check(client.sendNoOp()) { "FTP connection closed without indication" }
    }

    private fun fastMachineList(path: String): List<RemoteEntry>? {
        val socket = (client as? MachineListConnection)?.openMachineList(path) ?: return null
        var primaryFailure: Throwable? = null
        return try {
            val result = ArrayList<RemoteEntry>()
            BufferedReader(InputStreamReader(socket.getInputStream(), Charset.forName(client.controlEncoding))).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    FtpMachineListParser.parse(line, path)?.let { entry ->
                        require(result.size < MAX_LIST_ENTRIES) { "Nuotoliniame aplanke per daug elementų" }
                        result += entry
                    }
                }
            }
            result
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val failure = primaryFailure
            runCatching { socket.close() }.exceptionOrNull()?.let { closeError ->
                if (failure != null) failure.addSuppressed(closeError) else throw closeError
            }
            val completion = runCatching { client.completePendingCommand() }
            if (failure != null) {
                completion.exceptionOrNull()?.let(failure::addSuppressed)
            } else {
                check(completion.getOrThrow()) { "FTP katalogo sąrašas neužbaigtas" }
            }
        }
    }

    private fun deleteInternal(path: String, recursive: Boolean, counter: Counter, depth: Int) {
        require(depth <= 64) { "Nuotolinių aplankų gylio riba viršyta" }
        counter.value += 1
        require(counter.value <= MAX_DELETE_ENTRIES) { "Trynimo elementų riba viršyta" }
        val metadata = remoteMetadata(path)
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

    private interface MachineListConnection {
        fun openMachineList(path: String): Socket?
    }

    private class FastFtpClient : FTPClient(), MachineListConnection {
        override fun openMachineList(path: String): Socket? = _openDataConnection_(FTPCmd.MLSD, path)
    }

    private class FastFtpsClient : FTPSClient(false), MachineListConnection {
        override fun openMachineList(path: String): Socket? = _openDataConnection_(FTPCmd.MLSD, path)
    }
}
