package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransferDiagnosticsExportTest {
    @Test fun explicitExportIncludesActualPermissionsAndBoundedAndroidExitCodesWithoutPathsOrTraces() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        TransferDiagnostics.initialize(app)
        val report = TransferDiagnostics.export(app).readText()
        assertTrue(report.contains("all_files_access="))
        assertTrue(report.contains("legacy_read_permission="))
        assertTrue(report.contains("legacy_write_permission="))
        val exits = report.lineSequence().filter { it.startsWith("android_process_exit=") }.toList()
        assertTrue(exits.size in 1..3)
        if (android.os.Build.VERSION.SDK_INT < 30) assertEquals(listOf("android_process_exit=unsupported"), exits)
        else assertTrue(exits.all { it.matches(Regex("android_process_exit=(none|unavailable|time:[0-9]+,reason:[0-9]+,status:-?[0-9]+,importance:[0-9]+)")) })
        assertFalse(report.contains(app.filesDir.path))
        assertFalse(report.contains("trace_file="))
    }
}
