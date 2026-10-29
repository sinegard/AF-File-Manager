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
import java.io.InputStream
import java.security.MessageDigest

/** Matched stable-file measurements; separate from the stale-attribute regression. */
class PrivilegedReadPerformanceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun compareExistingLibraryAndOwnedDescriptorOnTheSameUnchangedFiles() {
        val args = InstrumentationRegistry.getArguments()
        val expectedUid = when {
            args.getString("afShizukuRoot") == "true" -> 0
            args.getString("afShizukuShell") == "true" -> 2_000
            else -> -1
        }
        assumeTrue("Requires an actual authorized provider",expectedUid >= 0)
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val root = File(checkNotNull(app.getExternalFilesDir("validation")),"read-cost-${System.nanoTime()}")
            .canonicalFile.apply { check(mkdir()) }
        val buffer = ByteArray(65_536) { (it % 251).toByte() }
        val records = org.json.JSONArray()
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            assertEquals(expectedUid,app.graph.advancedAccess.state.value.serviceUid)
            for (size in listOf(25,4_194_304)) {
                val target = File(root,"content-$size.bin")
                val expected = MessageDigest.getInstance("SHA-256")
                target.outputStream().use { output ->
                    var left=size
                    while(left>0) {
                        val count=minOf(left,buffer.size)
                        output.write(buffer,0,count); expected.update(buffer,0,count); left-=count
                    }
                    output.fd.sync()
                }
                val expectedHash=expected.digest()
                val remote=app.graph.advancedAccess.fileSystemOrThrow().getFile(target.path)
                fun measure(descriptor: Boolean): Long {
                    val digest=MessageDigest.getInstance("SHA-256")
                    var total=0L
                    val started=android.os.SystemClock.elapsedRealtimeNanos()
                    val input: InputStream = if(descriptor) app.graph.privilegedFiles.openInput(target.path)
                        else remote.newInputStream()
                    input.use {
                        while(true) {
                            val count=it.read(buffer)
                            if(count<0)break
                            check(count in 1..buffer.size)
                            total+=count; check(total<=size); digest.update(buffer,0,count)
                        }
                    }
                    val elapsed=android.os.SystemClock.elapsedRealtimeNanos()-started
                    assertEquals(size.toLong(),total)
                    assertArrayEquals(expectedHash,digest.digest())
                    return elapsed
                }
                measure(false); measure(true)
                repeat(7) { trial ->
                    val methods=if(trial%2==0) listOf(false,true) else listOf(true,false)
                    for(descriptor in methods) records.put(org.json.JSONObject().put("uid",expectedUid)
                        .put("bytes",size).put("trial",trial).put("reader",if(descriptor) "owned-fd" else "library")
                        .put("elapsedNanos",measure(descriptor)))
                    File(uiAuditDirectory(app),"privileged-read-cost.json").writeText(records.toString())
                }
            }
            accepted=true
        } finally {
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
            if(accepted)check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit","Retained failed reader comparison: $root")
        }
    }
}
