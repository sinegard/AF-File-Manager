package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException
import java.util.Locale

object RemoteFailureClassifier {
    private val securityOrConfigurationMarkers = listOf(
        "auth", "login", "password", "credential", "certificate", "host key", "fingerprint",
        "permission", "denied", "unknown host", "not found", "no such file",
    )
    private val transientMarkers = listOf(
        "broken pipe", "connection reset", "connection abort", "connection closed",
        "connection is closed", "closed without indication", "socket closed", "socket is closed",
        "channel is closed", "channel is not opened", "session is down", "unexpected end of stream",
        "end of file", "timed out", "timeout", "eof", "no route to host",
    )

    fun isTransient(error: Throwable): Boolean {
        if (error is CancellationException) return false
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        if (securityOrConfigurationMarkers.any(message::contains)) return false
        return chain.any { it is SocketException || it is SocketTimeoutException || it is EOFException || it is ClosedChannelException } ||
            transientMarkers.any(message::contains)
    }
}

class ReconnectingRemoteClient(
    initial: RemoteClient,
    private val reconnect: suspend () -> RemoteClient,
    private val onReconnected: () -> Unit = {},
    private val transientFailure: (Throwable) -> Boolean = RemoteFailureClassifier::isTransient,
) : RemoteClient {
    private val mutex = Mutex()
    private var delegate = initial
    private var closed = false
    @Volatile
    private var fingerprint: String? = initial.verifiedHostFingerprint

    override val verifiedHostFingerprint: String?
        get() = fingerprint

    override suspend fun list(path: String): List<RemoteEntry> = retryOnce { client -> client.list(path) }

    override suspend fun download(remotePath: String, localDestination: java.io.File, operation: OperationContext?) =
        retryOnce { client -> client.download(remotePath, localDestination, operation) }

    override suspend fun upload(localSource: java.io.File, remotePath: String, operation: OperationContext?) =
        retryOnce { client -> client.upload(localSource, remotePath, operation) }

    override suspend fun createDirectory(path: String) = mutex.withLock {
        checkOpen()
        try {
            delegate.createDirectory(path)
        } catch (first: Throwable) {
            if (!transientFailure(first)) throw first
            val client = reconnectAfter(first)
            val normalized = RemotePath.normalize(path)
            val parent = RemotePath.normalize("$normalized/..")
            val existing = client.list(parent).firstOrNull { RemotePath.normalize(it.path) == normalized }
            when {
                existing?.directory == true -> Unit
                existing != null -> throw IllegalStateException("Nuotolinio aplanko vietoje jau yra failas", first)
                else -> retryWithOriginal(first) { client.createDirectory(normalized) }
            }
        }
    }

    override suspend fun rename(fromPath: String, toPath: String) = mutex.withLock {
        checkOpen()
        try {
            delegate.rename(fromPath, toPath)
        } catch (first: Throwable) {
            if (!transientFailure(first)) throw first
            val client = reconnectAfter(first)
            val from = RemotePath.normalize(fromPath)
            val to = RemotePath.normalize(toPath)
            val parent = RemotePath.normalize("$to/..")
            val entries = client.list(parent)
            val sourceExists = entries.any { RemotePath.normalize(it.path) == from }
            val targetExists = entries.any { RemotePath.normalize(it.path) == to }
            when {
                !sourceExists && targetExists -> Unit
                sourceExists && !targetExists -> retryWithOriginal(first) { client.rename(from, to) }
                else -> throw IllegalStateException("Nuotolinio pervadinimo būsenos patikimai nustatyti nepavyko", first)
            }
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = mutex.withLock {
        checkOpen()
        try {
            delegate.delete(path, recursive)
        } catch (first: Throwable) {
            if (!transientFailure(first)) throw first
            val client = reconnectAfter(first)
            val normalized = RemotePath.normalize(path)
            val parent = RemotePath.normalize("$normalized/..")
            val stillExists = client.list(parent).any { RemotePath.normalize(it.path) == normalized }
            if (stillExists) retryWithOriginal(first) { client.delete(normalized, recursive) }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                delegate.close()
            }
        }
    }

    private suspend fun <T> retryOnce(block: suspend (RemoteClient) -> T): T = mutex.withLock {
        checkOpen()
        try {
            block(delegate)
        } catch (first: Throwable) {
            if (!transientFailure(first)) throw first
            val client = reconnectAfter(first)
            retryWithOriginal(first) { block(client) }
        }
    }

    private suspend fun reconnectAfter(first: Throwable): RemoteClient {
        runCatching { delegate.close() }
        return try {
            reconnect().also { replacement ->
                delegate = replacement
                fingerprint = replacement.verifiedHostFingerprint
                onReconnected()
            }
        } catch (reconnectError: Throwable) {
            reconnectError.addSuppressed(first)
            throw reconnectError
        }
    }

    private suspend fun <T> retryWithOriginal(first: Throwable, block: suspend () -> T): T = try {
        block()
    } catch (retryError: Throwable) {
        retryError.addSuppressed(first)
        throw retryError
    }

    private fun checkOpen() = check(!closed) { "Nuotolinis ryšys jau uždarytas" }
}
