package com.affilemanager.app.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbRemoteClient private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : RemoteClient {
    companion object {
        private const val BUFFER_SIZE = 256 * 1_024
        private const val MAX_LIST_ENTRIES = 50_000
        private const val MAX_DELETE_ENTRIES = 100_000

        suspend fun connect(profile: NetworkProfile, password: CharArray): SmbRemoteClient = withContext(Dispatchers.IO) {
            require(profile.protocol == NetworkProtocol.SMB)
            val config = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .withReadTimeout(30, TimeUnit.SECONDS)
                .withWriteTimeout(30, TimeUnit.SECONDS)
                .withTransactTimeout(30, TimeUnit.SECONDS)
                .withSigningEnabled(true)
                .withBufferSize(BUFFER_SIZE)
                .build()
            val client = SMBClient(config)
            var connection: Connection? = null
            var session: Session? = null
            try {
                connection = client.connect(profile.host, profile.port)
                val auth = AuthenticationContext(profile.username, password, profile.domain)
                session = connection.authenticate(auth)
                val share = session.connectShare(profile.smbShare) as? DiskShare
                    ?: throw IllegalArgumentException("SMB vieta nėra disko bendrinimas")
                SmbRemoteClient(client, connection, session, share)
            } catch (error: Throwable) {
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
                throw error
            } finally {
                password.fill('\u0000')
            }
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(path)
        val entries = share.list(toSmbPath(normalized))
        require(entries.size <= MAX_LIST_ENTRIES) { "SMB aplanke per daug elementų" }
        entries.asSequence()
            .filterNot { it.fileName == "." || it.fileName == ".." }
            .map { entry ->
                val directory = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteEntry(
                    name = entry.fileName,
                    path = RemotePath.join(normalized, entry.fileName),
                    directory = directory,
                    sizeBytes = entry.endOfFile.coerceAtLeast(0),
                    modifiedAtMillis = entry.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?) = withContext(Dispatchers.IO) {
        val smbPath = toSmbPath(RemotePath.normalize(remotePath))
        val expected = share.getFileInformation(smbPath).standardInformation.endOfFile
        val partial = File(localDestination.parentFile, ".${localDestination.name}.partial")
        try {
            share.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            ).use { remote ->
                remote.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            operation?.checkpoint()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            operation?.progress(byteDelta = read.toLong(), currentName = localDestination.name)
                        }
                    }
                }
            }
            require(partial.length() == expected) { "Atsisiųsto SMB failo dydis nesutampa" }
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
        val partialSmb = toSmbPath(partial)
        try {
            share.openFile(
                partialSmb,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            ).use { remote ->
                remote.outputStream.use { output ->
                    localSource.inputStream().buffered().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            operation?.checkpoint()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            operation?.progress(byteDelta = read.toLong(), currentName = localSource.name)
                        }
                    }
                }
                remote.flush()
            }
            require(share.getFileInformation(partialSmb).standardInformation.endOfFile == localSource.length()) {
                "Įkelto SMB failo dydis nesutampa"
            }
            if (share.fileExists(toSmbPath(normalized))) share.rm(toSmbPath(normalized))
            renameInternal(partial, normalized, replace = true)
            operation?.progress(itemDelta = 1, currentName = localSource.name)
        } finally {
            runCatching { if (share.fileExists(partialSmb)) share.rm(partialSmb) }
        }
        Unit
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        share.mkdir(toSmbPath(RemotePath.normalize(path)))
    }

    override suspend fun rename(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        renameInternal(RemotePath.normalize(fromPath), RemotePath.normalize(toPath), replace = false)
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        deleteInternal(RemotePath.normalize(path), recursive, Counter(), 0)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
        Unit
    }

    private fun renameInternal(fromPath: String, toPath: String, replace: Boolean) {
        val source = toSmbPath(fromPath)
        share.open(
            source,
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            emptySet(),
        ).use { entry -> entry.rename(toSmbPath(toPath), replace) }
    }

    private fun deleteInternal(path: String, recursive: Boolean, counter: Counter, depth: Int) {
        require(depth <= 64) { "Nuotolinių aplankų gylio riba viršyta" }
        counter.value += 1
        require(counter.value <= MAX_DELETE_ENTRIES) { "Trynimo elementų riba viršyta" }
        val smbPath = toSmbPath(path)
        if (share.folderExists(smbPath)) {
            require(recursive) { "Rekursinis aplanko trynimas nepatvirtintas" }
            share.list(smbPath)
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .forEach { child -> deleteInternal(RemotePath.join(path, child.fileName), true, counter, depth + 1) }
            share.rmdir(smbPath, false)
        } else {
            share.rm(smbPath)
        }
    }

    private fun toSmbPath(remotePath: String): String = RemotePath.normalize(remotePath)
        .trim('/')
        .replace('/', '\\')

    private class Counter(var value: Int = 0)
}
