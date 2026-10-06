package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real TCP receiver regressions: errors retain their category without exposing private data. */
class Issue215ReceiverDiagnosticTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anOpenPartialUploadRemainsRecoverableAfterTheOldFourPollWindow() {
        val root = temporary.newFolder("withheld-body")
        val updates = CopyOnWriteArrayList<LanUploadProgress>()
        val started = CountDownLatch(1)
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", onUploadProgress = {
            updates += it
            if (it.files.any { row -> row.status == TransferFileStatus.TRANSFERRING }) started.countDown()
        }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val id = UUID.randomUUID().toString()
            val manifest = NearbyTransferManifest.encode(listOf(TransferFileProgress("video.mp4", 5L * 1_048_576)))
            assertTrue(post(port, "/nearby/manifest", cookie, id, manifest).startsWith("HTTP/1.1 200"))
            Socket(InetAddress.getLoopbackAddress(), port).use { upload ->
                upload.soTimeout = 5_000
                upload.getOutputStream().write(("POST /upload?name=video.mp4 HTTP/1.1\r\nHost: localhost\r\n" +
                    "Cookie: $cookie\r\nX-AF-Batch-ID: $id\r\nContent-Length: 5242880\r\n\r\n").toByteArray())
                upload.getOutputStream().write(ByteArray(16_384))
                upload.getOutputStream().flush()
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val start = System.nanoTime()
                repeat(4) { check ->
                    val remote = request(port, "GET /nearby/file-status?fileIndex=1 HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $id\r\n\r\n")
                    assertTrue(remote.endsWith("TRANSFERRING"))
                    assertEquals(NearbyUploadRecovery.WAIT,
                        NearbyTransferRetry.decide(IOException("injected link loss"), TransferFileStatus.TRANSFERRING, 1))
                    Thread.sleep(NearbyTransferRetry.statusDelayMillis(check))
                }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue(elapsed >= 2_750)
                assertEquals(TransferFileStatus.TRANSFERRING, updates.last().files.single().status)
                assertFalse(root.resolve("video.mp4").exists())
                upload.shutdownOutput()
                val response = upload.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 408"))
                assertTrue(response.contains("X-AF-Error-Code: AF-XFER-CONNECTION"))
                assertEquals(TransferFileStatus.FAILED, updates.last().files.single().status)
                assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                assertTrue(post(port, "/upload?name=video.mp4", cookie, id, ByteArray(5 * 1_048_576)).startsWith("HTTP/1.1 201"))
                assertEquals(5L * 1_048_576, root.resolve("video.mp4").length())
            }
        }
    }

    @Test fun aCommitConflictIsVisibleAndNeverRetriedOrOverwritesTheOriginal() {
        val root = temporary.newFolder("storage-race")
        val updates = CopyOnWriteArrayList<LanUploadProgress>()
        val events = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678",
            diagnosticsEnabled = { true }, onDiagnostic = { events += it }, onUploadProgress = {
            updates += it
            if (it.files.any { row -> row.status == TransferFileStatus.TRANSFERRING } && !root.resolve("video.mp4").exists()) {
                root.resolve("video.mp4").writeText("concurrent original must remain")
            }
        }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val id = UUID.randomUUID().toString()
            val body = ByteArray(1024)
            assertTrue(post(port, "/nearby/manifest", cookie, id,
                NearbyTransferManifest.encode(listOf(TransferFileProgress("video.mp4", body.size.toLong())))).startsWith("HTTP/1.1 200"))
            val failed = post(port, "/upload?name=video.mp4", cookie, id, body)
            assertTrue(failed.startsWith("HTTP/1.1 500"))
            assertTrue(failed.contains("X-AF-Error-Code: AF-XFER-CONFLICT"))
            assertTrue(failed.contains("X-AF-Error-Phase: commit"))
            assertEquals(TransferFailure.CONFLICT, updates.last().files.single().failure)
            assertEquals(TransferPhase.COMMIT, events.last().phase)
            assertEquals(TransferFailure.CONFLICT, events.last().failure)
            assertEquals(TransferFileStatus.FAILED, updates.last().files.single().status)
            assertEquals("concurrent original must remain", root.resolve("video.mp4").readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            assertEquals(NearbyUploadRecovery.FAIL, NearbyTransferRetry.decide(
                NearbyUploadException(500, TransferFailure.CONFLICT, "Server error"), TransferFileStatus.FAILED, 1))
            assertEquals(NearbyUploadRecovery.RETRY, NearbyTransferRetry.decide(
                IOException("same transport outcome"), TransferFileStatus.FAILED, 1))
        }
    }

    @Test fun failureAfterCommitReturns500WithoutUndoingTheWrittenFile() {
        val root = temporary.newFolder("after-commit")
        val updates = CopyOnWriteArrayList<LanUploadProgress>()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678",
            onUploadProgress = { updates += it }, onMutation = { throw IOException("injected post-commit failure") }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val id = UUID.randomUUID().toString()
            val body = ByteArray(1024) { (it % 251).toByte() }
            assertTrue(post(port, "/nearby/manifest", cookie, id,
                NearbyTransferManifest.encode(listOf(TransferFileProgress("video.mp4", body.size.toLong())))).startsWith("HTTP/1.1 200"))
            assertTrue(post(port, "/upload?name=video.mp4", cookie, id, body).startsWith("HTTP/1.1 500"))
            assertEquals(TransferFileStatus.COMPLETED, updates.last().files.single().status)
            assertArrayEquals(body, root.resolve("video.mp4").readBytes())
            assertEquals(NearbyUploadRecovery.COMPLETE, NearbyTransferRetry.decide(
                NearbyUploadException(500, TransferFailure.CALLBACK, "Server error"), TransferFileStatus.COMPLETED, 1))
        }
    }

    @Test fun preCommitCallbackFailureHasAPrivateCodeAndAuthoritativeStatus() {
        val root = temporary.newFolder("notification-failure")
        val events = CopyOnWriteArrayList<TransferDiagnosticEvent>()
        val reject = java.util.concurrent.atomic.AtomicBoolean(true)
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678",
            diagnosticsEnabled = { true }, onDiagnostic = { events += it }, onUploadProgress = {
                if (reject.get() && it.files.any { file -> file.status == TransferFileStatus.TRANSFERRING })
                    throw IOException("private video.mp4 path=/private/session password=secret")
            }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val id = UUID.randomUUID().toString()
            val body = ByteArray(1024)
            assertTrue(post(port, "/nearby/manifest", cookie, id,
                NearbyTransferManifest.encode(listOf(TransferFileProgress("video.mp4", body.size.toLong())))).startsWith("HTTP/1.1 200"))
            val response = post(port, "/upload?name=video.mp4", cookie, id, body)
            assertTrue(response.startsWith("HTTP/1.1 500"))
            assertTrue(response.contains("X-AF-Error-Code: AF-XFER-CALLBACK"))
            assertTrue(response.contains("X-AF-Error-Phase: notify"))
            assertFalse(response.contains("secret"))
            assertFalse(events.joinToString { it.line() }.contains("video.mp4"))
            val status = request(port, "GET /nearby/file-status?fileIndex=1 HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $id\r\n\r\n")
            assertTrue(status.endsWith("FAILED"))
            assertTrue(status.contains("X-AF-Error-Code: AF-XFER-CALLBACK"))
            assertTrue(root.listFiles().orEmpty().isEmpty())
            reject.set(false)
            assertTrue(post(port, "/upload?name=video.mp4", cookie, id, body).startsWith("HTTP/1.1 201"))
            assertArrayEquals(body, root.resolve("video.mp4").readBytes())
        }
    }

    @Test fun disabledDiagnosticsDoNotRunTheDiagnosticCallbackOnEitherSuccessOrFailure() {
        val root = temporary.newFolder("diagnostics-off")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678",
            onDiagnostic = { calls.incrementAndGet(); error("disabled diagnostics must not run") }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            assertTrue(post(port, "/upload?name=video.mp4", cookie, UUID.randomUUID().toString(), byteArrayOf(1))
                .startsWith("HTTP/1.1 400")) // Unknown batch is rejected, not implicitly authorized.
            assertEquals(0, calls.get())
            val id = UUID.randomUUID().toString()
            assertTrue(post(port, "/nearby/manifest", cookie, id,
                NearbyTransferManifest.encode(listOf(TransferFileProgress("video.mp4", 1)))).startsWith("HTTP/1.1 200"))
            assertTrue(post(port, "/upload?name=video.mp4", cookie, id, byteArrayOf(1)).startsWith("HTTP/1.1 201"))
            assertEquals(0, calls.get())
        }
    }

    private fun login(port: Int): String = request(port,
        "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: 13\r\n\r\ncode=12345678")
        .lineSequence().first { it.startsWith("Set-Cookie:") }.substringAfter(": ").substringBefore(';')

    private fun post(port: Int, path: String, cookie: String, id: String, body: ByteArray): String = request(port,
        "POST $path HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $id\r\nContent-Length: ${body.size}\r\n\r\n", body)

    private fun request(port: Int, headers: String, body: ByteArray = byteArrayOf()): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 8_000
            socket.getOutputStream().write(headers.toByteArray())
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
}
