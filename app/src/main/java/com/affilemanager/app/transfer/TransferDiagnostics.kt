package com.affilemanager.app.transfer

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.ErrnoException
import com.affilemanager.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Nothing leaves the device without an explicit user export. */
internal object TransferDiagnostics {
    const val STORAGE_ERROR_CODE = "AF-DIAG-STORAGE"
    private var journal: TransferDiagnosticLog? = null
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private val mutableStorageFailed = MutableStateFlow(false)
    val storageFailed = mutableStorageFailed.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (journal != null) return
        val selected = context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false)
        journal = TransferDiagnosticLog(File(context.filesDir, "transfer-diagnostics/events.log")).also { it.enabled = selected }
        mutableEnabled.value = selected
    }

    suspend fun setEnabled(context: Context, selected: Boolean) = withContext(Dispatchers.IO) {
        initialize(context)
        check(context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).edit().putBoolean("enabled", selected).commit())
        synchronized(this@TransferDiagnostics) {
            journal?.enabled = selected
            mutableEnabled.value = selected
        }
    }

    fun record(event: TransferDiagnosticEvent) {
        val log = synchronized(this) { journal } ?: return
        log.record(event)
        mutableStorageFailed.value = log.storageFailed
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        initialize(context)
        journal?.clear()
        mutableStorageFailed.value = journal?.storageFailed == true
        check(!mutableStorageFailed.value)
        File(context.cacheDir, "transfer-diagnostics/report.txt").takeIf(File::exists)?.let { check(it.delete()) }
    }

    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        initialize(context)
        val log = requireNotNull(journal)
        fun safeDeviceValue(value: String) = value.filter { it.isLetterOrDigit() || it in " ._-" }.take(80)
        val report = "AF transfer diagnostics v1\napp=${BuildConfig.VERSION_NAME}\napi=${Build.VERSION.SDK_INT}\n" +
            "manufacturer=${safeDeviceValue(Build.MANUFACTURER)}\nmodel=${safeDeviceValue(Build.MODEL)}\n" +
            storagePermissionReport(context) + recentProcessExitReport(context) + log.report()
        check(report.toByteArray(Charsets.UTF_8).size <= TransferDiagnosticLog.MAX_BYTES + 1024)
        val destination = File(context.cacheDir, "transfer-diagnostics/report.txt")
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
        destination.writeText(report)
        mutableStorageFailed.value = log.storageFailed
        destination
    }

    private fun storagePermissionReport(context: Context): String {
        fun permission(name: String) = context.checkSelfPermission(name) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val allFiles = if (Build.VERSION.SDK_INT >= 30) runCatching { Environment.isExternalStorageManager().toString() }
            .getOrDefault("unavailable") else "not_applicable"
        val legacyRead = if (Build.VERSION.SDK_INT <= 32) permission(android.Manifest.permission.READ_EXTERNAL_STORAGE).toString() else "not_applicable"
        val legacyWrite = if (Build.VERSION.SDK_INT <= 29) permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE).toString() else "not_applicable"
        return "all_files_access=$allFiles\nlegacy_read_permission=$legacyRead\nlegacy_write_permission=$legacyWrite\n"
    }

    private fun recentProcessExitReport(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "android_process_exit=unsupported\n"
        return try {
            val history = context.getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3).take(3)
            if (history.isEmpty()) "android_process_exit=none\n" else history.joinToString("") { exit ->
                // Android's numeric reason and time, not a guessed session cause.
                // Do not include descriptions, trace files, names or other apps.
                "android_process_exit=time:${exit.timestamp},reason:${exit.reason},status:${exit.status},importance:${exit.importance}\n"
            }
        } catch (_: SecurityException) { "android_process_exit=unavailable\n" }
        catch (_: RuntimeException) { "android_process_exit=unavailable\n" }
    }

    fun errno(error: Throwable): Int {
        var cause: Throwable? = error
        repeat(8) {
            (cause as? ErrnoException)?.let { return it.errno.coerceIn(0, 4096) }
            cause = cause?.cause
        }
        return TransferFileError.errno(error)
    }

    fun storage(root: File): TransferStorage = runCatching {
        if (Environment.isExternalStorageRemovable(root)) TransferStorage.REMOVABLE else TransferStorage.INTERNAL
    }.getOrDefault(TransferStorage.UNKNOWN)
}
