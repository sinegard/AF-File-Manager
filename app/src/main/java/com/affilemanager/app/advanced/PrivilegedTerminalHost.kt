package com.affilemanager.app.advanced

import android.os.Process
import com.affilemanager.app.terminal.LocalPtyNative
import com.affilemanager.app.terminal.TerminalLimits
import java.io.File

/** Owns bounded PTY handles inside the already-authorized root or Shizuku service process. */
internal class PrivilegedTerminalHost {
    companion object {
        private const val SYSTEM_SHELL = "/system/bin/sh"
        private const val MAX_SESSIONS = 2
        private const val MAX_PATH_BYTES = 4_096
    }

    private val handles = linkedSetOf<Long>()

    @Synchronized
    fun open(workingDirectory: String, rows: Int, columns: Int): Long {
        TerminalLimits.requireDimensions(rows, columns)
        require(handles.size < MAX_SESSIONS) { "Privileged terminal session limit reached" }
        require(workingDirectory.isNotBlank() && '\u0000' !in workingDirectory && '\n' !in workingDirectory && '\r' !in workingDirectory) {
            "Invalid privileged terminal path"
        }
        require(workingDirectory.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES) {
            "Privileged terminal path is too long"
        }
        val requestedDirectory = File(workingDirectory)
        require(requestedDirectory.isAbsolute) { "Invalid privileged terminal path" }
        val directory = requestedDirectory.canonicalFile
        require(directory.isDirectory) { "Privileged terminal folder is unavailable" }
        require(directory.canRead()) { "Privileged terminal folder cannot be read" }
        val home = if (Process.myUid() == 0) "/" else "/data/local/tmp"
        val environment = arrayOf(
            "HOME=$home",
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "TMPDIR=/data/local/tmp",
            "LANG=en_US.UTF-8",
            "AF_FILE_MANAGER_TERMINAL=1",
            "AF_FILE_MANAGER_PRIVILEGED_TERMINAL=1",
        ).map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val handle = LocalPtyNative.spawn(
            shell = SYSTEM_SHELL.toByteArray(Charsets.UTF_8),
            workingDirectory = directory.path.toByteArray(Charsets.UTF_8),
            environment = environment,
            rows = rows,
            columns = columns,
        )
        check(handle > 0) { "Privileged Android pseudo-terminal did not start" }
        handles += handle
        return handle
    }

    fun read(handle: Long, destination: ByteArray): Int {
        synchronized(this) { require(handle in handles) { "Unknown privileged terminal session" } }
        require(destination.isNotEmpty() && destination.size <= TerminalLimits.IO_CHUNK_BYTES) {
            "Invalid privileged terminal read buffer"
        }
        return LocalPtyNative.read(handle, destination)
    }

    fun write(handle: Long, source: ByteArray, offset: Int, length: Int): Int {
        synchronized(this) { require(handle in handles) { "Unknown privileged terminal session" } }
        require(offset >= 0 && length >= 0 && offset <= source.size && length <= source.size - offset) {
            "Invalid privileged terminal input range"
        }
        require(length <= TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES) { "Privileged terminal input chunk is too large" }
        return LocalPtyNative.write(handle, source, offset, length)
    }

    fun resize(handle: Long, rows: Int, columns: Int) {
        synchronized(this) { require(handle in handles) { "Unknown privileged terminal session" } }
        TerminalLimits.requireDimensions(rows, columns)
        LocalPtyNative.resize(handle, rows, columns)
    }

    fun close(handle: Long) {
        val removed = synchronized(this) { handles.remove(handle) }
        if (removed) LocalPtyNative.close(handle)
    }

    fun closeAll() {
        val active = synchronized(this) {
            handles.toList().also { handles.clear() }
        }
        active.forEach { handle -> runCatching { LocalPtyNative.close(handle) } }
    }
}
