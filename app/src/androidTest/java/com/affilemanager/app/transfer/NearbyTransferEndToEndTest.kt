package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class NearbyTransferEndToEndTest {
    @Test fun senderStillWorksWithAnOlderReceiverWithoutTheMetadataEndpoint() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val source = File(app.cacheDir, "legacy-${UUID.randomUUID()}.txt").apply { writeText("legacy-compatible") }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val routes = CopyOnWriteArrayList<String>()
        val server = java.net.ServerSocket(0, 3, address).apply { soTimeout = 10_000 }
        try {
            val peer = executor.submit {
                repeat(3) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val route = input.readLine().split(' ')[1].substringBefore('?')
                        routes += route
                        var length = 0
                        while (true) {
                            val header = input.readLine() ?: error("Missing request header")
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(body, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        if (route == "/upload") assertEquals(source.readText(), String(body))
                        val status = if (route == "/nearby/manifest") "404 Not Found" else "200 OK"
                        val cookie = if (route == "/login") "Set-Cookie: af_session=fixture; HttpOnly\r\n" else ""
                        socket.getOutputStream().write("HTTP/1.1 $status\r\n${cookie}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }
            val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
            NearbyTransferController.start(app, NearbyPairing.create(address.hostAddress!!, server.localPort, "12345678"), prepared)
            withTimeout(20_000) {
                while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(50)
            }
            val result = NearbyTransferController.state.value
            assertEquals(result.message, NearbyTransferStatus.COMPLETED, result.status)
            peer.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("/login", "/nearby/manifest", "/upload"), routes.toList())
        } finally {
            server.close()
            executor.shutdownNow()
            NearbyTransferController.clearFinished()
            source.delete()
        }
    }

    @Test fun servicePublishesBothFileListsAndPreservesExistingDestination() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "nearby-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "source").apply { mkdir() }
        val destination = File(root, "destination").apply { mkdir() }
        val text = File(source, "hello.txt").apply { writeText("AF test content") }
        val empty = File(source, "empty.txt").apply { createNewFile() }
        File(destination, text.name).writeText("keep me")
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val updates = CopyOnWriteArrayList<LanUploadProgress>()
        try {
            LanHttpServer(destination, address, requestedCode = "12345678", onUploadProgress = { updates += it }).use { server ->
                val session = server.start()
                val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(text.path, empty.path)).getOrThrow()
                NearbyTransferController.clearFinished()
                NearbyTransferController.start(app, NearbyPairing.create(session.address, session.port, session.code, "Test receiver"), prepared)
                val finished = withTimeout(20_000) {
                    while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(50)
                    NearbyTransferController.state.value
                }
                assertEquals(finished.message, NearbyTransferStatus.COMPLETED, finished.status)
                assertEquals(2, finished.completedFiles)
                assertEquals(2, finished.files.size)
                assertTrue(finished.files.all { it.status == TransferFileStatus.COMPLETED })
                assertEquals(listOf(text.canonicalPath, empty.canonicalPath).toSet(), finished.files.map { it.localPath }.toSet())
                assertEquals(2, updates.first().files.size) // All metadata arrives before the first file.
                assertTrue(updates.first().files.all { it.localPath == null })
                val received = updates.last().files
                assertTrue(received.all { it.status == TransferFileStatus.COMPLETED && it.localPath != null })
                val copiedText = received.first { it.name == text.name }
                assertEquals(text.readText(), File(requireNotNull(copiedText.localPath)).readText())
                assertEquals("keep me", File(destination, text.name).readText())
                assertTrue(destination.listFiles().orEmpty().none { it.name.endsWith(".partial") })
            }
        } finally {
            NearbyTransferController.clearFinished()
            root.deleteRecursively()
        }
    }
}
