package com.affilemanager.app.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LocalPtyBackend private constructor(
    private val handle: Long,
) : TerminalBackend {
    private val closed = AtomicBoolean(false)

    companion object {
        private const val SYSTEM_SHELL = "/system/bin/sh"

        suspend fun open(
            workingDirectory: File,
            homeDirectory: File,
            temporaryDirectory: File,
            rows: Int = TerminalLimits.INITIAL_ROWS,
            columns: Int = TerminalLimits.INITIAL_COLUMNS,
        ): LocalPtyBackend {
            var created: LocalPtyBackend? = null
            try {
                return withContext(Dispatchers.IO) {
                    TerminalLimits.requireDimensions(rows, columns)
                    val canonicalWorkingDirectory = workingDirectory.canonicalFile
                    require(canonicalWorkingDirectory.isDirectory) { "Working directory is unavailable" }
                    require(canonicalWorkingDirectory.canRead()) { "Working directory cannot be read" }
                    require(homeDirectory.isDirectory || homeDirectory.mkdirs()) { "Terminal home is unavailable" }
                    require(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) { "Terminal temporary directory is unavailable" }
                    val environment = arrayOf(
                        "HOME=${homeDirectory.canonicalPath}",
                        "PATH=/system/bin:/system/xbin",
                        "TERM=xterm-256color",
                        "COLORTERM=truecolor",
                        "TMPDIR=${temporaryDirectory.canonicalPath}",
                        "LANG=en_US.UTF-8",
                        "AF_FILE_MANAGER_TERMINAL=1",
                    ).map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
                    val nativeHandle = LocalPtyNative.spawn(
                        shell = SYSTEM_SHELL.toByteArray(Charsets.UTF_8),
                        workingDirectory = canonicalWorkingDirectory.path.toByteArray(Charsets.UTF_8),
                        environment = environment,
                        rows = rows,
                        columns = columns,
                    )
                    check(nativeHandle > 0) { "Android pseudo-terminal did not start" }
                    LocalPtyBackend(nativeHandle).also { created = it }
                }
            } catch (error: Throwable) {
                created?.close()
                throw error
            }
        }
    }

    override suspend fun read(destination: ByteArray): Int = withContext(Dispatchers.IO) {
        require(destination.isNotEmpty() && destination.size <= TerminalLimits.IO_CHUNK_BYTES)
        if (closed.get()) -1 else LocalPtyNative.read(handle, destination)
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && offset <= source.size && length <= source.size - offset) {
            "Invalid terminal input range"
        }
        require(length <= TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES) { "Terminal input chunk is too large" }
        var writtenTotal = 0
        while (writtenTotal < length) {
            if (closed.get()) throw IOException("Terminal is closed")
            val written = LocalPtyNative.write(handle, source, offset + writtenTotal, length - writtenTotal)
            if (written < 0) throw IOException("Terminal input failed")
            if (written == 0) continue
            writtenTotal += written
        }
    }

    override suspend fun resize(rows: Int, columns: Int) = withContext(Dispatchers.IO) {
        TerminalLimits.requireDimensions(rows, columns)
        if (!closed.get()) LocalPtyNative.resize(handle, rows, columns)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) LocalPtyNative.close(handle)
    }
}
