package com.affilemanager.app.transfer

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID

/** Permission modes are set externally between runs because Android restarts the UID on a change. */
class Issue215StorageDiagnosticTest {
    @Test fun sharedDownloadsWithAndWithoutAllFilesAccess(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("issue215StorageMode")
        assumeTrue("Opt-in owned emulator fixture in three separate permission-mode runs", mode != null)
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val id = requireNotNull(arguments.getString("issue215StorageFixture"))
        require(UUID.fromString(id).toString() == id)
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AFIssue215-$id")
        val source = File(app.cacheDir, "issue215-storage-$id.mp4")
        if (mode == "granted") {
            assertTrue(Environment.isExternalStorageManager())
            check(root.mkdir())
            source.outputStream().use { output ->
                val block = ByteArray(65_536) { (it % 251).toByte() }
                repeat(5 * 16) { output.write(block) }
            }
        }
        val before = digest(source)
        if (mode == "verify") {
            assertTrue(Environment.isExternalStorageManager())
            assertEquals(before, digest(File(root, "granted.mp4")))
            val restricted = File(root, "restricted.mp4")
            if (restricted.exists()) assertEquals(before, digest(restricted))
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            Log.i("Issue215Diagnostic", "shared_downloads_access restored=true originals_unchanged=true restrictedOutputExists=${restricted.exists()}")
            check(root.name == "AFIssue215-$id" && root.parentFile!!.canonicalFile == Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
            root.deleteRecursively(); source.delete()
            return@runBlocking
        }
        require(mode == "granted" || mode == "restricted")
        assertEquals(mode == "granted", Environment.isExternalStorageManager())
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        suspend fun finish(): NearbyTransferState = withTimeout(20_000) {
            while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
            NearbyTransferController.state.value
        }
        try {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            val serverResult = runCatching { LanHttpServer(root, address, requestedCode = "12345678") }
            if (serverResult.isFailure) {
                assertEquals("restricted", mode)
                val failure = serverResult.exceptionOrNull()!!
                Log.i("Issue215Diagnostic", "restricted_shared_downloads start_rejected exception=${failure.javaClass.simpleName} message=${failure.message}")
                assertEquals(before, digest(source))
                return@runBlocking
            }
            serverResult.getOrThrow().use { server ->
                val session = server.start()
                val peer = NearbyPairing.create(session.address, session.port, session.code)
                val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
                NearbyTransferController.start(app, peer, prepared.copy(relativePaths = listOf("$mode.mp4")))
                val state = finish()
                if (mode == "granted") {
                    assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
                    assertEquals(before, digest(File(root, "granted.mp4")))
                }
                Log.i("Issue215Diagnostic", "${mode}_shared_downloads status=${state.status} message=${state.message} paired=${NearbyTransferController.connection.cookieFor(peer) != null}")
                assertEquals(before, digest(source))
            }
        } finally {
            NearbyTransferController.cancel(app)
            delay(900)
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
        }
    }

    private fun digest(file: File): List<Byte> = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(65_536)
        while (true) { val length = input.read(block); if (length < 0) break; hash.update(block, 0, length) }
        hash.digest().toList()
    }
}
