package com.affilemanager.app.transfer

import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

class ReceivedFileCommitterAndroidTest {
    @Test fun sharedDownloadsCopyFallbackPreservesReportedSizesAndLargeFile() = withFixture { _, root, sourceRoot ->
        check(minOf(root.usableSpace, sourceRoot.usableSpace) >= 768L * 1024 * 1024) { "Insufficient owned fixture disk reserve" }
        val diagnostics = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        val progress = CopyOnWriteArrayList<LanUploadProgress>()
        LanHttpServer(root, InetAddress.getByName("127.0.0.1"), requestedCode = "12345678",
            receivedFileCommitter = deniedRename(), diagnosticsEnabled = { true },
            onDiagnostic = diagnostics::add, onUploadProgress = progress::add,
        ).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val sizes = listOf(1_818_418L, 4_509_722L, 30L * 1024 * 1024, 0L, 256L * 1024 * 1024)
            sizes.forEachIndexed { index, size ->
                val name = if (index == 0) "Received image with spaces.png" else "Received-$index.mp4"
                val source = File(sourceRoot, name).apply { writePattern(this, size) }
                val original = hash(source)
                val response = request(port, "POST /upload?dir=&name=${URLEncoder.encode(name, "UTF-8")}", cookie, source)
                assertTrue(response, response.startsWith("HTTP/1.1 201"))
                assertEquals(size, root.resolve(name).length())
                assertArrayEquals(original, hash(root.resolve(name)))
                assertArrayEquals(original, hash(source))
                assertTrue("Payload $index; completion flags ${progress.takeLast(6).map { it.completed }}; phases ${diagnostics.takeLast(6).map { it.phase }}",
                    progress.last().completed)
                assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".partial") })
            }
            // COMPLETE follows the response flush; a length-bounded client can return first.
            runBlocking { withTimeout(3000) { while (diagnostics.count { it.phase == TransferPhase.COMPLETE } < sizes.size) delay(10) } }
            assertEquals(sizes.size, diagnostics.count { it.phase == TransferPhase.COMMIT_COPY && it.failure == null })
            assertEquals(sizes.size, diagnostics.count { it.phase == TransferPhase.COMPLETE })
            assertTrue(diagnostics.any { it.phase == TransferPhase.COMMIT && it.errno == 1 })
        }
    }

    @Test fun tenRoundsOfFiveAndThirtyMiBCopiesInBothDirectionsKeepSourcesAndCleanPartials() = withFixture { _, root, _ ->
        check(root.usableSpace >= 256L * 1024 * 1024) { "Insufficient owned repeat fixture reserve" }
        val left = root.resolve("left").apply { check(mkdir()) }
        val right = root.resolve("right").apply { check(mkdir()) }
        fun sources(folder: File, prefix: String) = listOf(5, 30).map { mib ->
            folder.resolve("$prefix-$mib MiB.bin").apply { writePattern(this, mib.toLong() * 1024 * 1024) }
        }
        val leftSources = sources(left, "kairė")
        val rightSources = sources(right, "写真")
        val originals = (leftSources + rightSources).associateWith(::hash)
        val leftDiagnostics = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        val rightDiagnostics = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        val loopback = InetAddress.getByName("127.0.0.1")
        LanHttpServer(left, loopback, requestedCode = "12345678", receivedFileCommitter = deniedRename(),
            diagnosticsEnabled = { true }, onDiagnostic = leftDiagnostics::add).use { leftServer ->
            LanHttpServer(right, loopback, requestedCode = "12345678", receivedFileCommitter = deniedRename(),
                diagnosticsEnabled = { true }, onDiagnostic = rightDiagnostics::add).use { rightServer ->
                val leftPort = leftServer.start().port
                val rightPort = rightServer.start().port
                val leftCookie = login(leftPort)
                val rightCookie = login(rightPort)
                fun copy(port: Int, cookie: String, target: File, source: File, round: Int) {
                    val name = "received-$round-${source.name}"
                    val destination = target.resolve(name)
                    assertFalse(destination.exists())
                    val response = request(port, "POST /upload?dir=&name=${URLEncoder.encode(name, "UTF-8")}", cookie, source)
                    assertTrue(response, response.startsWith("HTTP/1.1 201"))
                    assertEquals(source.length(), destination.length())
                    assertArrayEquals(originals.getValue(source), hash(destination))
                    assertArrayEquals(originals.getValue(source), hash(source))
                    assertTrue(target.listFiles().orEmpty().none { it.name.endsWith(".partial") })
                    assertTrue("Verified per-round output cleanup", destination.delete())
                }
                repeat(10) { round ->
                    leftSources.forEach { copy(rightPort, rightCookie, right, it, round) }
                    rightSources.forEach { copy(leftPort, leftCookie, left, it, round) }
                }
                runBlocking { withTimeout(3000) {
                    while (listOf(leftDiagnostics, rightDiagnostics).any { events -> events.count { it.phase == TransferPhase.COMPLETE } < 20 }) delay(10)
                } }
                listOf(leftDiagnostics, rightDiagnostics).forEach { events ->
                    assertEquals(20, events.count { it.phase == TransferPhase.COMMIT_COPY && it.failure == null })
                    assertEquals(20, events.count { it.phase == TransferPhase.COMPLETE })
                }
                originals.forEach { (source, expected) -> assertArrayEquals(expected, hash(source)) }
                assertEquals(leftSources.toSet(), left.listFiles().orEmpty().toSet())
                assertEquals(rightSources.toSet(), right.listFiles().orEmpty().toSet())
            }
        }
    }

    @Test fun acknowledgedUploadReleasesItsPayloadSlotBeforeItsCompleteObserverFinishes() = withFixture { _, root, sources ->
        val first = File(sources, "first.txt").apply { writeText("one") }
        val second = File(sources, "second.txt").apply { writeText("two") }
        val originals = listOf(first, second).associateWith(::hash)
        val completeEntered = java.util.concurrent.CountDownLatch(1)
        val completeRelease = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        LanHttpServer(root, InetAddress.getByName("127.0.0.1"), requestedCode = "12345678",
            receivedFileCommitter = deniedRename(), diagnosticsEnabled = { true }, onDiagnostic = { event ->
                if (event.phase == TransferPhase.COMPLETE && completed.incrementAndGet() == 1) {
                    completeEntered.countDown()
                    check(completeRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }).use { server ->
            try {
                val port = server.start().port
                val cookie = login(port)
                assertTrue(request(port, "POST /upload?name=first.txt", cookie, first).startsWith("HTTP/1.1 201"))
                assertTrue("First response was read before its COMPLETE observer", completeEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                val response = request(port, "POST /upload?name=second.txt", cookie, second)
                assertTrue("A fully acknowledged payload still occupied its slot: $response", response.startsWith("HTTP/1.1 201"))
                originals.forEach { (source, expected) ->
                    assertArrayEquals(expected, hash(source))
                    assertArrayEquals(expected, hash(File(root, source.name)))
                }
                assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            } finally { completeRelease.countDown() }
        }
    }

    @Test fun actualSenderServiceCompletesFallbackAndRecoversAResponseFailureWithoutADuplicate() = runBlocking {
        withFixture { app, root, sourceRoot ->
            val source = File(sourceRoot, "sender.mp4").apply { writePattern(this, 4_509_722L) }
            val original = hash(source)
            val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .first { it is Inet4Address && it.isSiteLocalAddress }
            val completions = CopyOnWriteArrayList<LanUploadProgress>()
            LanHttpServer(root, address, requestedCode = "12345678", receivedFileCommitter = deniedRename(),
                onUploadProgress = completions::add,
                onMutation = { throw IOException("Synthetic notification failure after verified commit") },
            ).use { server ->
                val session = server.start()
                val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
                NearbyTransferController.clearFinished()
                NearbyTransferController.start(app, NearbyPairing.create(session.address, session.port, session.code), prepared)
                val result = withTimeout(45_000) {
                    while (NearbyTransferController.state.value.status in
                        setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(50)
                    NearbyTransferController.state.value
                }
                assertEquals(result.message, NearbyTransferStatus.COMPLETED, result.status)
                assertArrayEquals(original, hash(root.resolve(source.name)))
                assertArrayEquals(original, hash(source))
                assertEquals(1, root.listFiles().orEmpty().size)
            }
        }
    }

    @Test fun nativeManualRetryCopiesOnlyTheChosenFileAndRejectsDuplicateClicks() = runBlocking {
        withFixture { app, root, sourceRoot ->
            val sources = listOf("one.txt", "retry.txt", "three.txt").map { name -> File(sourceRoot, name).apply { writeText("original $name") } }
            val originals = sources.map(::hash)
            val reject = java.util.concurrent.atomic.AtomicBoolean(true)
            val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .first { it is Inet4Address && it.isSiteLocalAddress }
            LanHttpServer(root, address, requestedCode = "12345678", receivedFileCommitter = ReceivedFileCommitter { source, target ->
                if (target.name == "retry.txt" && reject.get()) throw java.nio.file.FileAlreadyExistsException(target.path)
                Files.move(source.toPath(), target.toPath())
                Unit
            }).use { server ->
                val session = server.start()
                NearbyTransferController.clearFinished()
                val prepared = app.graph.nearbySources.prepareLocalPaths(sources.map { it.path }).getOrThrow()
                NearbyTransferController.start(app, NearbyPairing.create(session.address, session.port, session.code), prepared)
                withTimeout(20_000) { while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25) }
                val first = NearbyTransferController.state.value
                assertEquals(first.message, NearbyTransferStatus.ERROR, first.status)
                assertEquals(TransferFileStatus.COMPLETED, first.files[0].status)
                assertEquals(TransferFileStatus.FAILED, first.files[1].status)
                assertEquals(TransferFileStatus.CANCELLED, first.files[2].status)
                assertTrue(first.files[2].notAttempted)
                val batch = first.files[1].batchId
                reject.set(false)
                NearbyTransferController.retryFile(app, batch, 2)
                assertTrue(runCatching { NearbyTransferController.retryFile(app, batch, 2) }.isFailure)
                withTimeout(20_000) { while (NearbyTransferController.state.value.files.none {
                    it.batchId != batch && it.relativePath == "retry.txt" && it.status == TransferFileStatus.COMPLETED
                }) delay(25) }
                assertEquals(setOf("one.txt", "retry.txt"), root.listFiles().orEmpty().map { it.name }.toSet())
                assertArrayEquals(originals[1], hash(root.resolve("retry.txt")))
                NearbyTransferController.retryFile(app, batch, 3)
                withTimeout(20_000) { while (NearbyTransferController.state.value.files.none {
                    it.batchId != batch && it.relativePath == "three.txt" && it.status == TransferFileStatus.COMPLETED
                }) delay(25) }
                assertEquals(sources.map { it.name }.toSet(), root.listFiles().orEmpty().map { it.name }.toSet())
                sources.forEachIndexed { index, file -> assertArrayEquals(originals[index], hash(file)); assertArrayEquals(originals[index], hash(root.resolve(file.name))) }
            }
        }
    }

    @Test fun androidCopyCancellationAndStorageFailureRemoveOnlyTheirOwnIncompleteTargets() = withFixture { _, root, sourceRoot ->
        val stage = File(root, ".owned-stage.partial").apply { writePattern(this, 4_509_722L) }
        val original = hash(stage)
        val unaffected = File(sourceRoot, "original.txt").apply { writeText("untouched") }
        listOf(false, true).forEach { cancel ->
            val target = File(root, if (cancel) "cancelled.mp4" else "failed.mp4")
            var interrupted = false
            try {
                deniedRename().commit(stage, target, stage.length(), checkpoint = {
                    if (target.length() > 0) {
                        assertNotNull(Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS).fileKey())
                        interrupted = true
                        if (cancel) throw CancellationException()
                        throw FileSystemException(null, null, "No space left on device")
                    }
                }, onFallback = {})
                fail("Incomplete file reported as committed")
            } catch (failure: Exception) {
                assertTrue(interrupted)
                assertTrue(if (cancel) failure is CancellationException else failure is FileSystemException)
                assertFalse(target.exists())
                assertArrayEquals(original, hash(stage))
                assertEquals("untouched", unaffected.readText())
            }
        }
    }

    @Test fun defaultCommitAndKeepBothStillWorkOnSharedStorage() = withFixture { _, root, sourceRoot ->
        val source = File(sourceRoot, "normal.txt").apply { writePattern(this, 508_228) }
        val original = File(root, source.name).apply { writeText("keep this") }
        LanHttpServer(root, InetAddress.getByName("127.0.0.1"), requestedCode = "12345678").use { server ->
            val port = server.start().port
            val response = request(port, "POST /upload?dir=&name=${source.name}", login(port), source)
            assertTrue(response, response.startsWith("HTTP/1.1 201"))
            assertEquals("keep this", original.readText())
            assertArrayEquals(hash(source), hash(root.resolve("normal (1).txt")))
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".partial") })
        }
    }

    private fun deniedRename() = ReceivedFileCommitter { source, target ->
        throw FileSystemException(source.path, target.path, "Operation not permitted")
    }

    private fun login(port: Int): String {
        val response = request(port, "POST /login", bytes = "code=12345678".toByteArray())
        check(response.startsWith("HTTP/1.1 200"))
        return response.lineSequence().first { it.startsWith("Set-Cookie:") }.substringAfter(": ").substringBefore(';')
    }

    private fun request(port: Int, route: String, cookie: String? = null, file: File? = null, bytes: ByteArray = byteArrayOf()): String =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 45_000
            val output = socket.getOutputStream()
            output.write(("$route HTTP/1.1\r\nHost: localhost\r\n" +
                (cookie?.let { "Cookie: $it\r\n" } ?: "") +
                "Content-Length: ${file?.length() ?: bytes.size.toLong()}\r\n\r\n").toByteArray())
            if (file != null) file.inputStream().use { it.copyTo(output, 1024 * 1024) } else output.write(bytes)
            output.flush()
            ReleaseHttpResponseReader.read(socket.getInputStream(), 64 * 1024)
        }

    private fun writePattern(file: File, size: Long) {
        val block = ByteArray(1024 * 1024) { (it * 31).toByte() }
        file.outputStream().use { output ->
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                output.write(block, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(1024 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest()
    }

    private inline fun withFixture(block: (AFFileManagerApplication, File, File) -> Unit) {
        check(Build.MODEL.contains("sdk", ignoreCase = true)) { "Owned emulator fixture only" }
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val id = UUID.randomUUID().toString()
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AFCommit-$id")
        val sourceRoot = File(app.cacheDir, "commit-source-$id")
        check(root.mkdir() && sourceRoot.mkdir())
        try { block(app, root, sourceRoot) } finally {
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            check(root.name == "AFCommit-$id" && root.parentFile.canonicalFile ==
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
            check(sourceRoot.parentFile.canonicalFile == app.cacheDir.canonicalFile)
            check(root.deleteRecursively())
            check(sourceRoot.deleteRecursively())
        }
    }
}
