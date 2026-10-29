package com.affilemanager.app.advanced

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Real manager-authorized provider, never an in-process substitute for UID 0/2000. */
class PrivilegedReadLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchingAccessOffClosesAllOwnedReadsAndAllowsAReconnection() = exercise(false)

    @Test fun providerDeathClosesAllOwnedReadsAndAllowsAReconnection() = exercise(true)

    private fun exercise(killProvider: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val expectedUid = when {
            args.getString("afShizukuRoot") == "true" -> 0
            args.getString("afShizukuShell") == "true" -> 2_000
            else -> -1
        }
        assumeTrue("Requires the actual authorized emulator provider", expectedUid >= 0)
        val app = compose.activity.application as AFFileManagerApplication
        val manager = app.graph.advancedAccess
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = manager.state.value.selectedMode
        val root = File(checkNotNull(app.getExternalFilesDir("validation")),"read-lifecycle-${System.nanoTime()}")
            .canonicalFile.apply { check(mkdir()) }
        val target = File(root,"document.txt").apply { writeText("complete contents after reconnect") }
        val inputs = mutableListOf<java.io.InputStream>()
        var accepted = false
        fun connect() {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { manager.state.value.connected || manager.state.value.error != null }
            assertTrue(manager.state.value.error,manager.state.value.connected)
            assertEquals(expectedUid,manager.state.value.serviceUid)
        }
        try {
            connect()
            repeat(8) { inputs.add(app.graph.privilegedFiles.openInput(target.path)) }
            assertThrows(IOException::class.java) { app.graph.privilegedFiles.openInput(target.path) }
            if (killProvider) {
                val binder = manager.privilegedTerminalServiceOrThrow()
                val result = runCatching { binder.destroy() }
                result.exceptionOrNull()?.let { assertTrue(it.toString(),it is android.os.DeadObjectException) }
                compose.waitUntil(10_000) { !manager.state.value.connected }
            } else {
                compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.OFF) }
                assertFalse(manager.state.value.connected)
            }
            inputs.forEach { assertThrows(IOException::class.java) { it.read() }; it.close() }
            connect()
            app.graph.privilegedFiles.openInput(target.path).use {
                assertEquals("complete contents after reconnect",it.bufferedReader().readText())
            }
            assertEquals("complete contents after reconnect",target.readText())
            accepted=true
        } finally {
            inputs.forEach { it.close() }
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
            if (accepted) check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit","Retained failed read lifecycle fixture: $root")
        }
    }
}
