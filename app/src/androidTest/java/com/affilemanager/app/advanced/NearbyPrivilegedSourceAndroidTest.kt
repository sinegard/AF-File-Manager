package com.affilemanager.app.advanced

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.transfer.*
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyPrivilegedSourceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun protectedAndroidFolderStreamsAndRevocationDoesNotFallBackToRawAccess() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("afShizukuRoot") == "true" || args.getString("afShizukuShell") == "true")
        check(android.os.Build.MODEL.contains("sdk", true))
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val root = File(app.getExternalFilesDir(null), "nearby-protected-${System.nanoTime()}").apply { check(mkdir()) }
        val payload = "Authorized protected-folder payload\n".repeat(4_096)
        val source = File(root, "nested/content.txt").apply { check(parentFile!!.mkdir()); writeText(payload) }
        val destination = File(app.cacheDir, "nearby-privileged-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            assertEquals(if (args.getString("afShizukuRoot") == "true") 0 else 2_000,
                app.graph.advancedAccess.state.value.serviceUid)
            val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(root.path)).getOrThrow()
            assertEquals(listOf(""), prepared.paths)
            assertEquals(listOf(source.path), prepared.privilegedPaths)
            assertNull(prepared.cleanupRootPath)
            sendNearbyFixture(app, prepared, destination)
            assertEquals(source.readText(), File(destination, "${root.name}/nested/content.txt").readText())
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.OFF) }
            compose.waitUntil(5_000) { !app.graph.advancedAccess.state.value.connected }
            val denied = File(destination, "denied").apply { check(mkdir()) }
            sendNearbyFixture(app, prepared, denied, NearbyTransferStatus.ERROR)
            assertTrue(denied.listFiles()!!.isEmpty())
            assertEquals(payload, source.readText())
        } finally {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            check(root.deleteRecursively()); check(destination.deleteRecursively())
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
        }
    }

    @Test fun aDifferentAppPrivateLabelCanBeSentWithoutMakingAPublicOrCachedCopy() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        check(android.os.Build.MODEL.contains("sdk", true))
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val parent = "/data/data/moe.shizuku.privileged.api/cache"
        val path = "$parent/af-nearby-${System.nanoTime()}"
        val file = "$path/owned.txt"
        val bytes = ByteArray(320 * 1_024) { (it % 251).toByte() }
        val destination = File(app.cacheDir, "nearby-cross-app-${System.nanoTime()}").apply { check(mkdir()) }
        var created = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            assertEquals(0, app.graph.advancedAccess.state.value.serviceUid)
            val service = app.graph.advancedAccess.fileMetadataServiceOrThrow()
            service.createPrivateEntry(path, true); created = true
            val handle = service.openPrivateWrite(file)
            try {
                for (offset in bytes.indices step PrivilegedWriteHost.MAX_CHUNK) {
                    val chunk = bytes.copyOfRange(offset, minOf(offset + PrivilegedWriteHost.MAX_CHUNK, bytes.size))
                    assertEquals(chunk.size, service.writePrivateFile(handle, chunk, chunk.size))
                }
                service.finishPrivateWrite(handle)
            }
            catch (failure: Throwable) { service.abortPrivateWrite(handle); throw failure }
            val metadata = PrivilegedFileMetadata.decode(service.readFileMetadata(file))
            val parentMetadata = PrivilegedFileMetadata.decode(service.readFileMetadata(parent))
            service.applyFileMetadata(file, metadata.copy(selinuxHex = parentMetadata.selinuxHex).encode())
            val identity = service.readFileMetadata(file)
            assertFalse(File(file).canRead())
            val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(file)).getOrThrow()
            assertEquals(listOf(file), prepared.privilegedPaths)
            assertNull(prepared.cleanupRootPath)
            sendNearbyFixture(app, prepared, destination)
            assertArrayEquals(bytes, File(destination, "owned.txt").readBytes())
            assertEquals(identity, service.readFileMetadata(file))
        } finally {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            if (created) {
                val fs = app.graph.advancedAccess.fileSystemOrThrow()
                check(fs.getFile(file).delete()); check(fs.getFile(path).delete())
            }
            check(destination.deleteRecursively())
            compose.runOnUiThread { vm.setAdvancedAccessMode(previous) }
        }
    }
}
