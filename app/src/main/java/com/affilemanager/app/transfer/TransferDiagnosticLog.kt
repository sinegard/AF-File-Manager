package com.affilemanager.app.transfer

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Explicit wire identifiers: never serialize optimizer-controlled class or enum names. */
enum class TransferFailure(val code: String, val retryable: Boolean = false) {
    PERMISSION("AF-XFER-PERMISSION"), SPACE("AF-XFER-SPACE"), CONFLICT("AF-XFER-CONFLICT"),
    SOURCE("AF-XFER-SOURCE"), STORAGE("AF-XFER-STORAGE"), INVALID("AF-XFER-INVALID"),
    CANCELLED("AF-XFER-CANCELLED"), TIMEOUT("AF-XFER-TIMEOUT", true),
    CONNECTION("AF-XFER-CONNECTION", true), CALLBACK("AF-XFER-CALLBACK"),
    UNKNOWN("AF-XFER-UNKNOWN");

    companion object {
        fun fromCode(code: String?): TransferFailure? = entries.firstOrNull { it.code == code }
        fun classify(error: Throwable, phase: TransferPhase): TransferFailure {
            var cause: Throwable? = error
            repeat(8) {
                when (val current = cause) {
                    is AccessDeniedException, is SecurityException -> return PERMISSION
                    is FileAlreadyExistsException -> return CONFLICT
                    is SocketTimeoutException -> return TIMEOUT
                    is SocketException, is EOFException -> return CONNECTION
                    is java.util.concurrent.CancellationException -> return CANCELLED
                    is FileSystemException -> {
                        // Inspect only for a known category; never retain a message or path.
                        val reason = current.reason.orEmpty().lowercase(java.util.Locale.ROOT)
                        if ("no space" in reason || "enospc" in reason) return SPACE
                        if ("permission denied" in reason || "eacces" in reason || "eperm" in reason) return PERMISSION
                    }
                }
                cause = cause?.cause
            }
            if (error is IllegalArgumentException) return INVALID
            if (phase == TransferPhase.SOURCE) return SOURCE
            if (phase == TransferPhase.NOTIFY) return CALLBACK
            if (error is IOException && phase in setOf(TransferPhase.RESPONSE, TransferPhase.RECOVER)) return CONNECTION
            if (error is IOException || error is FileNotFoundException) return STORAGE
            return UNKNOWN
        }
    }
}

enum class TransferPhase(val code: String) {
    VALIDATE("validate"), SOURCE("source"), OPEN("open"), READ("read"), WRITE("write"),
    SYNC("sync"), COMMIT("commit"), NOTIFY("notify"), RESPONSE("response"),
    RECOVER("recover"), COMPLETE("complete"), CANCEL("cancel");
}
enum class TransferRole(val code: String) { SEND("send"), RECEIVE("receive") }
enum class TransferStorage(val code: String) { INTERNAL("internal"), REMOVABLE("removable"), UNKNOWN("unknown") }

data class TransferDiagnosticEvent(
    val role: TransferRole,
    val phase: TransferPhase,
    val batchId: String? = null,
    val fileIndex: Int = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val httpStatus: Int = 0,
    val failure: TransferFailure? = null,
    val errno: Int = 0,
    val freeBytes: Long = -1,
    val storage: TransferStorage = TransferStorage.UNKNOWN,
) {
    fun line(): String = listOf(
        role.code, phase.code,
        batchId?.takeIf { value -> runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) } ?: "-",
        fileIndex.coerceIn(0, NearbySourcePreparer.MAX_FILES),
        transferredBytes.coerceIn(0, NearbySourcePreparer.MAX_TOTAL_BYTES),
        totalBytes.coerceIn(0, NearbySourcePreparer.MAX_TOTAL_BYTES),
        httpStatus.coerceIn(0, 599), failure?.code ?: "-", errno.coerceIn(0, 4096),
        freeBytes.coerceAtLeast(-1), storage.code, System.currentTimeMillis().coerceAtLeast(0),
    ).joinToString("\t")
}

/** Opt-in, finite, app-private typed journal. No raw errors, paths, names, or peer information. */
internal class TransferDiagnosticLog(private val file: File) {
    companion object { const val MAX_EVENTS = 256; const val MAX_BYTES = 128 * 1024 }
    private val rows = java.util.ArrayDeque<String>()
    @Volatile var enabled = false
    @Volatile var storageFailed = false
        private set
    private var loaded = false

    @Synchronized fun record(event: TransferDiagnosticEvent) {
        if (!enabled) return
        load()
        rows.addLast(event.line())
        while (rows.size > MAX_EVENTS) rows.removeFirst()
        persist()
    }

    @Synchronized fun clear() {
        rows.clear(); loaded = true
        persist()
    }

    @Synchronized fun report(): String {
        load()
        return "role\tphase\ttransfer_id\tfile_index\tbytes\ttotal\thttp\terror_code\terrno\tfree_bytes\tstorage\ttime_ms\n" +
            rows.joinToString("\n") + "\ndiagnostic_storage_failed=$storageFailed\n"
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.isFile && file.length() <= MAX_BYTES) {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (safeLine(line)) {
                            rows.addLast(line)
                            if (rows.size > MAX_EVENTS) rows.removeFirst()
                        }
                    }
                }
            } else if (file.exists()) storageFailed = true
        } catch (_: Exception) { storageFailed = true }
    }

    private fun safeLine(line: String): Boolean {
        if (line.length > 400) return false
        val values = line.split('\t')
        if (values.size != 12 || TransferRole.entries.none { it.code == values[0] } ||
            TransferPhase.entries.none { it.code == values[1] } ||
            TransferStorage.entries.none { it.code == values[10] }) return false
        if (values[2] != "-" && !runCatching { UUID.fromString(values[2]).toString() == values[2] }.getOrDefault(false)) return false
        if (values[7] != "-" && TransferFailure.fromCode(values[7]) == null) return false
        return listOf(3, 4, 5, 6, 8, 9, 11).all { index ->
            values[index].toLongOrNull()?.let { it >= if (index == 9) -1 else 0 } == true
        }
    }

    private fun persist() {
        try {
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            val temporary = File(file.parentFile, "events.tmp")
            val text = rows.joinToString("\n")
            check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            java.io.FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            storageFailed = false
        } catch (_: Exception) { storageFailed = true }
    }
}

/** A server response is not an argument validation error or an implicit transport exception. */
internal class NearbyUploadException(val httpStatus: Int, val failure: TransferFailure?, message: String) : IOException(message)
internal class NearbySourceException(cause: IOException) : IOException("Failo srautas nepasiekiamas", cause)

internal class ReceiverUploadException(
    val failure: TransferFailure,
    val phase: TransferPhase,
    val errno: Int,
    cause: Throwable,
) : IOException(failure.code, cause)
