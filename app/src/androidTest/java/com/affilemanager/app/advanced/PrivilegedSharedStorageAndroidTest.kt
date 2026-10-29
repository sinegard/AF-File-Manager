package com.affilemanager.app.advanced

import android.system.Os
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.terminal.PrivilegedTerminalRuntimeVerifier
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A different emulated-storage mount must not hide a published file from its owner. */
class PrivilegedSharedStorageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun replacingPrivilegedRenameIsImmediatelyReadableThroughTheAppMount() = fixture { root, app, service ->
        publish(root, app, service)
    }

    @Test fun productionSavePreservesAccessOrExplicitlyRefusesAnUnsupportedOwnerChange() = fixture { _, app, _ ->
        assertTrue(PrivilegedTerminalRuntimeVerifier.verifyAuthorizedBackend(app, 2_000))
        assertTrue(PrivilegedTerminalRuntimeVerifier.fileWriteCapability() in setOf("WRITE_SUPPORTED", "OWNER_CHANGE_BLOCKED"))
    }

    @Test fun afInputAfterAppAtomicReplacementPreservesWholeContentAndFreshMetadata() = observeAppPublication(false)

    @Test fun afInputAfterMetadataPinningStillPreservesWholeContent() = observeAppPublication(true)

    @Test fun afInputAfterEmptyShorterAndBoundaryAtomicReplacementsIsComplete() = fixture { root,app,_ ->
        val records=org.json.JSONArray()
        for(size in listOf(0,1,7,8,9,65_535,65_536,65_537,300_017)) {
            val target=File(root,"boundary-$size.bin").apply { writeText("original") }
            val remote=app.graph.advancedAccess.fileSystemOrThrow().getFile(target.path)
            assertEquals(8L,remote.length())
            val bytes=ByteArray(size) { (it%251).toByte() }
            val stage=File(root,".boundary-$size.tmp")
            stage.outputStream().use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(stage.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            var count=0L
            var immediate=-1L
            app.graph.privilegedFiles.openInput(target.path).use { input ->
                immediate=remote.length()
                val buffer=ByteArray(65_536)
                while(true) {
                    val read=input.read(buffer)
                    if(read<0)break
                    check(read in 1..buffer.size); count+=read
                    check(count<=size); digest.update(buffer,0,read)
                }
            }
            records.put(org.json.JSONObject().put("bytes",size).put("immediate",immediate)
                .put("read",count).put("afterRead",remote.length()))
            File(uiAuditDirectory(app),"af-atomic-boundaries.json").writeText(records.toString())
            assertEquals(size.toLong(),immediate)
            assertEquals(size.toLong(),count)
            assertEquals(size.toLong(),remote.length())
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),digest.digest())
            assertArrayEquals(bytes,target.readBytes())
        }
    }

    private fun observeAppPublication(pinMetadata: Boolean) = fixture { root, app, service ->
        val records = org.json.JSONArray()
        repeat(3) { index ->
            val target = File(root,"document-$index.txt").apply { writeText("original") }
            val edited = "edited in AF\nsecond line\n"
            val stage = File(root,".stage-$index.txt")
            stage.outputStream().use { it.write(edited.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val remote = app.graph.advancedAccess.fileSystemOrThrow().getFile(target.path)
            assertEquals(8L,remote.length())
            val started = android.os.SystemClock.uptimeMillis()
            java.nio.file.Files.move(stage.toPath(),target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (pinMetadata) PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            var immediate = -1L
            val received = app.graph.privilegedFiles.openInput(target.path).use { stream ->
                immediate = remote.length()
                val bytes = ByteArray(26)
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes,count,bytes.size-count)
                    if (read < 0) break
                    check(read > 0); count += read
                }
                bytes.copyOf(count).toString(Charsets.UTF_8)
            }
            val afterRead = remote.length()
            records.put(org.json.JSONObject().put("path",target.path).put("immediate",immediate)
                .put("afterRead",afterRead).put("providerText",received).put("appText",target.readText())
                .put("elapsed",android.os.SystemClock.uptimeMillis()-started))
            val name = if (pinMetadata) "af-app-to-provider-metadata-pin.json" else "af-app-to-provider-publication.json"
            File(uiAuditDirectory(app),name).writeText(records.toString())
            assertEquals(edited,target.readText())
            assertEquals(edited,received)
            assertEquals(25L,immediate)
            assertEquals(25L,afterRead)
        }
    }

    private fun publish(root: File, app: AFFileManagerApplication, service: IPrivilegedFileService) {
        val target = File(root, "document.txt").apply { writeText("original") }
        // Give publication a valid app-owned stage; shell chown is a separate capability.
        val stage = File(root, ".stage.txt").apply { writeText("edited") }
        val fs = app.graph.advancedAccess.fileSystemOrThrow()
        check(fs.getFile(stage.path).renameTo(fs.getFile(target.path)))
        try {
            assertEquals("edited", target.readText())
        } catch (error: Throwable) {
            val stat = runCatching { Os.stat(target.path).let { "${it.st_dev}:${it.st_ino}:${it.st_uid}:${it.st_gid}:${it.st_mode}:${it.st_size}" } }
            android.util.Log.e("AFRootAudit", "Shared mount: appStat=$stat appList=${root.list()?.toList()} " +
                "service=${runCatching { service.readFileMetadata(target.path).toList() }}", error)
            throw error
        }
    }

    private fun fixture(block: (File, AFFileManagerApplication, IPrivilegedFileService) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuShell") == "true")
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val root = File(checkNotNull(app.getExternalFilesDir("validation")), "shared-metadata-${System.nanoTime()}")
            .canonicalFile.apply { check(mkdir()) }
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected || app.graph.advancedAccess.state.value.error != null }
            assertEquals(2_000, app.graph.advancedAccess.state.value.serviceUid)
            block(root, app, app.graph.advancedAccess.fileMetadataServiceOrThrow())
            accepted = true
        } finally {
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
            if (accepted) check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit", "Retained failed shared-mount fixture: $root")
        }
    }
}
