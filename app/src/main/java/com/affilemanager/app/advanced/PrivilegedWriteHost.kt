package com.affilemanager.app.advanced

import android.system.Os
import java.io.FileDescriptor

/** Synchronous writes: no asynchronous pipe close, private FIFO path, or unbounded worker queue. */
internal class PrivilegedWriteHost {
    companion object {
        const val MAX_CHUNK = 64 * 1_024
        private const val MAX_OPEN_WRITES = 8
    }

    private val writes = HashMap<Long, FileDescriptor>()
    private var nextHandle = 0L

    @Synchronized fun open(path: String): Long {
        check(writes.size < MAX_OPEN_WRITES) { "Could not save the file" }
        val handle = Math.incrementExact(nextHandle)
        val fd = PrivilegedMetadataOperations.openPrivateWrite(path)
        try {
            writes[handle] = fd
            nextHandle = handle
            return handle
        } catch (error: Throwable) {
            runCatching { Os.close(fd) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized fun write(handle: Long, source: ByteArray, length: Int): Int = ioCall {
        require(source.size <= MAX_CHUNK && length in 0..source.size)
        val fd = checkNotNull(writes[handle]) { "Could not save the file" }
        var offset = 0
        while (offset < length) {
            val count = Os.write(fd, source, offset, length - offset)
            check(count > 0) { "Could not save the file" }
            offset += count
        }
        offset
    }

    @Synchronized fun finish(handle: Long) = ioCall {
        val fd = checkNotNull(writes.remove(handle)) { "Could not save the file" }
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    @Synchronized fun abort(handle: Long) = ioCall { writes.remove(handle)?.let(Os::close); Unit }

    @Synchronized fun closeAll() {
        val owned = writes.values.toList()
        writes.clear()
        owned.forEach { runCatching { Os.close(it) } }
    }

    private inline fun <T> ioCall(block: () -> T): T = try { block() }
    catch (error: Exception) { throw IllegalStateException("Could not save the file", error) }
}
