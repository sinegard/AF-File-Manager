package com.affilemanager.app.advanced

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Negative control for libsu's pipe API, NOT the acknowledged production writer. */
class PrivilegedPipeCompletionProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun aClosedLegacyPipeCanStillHaveAnUnfinishedRemoteWrite() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val root = File(app.cacheDir,"pipe-completion-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected || app.graph.advancedAccess.state.value.error != null }
            assertEquals(AdvancedAccessBackend.SHIZUKU_ROOT,app.graph.advancedAccess.state.value.activeBackend)
            val manager = app.graph.advancedAccess.fileSystemOrThrow()
            val service = app.graph.advancedAccess.privilegedTerminalServiceOrThrow()
            var attempts = 0
            var observed = -1L
            val payload = "original".toByteArray()
            while (attempts < 64 && observed == -1L) {
                val target = manager.getFile(File(root,"trial-${attempts++}.txt").path)
                service.createPrivateEntry(target.path,false)
                target.newOutputStream().use { it.write(payload); it.flush() }
                val immediate = target.length()
                if (immediate != payload.size.toLong()) observed = immediate
                compose.waitUntil(5_000) { target.length() == payload.size.toLong() }
                assertArrayEquals(payload,File(target.path).readBytes())
            }
            File(uiAuditDirectory(app),"legacy-pipe-completion.json").writeText(JSONObject()
                .put("api",android.os.Build.VERSION.SDK_INT).put("serviceUid",app.graph.advancedAccess.state.value.serviceUid)
                .put("attempts",attempts).put("expectedBytes",payload.size).put("immediateBytes",observed)
                .put("eventualContentVerified",true).put("source","Pinned libsu6.0.0 pipe; production uses acknowledged privileged writes")
                .toString())
            assumeTrue("No asynchronous-close window observed in64 attempts; not negative-control acceptance",observed >= 0)
            accepted=true
        } finally {
            try {
                if (accepted) check(root.deleteRecursively())
                else android.util.Log.e("AFRootAudit","Retained unaccepted legacy-pipe probe: $root")
            } finally { compose.runOnUiThread { vm.setAdvancedAccessMode(previous) } }
        }
    }
}
