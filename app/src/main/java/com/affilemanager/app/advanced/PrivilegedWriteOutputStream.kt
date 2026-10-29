package com.affilemanager.app.advanced

import java.io.OutputStream

/** One bounded scratch array; each Binder return acknowledges the actual privileged write. */
internal class PrivilegedWriteOutputStream(
    private val service: IPrivilegedFileService,
    path: String,
) : OutputStream() {
    // Allocate client memory before acquiring a remote resource.
    private val chunk = ByteArray(PrivilegedWriteHost.MAX_CHUNK)
    private val handle = service.openPrivateWrite(path).also { check(it > 0) { "Could not save the file" } }
    private var closed = false
    private var failed = false

    override fun write(value: Int) {
        chunk[0] = value.toByte()
        send(1)
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        check(!closed)
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val count = minOf(end - cursor, chunk.size)
            source.copyInto(chunk, 0, cursor, cursor + count)
            send(count)
            cursor += count
        }
    }

    private fun send(count: Int) {
        check(!closed && !failed)
        try { check(service.writePrivateFile(handle, chunk, count) == count) { "Could not save the file" } }
        catch (error: Exception) { failed = true; throw error }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (failed) service.abortPrivateWrite(handle) else service.finishPrivateWrite(handle)
        } catch (error: Exception) {
            runCatching { service.abortPrivateWrite(handle) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally { chunk.fill(0) }
    }
}
