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
        val diagnostics = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        val progress = CopyOnWriteArrayList<LanUploadProgress>()
        LanHttpServer(root, InetAddress.getByName("127.0.0.1"), requestedCode = "12345678",
            receivedFileCommitter = deniedRename(), diagnosticsEnabled = { true },
            onDiagnostic = diagnostics::add, onUploadProgress = progress::add,
        ).use { server ->
            val port = server.start().port
            val cookie = login(port)
            listOf(1_818_418L, 4_509_722L, 30L * 1024 * 1024, 0L).forEachIndexed { index, size ->
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
            runBlocking { withTimeout(3000) { while (diagnostics.count { it.phase == TransferPhase.COMPLETE } < 4) delay(10) } }
            assertEquals(4, diagnostics.count { it.phase == TransferPhase.COMMIT_COPY && it.failure == null })
            assertEquals(4, diagnostics.count { it.phase == TransferPhase.COMPLETE })
            assertTrue(diagnostics.any { it.phase == TransferPhase.COMMIT && it.errno == 1 })
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
