package com.affilemanager.app.advanced

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream

/** Separate from the unchanged metadata wire version. Older cached services fail closed. */
internal object PrivilegedFileServiceProtocol {
    const val SERVICE_REVISION = 3
    const val READ_VERSION = 1
}

/**
 * A read-only, descriptor-pinned stream. Read to actual EOF, never a cached pre-open size.
 * The first real read refreshes legacy emulated-storage attributes; replay its byte once.
 */
internal class PrivilegedReadInputStream internal constructor(
    openInput: () -> InputStream,
    private val isAlive: () -> Boolean = { true },
) : InputStream() {
    companion object {
        const val MAX_OPEN_READS = 8
        private const val READ_ERROR = "Failo perskaityti nepavyko"
        private val reads = linkedSetOf<Lease>()

        private fun reserve(): Lease = synchronized(reads) {
            if (reads.size >= MAX_OPEN_READS) throw IOException(READ_ERROR)
            Lease().also { reads.add(it) }
        }

        /** Includes pending opens: a late Binder reply must not revive a cancelled read. */
        fun closeAll() {
            val owned = synchronized(reads) { reads.toList() }
            owned.forEach { runCatching { it.close() } }
        }
    }

    constructor(service: IPrivilegedFileService, path: String, isAuthorized: () -> Boolean = { true }) : this(
        openInput = {
            val descriptor = try {
                if (service.fileReadVersion != PrivilegedFileServiceProtocol.READ_VERSION) throw IOException(READ_ERROR)
                service.openReadOnlyFile(path) ?: throw IOException(READ_ERROR)
            } catch (error: Exception) { throw IOException(READ_ERROR, error) }
            try { ParcelFileDescriptor.AutoCloseInputStream(descriptor) }
            catch (error: Throwable) {
                kotlin.runCatching { descriptor.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        },
        isAlive = { service.asBinder().isBinderAlive && isAuthorized() },
    )

    private val lease = reserve()
    private var prefix = -1
    private var prefixPending = false

    init {
        try {
            // Reserve first, then validate: disconnect cannot be missed between
            // an earlier service capture and this open's admission.
            if (!isAlive()) throw IOException(READ_ERROR)
            lease.attach(openInput())
            prefix = reading { it.read() }
            prefixPending = prefix >= 0
        } catch (error: Throwable) {
            runCatching { lease.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun read(): Int = reading { input ->
        if (prefixPending) { prefixPending = false; prefix } else input.read()
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > destination.size - length) throw IndexOutOfBoundsException()
        return reading { input ->
            when {
                length == 0 -> 0
                prefixPending -> { destination[offset] = prefix.toByte(); prefixPending = false; 1 }
                else -> input.read(destination, offset, length).also {
                    if (it == 0 || it > length || it < -1) throw IOException(READ_ERROR)
                }
            }
        }
    }

    override fun skip(count: Long): Long = reading { input ->
        if (count <= 0) 0L
        else if (prefixPending) { prefixPending = false; 1L + if (count > 1) input.skip(count - 1) else 0L }
        else input.skip(count)
    }

    // InputStream permits this conservative estimate. It must not become a data-size limit.
    override fun available(): Int = reading { if (prefixPending) 1 else 0 }

    override fun close() = lease.close()

    private inline fun <T> reading(block: (InputStream) -> T): T {
        try {
            if (!isAlive()) throw IOException(READ_ERROR)
            val result = block(lease.input())
            if (lease.closed || !isAlive()) throw IOException(READ_ERROR)
            return result
        } catch (error: IOException) {
            runCatching { lease.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private class Lease {
        @Volatile var closed = false
            private set
        private var stream: InputStream? = null

        fun attach(input: InputStream) {
            synchronized(this) {
                if (!closed) { stream = input; return }
            }
            input.close()
            throw IOException(READ_ERROR)
        }

        @Synchronized fun input(): InputStream =
            if (closed) throw IOException(READ_ERROR) else stream ?: throw IOException(READ_ERROR)

        fun close() {
            val owned = synchronized(this) {
                if (closed) return
                closed = true
                stream.also { stream = null }
            }
            try { owned?.close() }
            finally { synchronized(reads) { reads.remove(this) } }
        }
    }
}
