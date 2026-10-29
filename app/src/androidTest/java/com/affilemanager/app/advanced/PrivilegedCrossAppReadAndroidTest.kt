package com.affilemanager.app.advanced

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A different installed test app's MCS label, not just a different Unix owner. */
class PrivilegedCrossAppReadAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun authorizedRootCanReadItsOwnFixtureUnderAnotherAppsPrivateLabel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot")=="true")
        val app=compose.activity.application as AFFileManagerApplication
        val vm=ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous=app.graph.advancedAccess.state.value.selectedMode
        val path="/data/data/moe.shizuku.privileged.api/cache/af-owned-read-${System.nanoTime()}"
        val target="$path/document.txt"
        var accepted=false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            assertEquals(0,app.graph.advancedAccess.state.value.serviceUid)
            val service=app.graph.advancedAccess.fileMetadataServiceOrThrow()
            val parent=PrivilegedFileMetadata.decode(service.readFileMetadata("/data/data/moe.shizuku.privileged.api/cache"))
            service.createPrivateEntry(path,true)
            val bytes="Root fixture under another app label\n".toByteArray()
            val handle=service.openPrivateWrite(target)
            try { assertEquals(bytes.size,service.writePrivateFile(handle,bytes,bytes.size)); service.finishPrivateWrite(handle) }
            catch(error:Throwable) { service.abortPrivateWrite(handle); throw error }
            val created=PrivilegedFileMetadata.decode(service.readFileMetadata(target))
            // Pin the fixture to the installed peer's actual MCS label, not merely
            // a different UID or an arbitrary weaker label created by a root task.
            if(created.selinuxHex!=parent.selinuxHex) service.applyFileMetadata(target,created.copy(selinuxHex=parent.selinuxHex).encode())
            val security=PrivilegedFileMetadata.decode(service.readFileMetadata(target))
            assertEquals(parent.selinuxHex,security.selinuxHex)
            val appLabel=android.system.Os.getxattr(app.cacheDir.path,"security.selinux")
                .joinToString("") { "%02x".format(it) }
            File(uiAuditDirectory(app),"cross-app-read.json").writeText(org.json.JSONObject()
                .put("path",target).put("sourceLabel",security.selinuxHex).put("appLabel",appLabel)
                .put("createdLabel",created.selinuxHex).put("parentLabel",parent.selinuxHex)
                .put("appUid",android.os.Process.myUid()).put("sourceUid",security.uid).toString())
            assertNotEquals(appLabel,security.selinuxHex)
            assertFalse(File(target).canRead())
            val remote=app.graph.advancedAccess.fileSystemOrThrow().getFile(target)
            // Existing Root-side pipe is the authority-positive comparison, not production acceptance.
            remote.newInputStream().use { assertArrayEquals(bytes,it.readBytes()) }
            app.graph.privilegedFiles.openInput(target).use { assertArrayEquals(bytes,it.readBytes()) }
            assertEquals(security,PrivilegedFileMetadata.decode(service.readFileMetadata(target)))
            accepted=true
        } finally {
            if(accepted) {
                val fs=app.graph.advancedAccess.fileSystemOrThrow()
                check(fs.getFile(target).delete()); check(fs.getFile(path).delete())
            } else android.util.Log.e("AFRootAudit",path)
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
        }
    }
}
