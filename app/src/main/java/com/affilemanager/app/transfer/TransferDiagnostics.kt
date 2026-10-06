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
            "manufacturer=${safeDeviceValue(Build.MANUFACTURER)}\nmodel=${safeDeviceValue(Build.MODEL)}\n" + log.report()
        check(report.toByteArray(Charsets.UTF_8).size <= TransferDiagnosticLog.MAX_BYTES + 1024)
        val destination = File(context.cacheDir, "transfer-diagnostics/report.txt")
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
        destination.writeText(report)
        mutableStorageFailed.value = log.storageFailed
        destination
    }

    fun errno(error: Throwable): Int {
        var cause: Throwable? = error
        repeat(8) {
            (cause as? ErrnoException)?.let { return it.errno.coerceIn(0, 4096) }
            cause = cause?.cause
        }
        return 0
    }

    fun storage(root: File): TransferStorage = runCatching {
        if (Environment.isExternalStorageRemovable(root)) TransferStorage.REMOVABLE else TransferStorage.INTERNAL
    }.getOrDefault(TransferStorage.UNKNOWN)
}
