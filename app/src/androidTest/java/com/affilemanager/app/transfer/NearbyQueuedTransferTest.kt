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
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class NearbyQueuedTransferTest {
    @Test fun realReceivingServiceStopKeepsResultsButDropsCredentials() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val complete = TransferFileProgress("received.txt", 12, 12, TransferFileStatus.COMPLETED, "/fixture/received.txt")
        try {
            LanTransferController.publish(LanTransferState(status = LanTransferStatus.RUNNING, url = "http://192.168.1.2:8080",
                code = "fixture-only", incomingUpload = LanUploadProgress("waiting.txt", 2, 2, 0, 8, 12, 20,
                    files = listOf(complete, TransferFileProgress("waiting.txt", 8)))))
            LanTransferController.stop(app)
            withTimeout(5_000) { while (LanTransferController.state.value.status != LanTransferStatus.STOPPED) delay(25) }
            val result = LanTransferController.state.value
            assertNull(result.url); assertNull(result.code)
            assertNotNull("Stopping the receiving service must retain its file results", result.incomingUpload)
            assertEquals(complete, result.incomingUpload!!.files.first())
            assertEquals(TransferFileStatus.CANCELLED, result.incomingUpload!!.files.last().status)
        } finally { LanTransferController.publish(LanTransferState()) }
    }

    @Test fun failedManifestAcknowledgementCancelsOnlyThatBatchBeforeTheNextManifest() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val original = File(app.cacheDir, "manifest-failure-${UUID.randomUUID()}.txt").apply { writeText("original remains") }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val server = ServerSocket(0, 4, address).apply { soTimeout = 1_000 }
        val executor = Executors.newSingleThreadExecutor()
        val running = AtomicBoolean(true)
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val manifests = java.util.concurrent.atomic.AtomicInteger()
        executor.submit {
            while (running.get()) {
                val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    catch (_: java.net.SocketException) { break }
                try { socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (bytes.size() < 16_384) {
                            val byte = input.read(); check(byte >= 0)
                            if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                            bytes.write(byte)
                        }
                        error("oversized fixture header")
                    }
                    val route = line().split(' ')[1].substringBefore('?')
                    val headers = mutableMapOf<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                    val length = headers["content-length"]!!.toInt()
                    val body = input.readNBytes(length); check(body.size == length)
                    events += route to headers["x-af-batch-id"].orEmpty()
                    if (route == "/upload") assertArrayEquals(original.readBytes(), body)
                    val status = if (route == "/nearby/manifest" && manifests.incrementAndGet() == 1) "500 Lost acknowledgement" else "200 OK"
                    val cookie = if (route == "/login") "Set-Cookie: af_session=manifest-fixture\r\nX-AF-Queue-Version: 1\r\n" else ""
                    it.getOutputStream().write("HTTP/1.1 $status\r\n${cookie}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } } catch (failure: Throwable) { if (running.get()) errors += failure }
            }
        }
        try {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            val peer = NearbyPairing.create(address.hostAddress!!, server.localPort, "12345678")
            suspend fun send() = NearbyTransferController.start(app, peer,
                app.graph.nearbySources.prepareLocalPaths(listOf(original.path)).getOrThrow())
            suspend fun finish() = withTimeout(8_000) {
                while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
            }
            send(); finish()
            assertEquals(NearbyTransferStatus.ERROR, NearbyTransferController.state.value.status)
            send(); finish()
            assertEquals(1, events.count { it.first == "/upload" })
            val announced = events.filter { it.first == "/nearby/manifest" }
            assertEquals(2, announced.size)
            val cancellation = "/nearby/cancel" to announced.first().second
            assertTrue("The accepted but unacknowledged batch must not block the receiver: $events", events.contains(cancellation))
            assertTrue(events.indexOf(cancellation) < events.indexOf(announced.last()))
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals("original remains", original.readText())
        } finally {
            NearbyTransferController.cancel(app); running.set(false); server.close()
            executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished(); original.delete()
        }
    }

    @Test fun cancellingBeforeLoginCleansEveryQueuedPrivateCopyAndKeepsOriginals() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val original = File(app.cacheDir, "queue-original-${UUID.randomUUID()}.txt").apply { writeText("keep original") }
        val stages = (1..2).map { File(app.cacheDir, "nearby-send-staging/${UUID.randomUUID()}").apply { check(mkdirs()) } }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val accepted = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 2, address).use { server ->
            server.soTimeout = 5_000
            val worker = executor.submit { server.accept().use { accepted.countDown(); finish.await(5, TimeUnit.SECONDS) } }
            try {
                NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
                val peer = NearbyPairing.create(address.hostAddress!!, server.localPort, "12345678")
                stages.forEachIndexed { index, stage ->
                    val copy = File(stage, "copy-$index.txt").apply { original.copyTo(this) }
                    NearbyTransferController.start(app, peer, PreparedNearbyTransfer(listOf(copy.path),
                        cleanupRootPath = stage.path, fileSizes = listOf(copy.length())))
                }
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                NearbyTransferController.cancel(app)
                withTimeout(5_000) { while (stages.any { it.exists() }) delay(25) }
                assertEquals("keep original", original.readText())
                assertTrue(NearbyTransferController.state.value.files.all { it.localPath == null && it.status == TransferFileStatus.CANCELLED })
            } finally {
                finish.countDown(); worker.get(3, TimeUnit.SECONDS); executor.shutdownNow()
                NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
                stages.forEach { it.deleteRecursively() }; original.delete()
            }
        }
    }

    @Test fun foregroundSenderAppendsDuringARealBlockedUploadAndSurvivesRapidNextBatches() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "nearby-queue-${UUID.randomUUID()}").apply { check(mkdir()) }
        val first = File(root, "first.bin").apply { outputStream().use { out -> repeat(256) { out.write(ByteArray(32768) { (it % 251).toByte() }) } } }
        val second = File(root, "second.txt").apply { writeText("still here after copy") }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val server = ServerSocket(0, 8, address).apply { soTimeout = 1_000 }
        val executor = Executors.newFixedThreadPool(4)
        val running = AtomicBoolean(true)
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val manifests = CopyOnWriteArrayList<List<TransferFileProgress>>()
        val uploads = CopyOnWriteArrayList<String>()
        val firstHeader = CountDownLatch(1)
        val appended = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val expectedHashes = listOf(first, second).associate { it.name to hash(it.readBytes()) }
        fun line(input: java.io.InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 16_384) {
                val byte = input.read(); check(byte >= 0)
                if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(byte)
            }
            error("oversized test header")
        }
        executor.submit {
            while (running.get()) {
                val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    catch (_: java.net.SocketException) { break }
                sockets.add(socket)
                executor.submit {
                    try { socket.use {
                        it.soTimeout = 15_000
                        val input = it.getInputStream().buffered()
                        val target = line(input).split(' ')[1]
                        val route = target.substringBefore('?')
                        val headers = mutableMapOf<String, String>()
                        while (true) { val header = line(input); if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                        val length = headers["content-length"]!!.toInt()
                        if (route == "/upload" && uploads.isEmpty()) {
                            firstHeader.countDown(); check(releaseUpload.await(12, TimeUnit.SECONDS))
                        }
                        val body = input.readNBytes(length); check(body.size == length)
                        if (route == "/nearby/manifest") {
                            manifests += NearbyTransferManifest.decode(body)
                            if (manifests.size == 2) appended.countDown()
                        }
                        if (route == "/upload") {
                            val name = target.substringAfter("name=").substringBefore('&')
                            assertEquals(expectedHashes[name], hash(body)); uploads += name
                        }
                        val cookie = if (route == "/login") "Set-Cookie: af_session=queue-fixture; HttpOnly\r\nX-AF-Queue-Version: 1\r\n" else ""
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\n${cookie}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } } catch (failure: Throwable) { if (running.get()) errors += failure }
                    finally { sockets.remove(socket) }
                }
            }
        }
        try {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            val peer = NearbyPairing.create(address.hostAddress!!, server.localPort, "12345678", "queue receiver")
            suspend fun send(file: File) = NearbyTransferController.start(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(file.path)).getOrThrow())
            suspend fun finish() = withTimeout(20_000) {
                while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
                val state = NearbyTransferController.state.value
                assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
            }
            send(first)
            assertTrue(firstHeader.await(8, TimeUnit.SECONDS))
            send(second)
            assertTrue("Second manifest must arrive before first upload completes", appended.await(8, TimeUnit.SECONDS))
            val queued = NearbyTransferController.state.value.files
            assertEquals(listOf(first.name, second.name), queued.map { it.name })
            assertEquals(TransferFileStatus.WAITING, queued.last().status)
            assertTrue(queued.first().status == TransferFileStatus.TRANSFERRING)
            releaseUpload.countDown(); finish()
            assertEquals(listOf(first.name, second.name), uploads.toList())
            repeat(8) { send(second); finish() }
            assertEquals(10, uploads.size)
            assertTrue("HTTP fixture errors: $errors", errors.isEmpty())
            listOf(first, second).forEach { assertEquals(expectedHashes[it.name], hash(it.readBytes())) }
        } finally {
            releaseUpload.countDown(); NearbyTransferController.cancel(app)
            running.set(false); server.close(); sockets.forEach { it.close() }; executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            root.deleteRecursively()
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
}
