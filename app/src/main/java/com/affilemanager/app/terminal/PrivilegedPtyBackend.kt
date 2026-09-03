package com.affilemanager.app.terminal

import com.affilemanager.app.advanced.IPrivilegedFileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** PTY transport backed by the active root or Shizuku service, not by the app UID. */
class PrivilegedPtyBackend private constructor(
    private val service: IPrivilegedFileService,
    private val handle: Long,
) : TerminalBackend {
    companion object {
        private const val BINDER_READ_BYTES = 16 * 1024

        suspend fun open(
            service: IPrivilegedFileService,
            workingDirectory: String,
            rows: Int = TerminalLimits.INITIAL_ROWS,
            columns: Int = TerminalLimits.INITIAL_COLUMNS,
        ): PrivilegedPtyBackend {
            var created: PrivilegedPtyBackend? = null
            try {
                return withContext(Dispatchers.IO) {
                    TerminalLimits.requireDimensions(rows, columns)
                    val handle = service.openTerminal(workingDirectory, rows, columns)
                    check(handle > 0L) { "Privileged Android pseudo-terminal did not start" }
                    PrivilegedPtyBackend(service, handle).also { created = it }
                }
            } catch (error: Throwable) {
                // Cancellation can win after Binder created the PTY but before withContext
                // delivers it to the caller. Release that handle just like the local backend.
                created?.close()
                throw error
            }
        }
    }

    private val closed = AtomicBoolean(false)

    override suspend fun read(destination: ByteArray): Int = withContext(Dispatchers.IO) {
        require(destination.isNotEmpty() && destination.size <= TerminalLimits.IO_CHUNK_BYTES)
        if (closed.get()) return@withContext -1
        val transfer = ByteArray(min(destination.size, BINDER_READ_BYTES))
        val count = service.readTerminal(handle, transfer)
        if (count > 0) {
            check(count <= transfer.size) { "Privileged terminal returned an invalid read size" }
            transfer.copyInto(destination, endIndex = count)
        }
        count
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && offset <= source.size && length <= source.size - offset) {
            "Invalid terminal input range"
        }
        var writtenTotal = 0
        while (writtenTotal < length) {
            if (closed.get()) throw IOException("Privileged terminal is closed")
            val chunk = min(length - writtenTotal, TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES)
            // AIDL parcels the entire array, not just the offset/length range. Sending
            // the original paste for each chunk can exceed Binder's transaction limit.
            val transfer = source.copyOfRange(offset + writtenTotal, offset + writtenTotal + chunk)
            val written = service.writeTerminal(handle, transfer, 0, transfer.size)
            if (written < 0) throw IOException("Privileged terminal input failed")
            if (written == 0) continue
            check(written <= chunk) { "Privileged terminal returned an invalid write size" }
            writtenTotal += written
        }
    }

    override suspend fun resize(rows: Int, columns: Int) = withContext(Dispatchers.IO) {
        TerminalLimits.requireDimensions(rows, columns)
        if (!closed.get()) service.resizeTerminal(handle, rows, columns)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { service.closeTerminal(handle) }
    }
}
