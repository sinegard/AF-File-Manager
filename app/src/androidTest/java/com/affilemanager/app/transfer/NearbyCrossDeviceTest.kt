package com.affilemanager.app.transfer

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

/** Opt-in two-emulator validation. Normal connected suites skip it without afCrossRole. */
class NearbyCrossDeviceTest {
    @Test fun android8SenderStreamsThirtyMegabytesToNewAndroidReceiver() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        when (val role = arguments.getString("afCrossRole")) {
            "receiver" -> receive()
            "sender" -> send(
                host = arguments.getString("afCrossHost") ?: "10.0.2.2",
                port = arguments.getString("afCrossPort")?.toIntOrNull() ?: HOST_FORWARD_PORT,
            )
            null -> assumeTrue("Set afCrossRole to receiver or sender", false)
            else -> error("Unknown afCrossRole: $role")
        }
    }

    private suspend fun receive() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "cross-device-receiver-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        try {
            LanHttpServer(
                rootDirectory = root,
                bindAddress = address,
                requestedPort = RECEIVER_PORT,
                requestedCode = PAIRING_CODE,
            ).use { server ->
                val session = server.start()
                Log.i(LOG_TAG, "READY ${session.address}:${session.port}")
                val received = File(root, FILE_NAME)
                withTimeout(CROSS_TIMEOUT_MILLIS) {
                    while (!received.isFile || received.length() != FILE_BYTES) delay(100)
                }
                assertArrayEquals(expectedDigest(), digest(received))
                assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                Log.i(LOG_TAG, "RECEIVED ${received.length()}")
                delay(500)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun send(host: String, port: Int) {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "cross-device-sender-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val source = File(root, FILE_NAME)
        writeFixture(source)
        try {
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
            NearbyTransferController.start(
                app,
                NearbyPairing.create(host, port, PAIRING_CODE, "API 36 receiver"),
                prepared,
            )
            val finished = withTimeout(CROSS_TIMEOUT_MILLIS) {
                while (NearbyTransferController.state.value.status in setOf(
                        NearbyTransferStatus.STARTING,
                        NearbyTransferStatus.RUNNING,
                    )) delay(100)
                NearbyTransferController.state.value
            }
            assertEquals(finished.message, NearbyTransferStatus.COMPLETED, finished.status)
            assertEquals(FILE_BYTES, finished.sentBytes)
            assertArrayEquals(expectedDigest(), digest(source))
        } finally {
            NearbyTransferController.cancel(app)
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            root.deleteRecursively()
        }
    }

    private fun writeFixture(file: File) {
        val block = fixtureBlock()
        file.outputStream().buffered().use { output -> repeat(BLOCK_COUNT) { output.write(block) } }
        block.fill(0)
        check(file.length() == FILE_BYTES)
    }

    private fun expectedDigest(): ByteArray = MessageDigest.getInstance("SHA-256").run {
        val block = fixtureBlock()
        repeat(BLOCK_COUNT) { update(block) }
        block.fill(0)
        digest()
    }

    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        digest()
    }

    private fun fixtureBlock() = ByteArray(BLOCK_BYTES) { index -> (index * 31 + 7).toByte() }

    private companion object {
        const val LOG_TAG = "AF_CROSS_DEVICE"
        const val PAIRING_CODE = "12345678"
        const val FILE_NAME = "android8-to-new.mp4"
        const val RECEIVER_PORT = 19_090
        const val HOST_FORWARD_PORT = 19_091
        const val BLOCK_BYTES = 256 * 1_024
        const val BLOCK_COUNT = 120
        const val FILE_BYTES = BLOCK_BYTES.toLong() * BLOCK_COUNT
        const val CROSS_TIMEOUT_MILLIS = 180_000L
    }
}
