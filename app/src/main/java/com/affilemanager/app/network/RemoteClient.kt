package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

interface RemoteClient {
    val verifiedHostFingerprint: String? get() = null
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun download(remotePath: String, localDestination: File, operation: OperationContext? = null)
    suspend fun upload(localSource: File, remotePath: String, operation: OperationContext? = null)
    suspend fun createDirectory(path: String)
    suspend fun rename(fromPath: String, toPath: String)
    suspend fun delete(path: String, recursive: Boolean = false)
    suspend fun close()
}

class SerializedRemoteClient(private val delegate: RemoteClient) : RemoteClient {
    private val mutex = Mutex()
    private var closed = false

    override val verifiedHostFingerprint: String?
        get() = delegate.verifiedHostFingerprint

    override suspend fun list(path: String): List<RemoteEntry> = guarded { delegate.list(path) }

    override suspend fun download(remotePath: String, localDestination: File, operation: OperationContext?) =
        guarded { delegate.download(remotePath, localDestination, operation) }

    override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) =
        guarded { delegate.upload(localSource, remotePath, operation) }

    override suspend fun createDirectory(path: String) = guarded { delegate.createDirectory(path) }

    override suspend fun rename(fromPath: String, toPath: String) = guarded { delegate.rename(fromPath, toPath) }

    override suspend fun delete(path: String, recursive: Boolean) = guarded { delegate.delete(path, recursive) }

    override suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                delegate.close()
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = mutex.withLock {
        check(!closed) { "Nuotolinis ryšys jau uždarytas" }
        block()
    }
}
