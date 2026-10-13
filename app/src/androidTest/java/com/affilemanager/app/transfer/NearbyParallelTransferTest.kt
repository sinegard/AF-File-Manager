package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class NearbyParallelTransferTest {
    @Test fun nativeSenderHonoursReceiverParallelismAndTypeDestinationWithoutDeletingSources() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "parallel-native-${UUID.randomUUID()}").apply { check(mkdir()) }
        val sourceRoot = root.resolve("sources").apply { check(mkdir()) }
        val target = root.resolve("receive").apply { check(mkdir()) }
        val documents = root.resolve("documents").apply { check(mkdir()) }
        val sources = (0..4).map { index -> sourceRoot.resolve("file-$index.txt").apply {
            outputStream().use { output -> val buffer = ByteArray(64 * 1024) { (index + it * 3).toByte() }; repeat(128) { output.write(buffer) } }
        } }
        val original = sources.map(::digest)
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val maximum = AtomicInteger()
        var prepared: PreparedNearbyTransfer? = null
        try {
            LanHttpServer(target, address, requestedCode = "12345678", concurrentUploads = 5,
                receiveDestinations = mapOf(com.affilemanager.app.model.EntryKind.DOCUMENT to documents),
                onUploadProgress = { progress -> maximum.accumulateAndGet(progress.files.count { it.status == TransferFileStatus.TRANSFERRING }, ::maxOf) }).use { server ->
                val session = server.start()
                prepared = app.graph.nearbySources.prepareLocalPaths(sources.map(File::getAbsolutePath)).getOrThrow()
                NearbyTransferController.clearFinished()
                NearbyTransferController.start(app, NearbyPairing.create(session.address, session.port, session.code, "Fixture"), prepared!!, null)
                val state = withTimeout(90000) {
                    while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(50)
                    NearbyTransferController.state.value
                }
                assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
                assertEquals(5, state.completedFiles)
                assertTrue("No real concurrent payloads were observed", maximum.get() in 2..5)
                sources.forEachIndexed { index, file ->
                    assertArrayEquals(original[index], digest(file))
                    assertArrayEquals(original[index], digest(documents.resolve(file.name)))
                    assertFalse(target.resolve(file.name).exists())
                }
                assertFalse(root.walkTopDown().any { it.name.endsWith(".partial") })
            }
        } finally {
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            prepared?.let { app.graph.nearbySources.discard(it) }
            root.deleteRecursively()
        }
    }
    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val read = input.read(buffer); if(read < 0) break; update(buffer, 0, read) } }; digest()
    }
}
