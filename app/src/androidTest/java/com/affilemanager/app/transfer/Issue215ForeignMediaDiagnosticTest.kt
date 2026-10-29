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

/** An externally created media name can be invisible to a scoped-storage caller. */
class Issue215ForeignMediaDiagnosticTest {
    @Test fun receivedCopyKeepsAnExistingOtherOwnersVideo(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("issue215ForeignMode")
        assumeTrue("Explicit owned shared-storage fixture required", mode != null)
        val id = requireNotNull(arguments.getString("issue215ForeignFixture"))
        require(UUID.fromString(id).toString() == id)
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val parent = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val root = File(parent, "AFForeign215-$id")
        val source = File(app.cacheDir, "foreign215-$id.mp4")
        when (mode) {
            "prepare" -> {
                assertTrue(Environment.isExternalStorageManager())
                check(root.mkdir())
                source.outputStream().use { stream ->
                    val bytes = ByteArray(65_536) { (it % 251).toByte() }
                    repeat(80) { stream.write(bytes) }
                }
            }
            "receive" -> {
                assertFalse(Environment.isExternalStorageManager())
                val before = digest(source)
                try {
                    NearbyTransferController.connection.clear()
                    NearbyTransferController.clearFinished()
                    val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                        .first { it is Inet4Address && it.isSiteLocalAddress }
                    LanHttpServer(root, address).use { server ->
                        val session = server.start()
                        val peer = NearbyPairing.create(session.address, session.port, session.code)
                        val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
                        val visibleBefore = File(root, "received.mp4").exists()
                        NearbyTransferController.start(app, peer, prepared.copy(relativePaths = listOf("received.mp4")))
                        val state = withTimeout(20_000) {
                            while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
                            NearbyTransferController.state.value
                        }
                        Log.i("Issue215Diagnostic", "foreign_media visibleBefore=$visibleBefore status=${state.status}")
                        assertEquals(before, digest(source))
                        assertEquals("A received copy must keep the existing foreign media name: ${state.message}", NearbyTransferStatus.COMPLETED, state.status)
                    }
                } finally {
                    NearbyTransferController.cancel(app)
                    delay(900)
                    NearbyTransferController.connection.clear()
                    NearbyTransferController.clearFinished()
                }
            }
            "verify" -> {
                assertTrue(Environment.isExternalStorageManager())
                val expectedForeign = requireNotNull(arguments.getString("issue215ForeignSha256"))
                require(expectedForeign.matches(Regex("[a-fA-F0-9]{64}")))
                assertEquals(expectedForeign.lowercase(), digest(File(root, "received.mp4")))
                val copies = root.listFiles().orEmpty().filter { it.name != "received.mp4" && it.extension == "mp4" }
                assertEquals("Exactly one received copy must remain beside the foreign original", 1, copies.size)
                assertEquals(digest(source), digest(copies.single()))
                assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                check(root.name == "AFForeign215-$id" && root.parentFile!!.canonicalFile == parent.canonicalFile)
                check(root.deleteRecursively())
                check(source.delete())
            }
            else -> error("Unknown diagnostic phase")
        }
    }

    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65_536)
        while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
