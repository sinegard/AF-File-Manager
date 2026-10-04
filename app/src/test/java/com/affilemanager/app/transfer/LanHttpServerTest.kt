package com.affilemanager.app.transfer

import com.affilemanager.app.ui.localization.AppLanguageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class LanHttpServerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test fun leavingOneGroupMemberDoesNotStopTheOrganizerOrDisconnectOtherMembers() {
        val root = temporary.newFolder("group-leave")
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<List<NearbyGroupMember>>()
        var stopped = false
        val address = privateAddress()
        LanHttpServer(root, address, requestedCode = "12345678", groupMode = true,
            onStopped = { stopped = true }, onGroupMembers = { _, members -> snapshots += members }).use { server ->
            val port = server.start().port
            val firstCookie = login(port, address)
            val secondCookie = login(port, address)
            fun member(index: Int) = NearbyPairing.create(address.hostAddress, 25_000 + index, "8765432$index", "Phone $index")
            fun post(cookie: String, path: String, body: String = ""): String = request(port,
                "POST $path HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body", address)
            assertTrue(post(firstCookie, "/nearby/group/join", member(1).encoded()).startsWith("HTTP/1.1 200"))
            assertTrue(post(secondCookie, "/nearby/group/join", member(2).encoded()).startsWith("HTTP/1.1 200"))
            assertEquals(3, snapshots.last().size)
            assertTrue(post(firstCookie, "/nearby/disconnect").startsWith("HTTP/1.1 200"))
            assertFalse(stopped)
            assertEquals(listOf("AF File Manager", "Phone 2"), snapshots.last().map { it.pairing.receiverName })
            assertTrue(post(secondCookie, "/nearby/group/heartbeat", member(2).encoded()).startsWith("HTTP/1.1 200"))
            assertTrue(request(port, "GET /nearby/group/members HTTP/1.1\r\nHost: localhost\r\nCookie: $secondCookie\r\n\r\n", address)
                .startsWith("HTTP/1.1 200"))
            assertTrue(post(secondCookie, "/upload?name=after-leave.txt", "still connected").startsWith("HTTP/1.1 201"))
            assertEquals("still connected", root.resolve("after-leave.txt").readText())
        }
    }

    @Test fun organizerRemovalHasAnExplicitReasonAndCannotRemoveAnotherIdentity() {
        val root = temporary.newFolder("group-removal")
        val address = privateAddress()
        LanHttpServer(root, address, requestedCode = "12345678", groupMode = true).use { server ->
            val port = server.start().port
            val cookie = login(port, address)
            val member = NearbyPairing.create(address.hostAddress, 25_001, "87654321", "Member")
            fun post(path: String) = request(port,
                "POST $path HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${member.encoded().toByteArray().size}\r\n\r\n${member.encoded()}", address)
            assertTrue(post("/nearby/group/join").startsWith("HTTP/1.1 200"))
            assertTrue(server.removeGroupMember(member))
            val rejected = post("/nearby/group/heartbeat")
            assertTrue(rejected.startsWith("HTTP/1.1 403"))
            assertTrue(rejected.contains("X-AF-Group-State: removed"))
            assertTrue(post("/nearby/group/join").startsWith("HTTP/1.1 403"))
            assertTrue(request(port, "POST /upload?name=blocked.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 3\r\n\r\nbad", address)
                .startsWith("HTTP/1.1 403"))
            assertFalse(root.resolve("blocked.txt").exists())
            assertTrue(request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: ${login(port, address)}\r\n\r\n", address).startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun nearbyMessagesRequireAuthenticationAndStayOutOfSharedStorage() {
        val root = temporary.newFolder("messages")
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678",
            onNearbyMessage = { received += it }).use { server ->
            val port = server.start().port
            val body = "Hello from the other phone"
            val anonymous = request(port,
                "POST /nearby/message HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            assertTrue(anonymous.startsWith("HTTP/1.1 401"))
            assertTrue(received.isEmpty())

            val cookie = login(port)
            val accepted = request(port,
                "POST /nearby/message HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            assertTrue(accepted.startsWith("HTTP/1.1 200"))
            assertEquals(listOf(body), received)
            assertTrue(root.listFiles().orEmpty().isEmpty())

            val tooLarge = "x".repeat(LanHttpServer.MAX_NEARBY_MESSAGE_BYTES + 1)
            assertTrue(request(port,
                "POST /nearby/message HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${tooLarge.length}\r\n\r\n$tooLarge")
                .startsWith("HTTP/1.1 400"))
            assertEquals(1, received.size)
        }
    }

    @Test fun midUploadAppendKeepsBothBatchesAndCancelClosesOnlyTheActiveUpload() {
        val root = temporary.newFolder("queued-upload")
        val updates = java.util.concurrent.CopyOnWriteArrayList<LanUploadProgress>()
        val started = java.util.concurrent.CountDownLatch(1)
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", onUploadProgress = {
            updates += it
            if (it.files.any { row -> row.status == TransferFileStatus.TRANSFERRING }) started.countDown()
        }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val first = java.util.UUID.randomUUID().toString()
            val second = java.util.UUID.randomUUID().toString()
            fun announce(id: String, name: String, size: Long) {
                val body = NearbyTransferManifest.encode(listOf(TransferFileProgress(name, size))).toString(Charsets.UTF_8)
                assertTrue(request(port, "POST /nearby/manifest HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $id\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").startsWith("HTTP/1.1 200"))
            }
            announce(first, "one.txt", 100)
            Socket(InetAddress.getLoopbackAddress(), port).use { upload ->
                upload.soTimeout = 4_000
                upload.getOutputStream().write("POST /upload?name=one.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $first\r\nContent-Length: 100\r\n\r\npartial".toByteArray())
                assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
                announce(second, "two.txt", 2)
                assertEquals(listOf("one.txt", "two.txt"), updates.last().files.map { it.name })
                assertEquals(listOf(TransferFileStatus.TRANSFERRING, TransferFileStatus.WAITING), updates.last().files.map { it.status })
                val early = request(port, "POST /upload?name=two.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $second\r\nContent-Length: 2\r\n\r\nok")
                assertTrue(early.startsWith("HTTP/1.1 400"))
                assertTrue(request(port, "POST /nearby/cancel HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $first\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 200"))
                assertEquals(-1, upload.getInputStream().read())
                assertEquals(TransferFileStatus.CANCELLED, updates.last().files.first().status)
            }
            val next = request(port, "POST /upload?name=two.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $second\r\nContent-Length: 2\r\n\r\nok")
            assertTrue(next.startsWith("HTTP/1.1 201"))
            assertEquals("ok", root.resolve("two.txt").readText())
            assertFalse(root.resolve("one.txt").exists())
            assertTrue(updates.last().files.first().localPath == null)
            assertTrue(request(port, "POST /nearby/disconnect HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 200"))
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        }
    }

    @Test fun cancellingOneActiveFileKeepsTheRemainingManifestFilesTransferable() {
        val root = temporary.newFolder("single-file-cancel")
        val updates = java.util.concurrent.CopyOnWriteArrayList<LanUploadProgress>()
        val started = java.util.concurrent.CountDownLatch(1)
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", onUploadProgress = {
            updates += it
            if (it.files.firstOrNull()?.status == TransferFileStatus.TRANSFERRING) started.countDown()
        }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            val batch = java.util.UUID.randomUUID().toString()
            val manifest = NearbyTransferManifest.encode(
                listOf(TransferFileProgress("one.txt", 100), TransferFileProgress("two.txt", 2)),
            ).toString(Charsets.UTF_8)
            assertTrue(
                request(
                    port,
                    "POST /nearby/manifest HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n" +
                        "X-AF-Batch-ID: $batch\r\nContent-Length: ${manifest.toByteArray().size}\r\n\r\n$manifest",
                ).startsWith("HTTP/1.1 200"),
            )

            Socket(InetAddress.getLoopbackAddress(), port).use { upload ->
                upload.soTimeout = 4_000
                upload.getOutputStream().write(
                    (
                        "POST /upload?name=one.txt&fileCount=2&fileIndex=1 HTTP/1.1\r\n" +
                            "Host: localhost\r\nCookie: $cookie\r\nX-AF-Batch-ID: $batch\r\n" +
                            "Content-Length: 100\r\n\r\npartial"
                        ).toByteArray(),
                )
                assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
                val cancelled = request(
                    port,
                    "POST /nearby/cancel-file?fileIndex=1 HTTP/1.1\r\nHost: localhost\r\n" +
                        "Cookie: $cookie\r\nX-AF-Batch-ID: $batch\r\nContent-Length: 0\r\n\r\n",
                )
                assertTrue(cancelled.startsWith("HTTP/1.1 200"))
                assertEquals(-1, upload.getInputStream().read())
            }

            val firstStatus = request(
                port,
                "GET /nearby/file-status?fileIndex=1 HTTP/1.1\r\nHost: localhost\r\n" +
                    "Cookie: $cookie\r\nX-AF-Batch-ID: $batch\r\n\r\n",
            )
            assertTrue(firstStatus.startsWith("HTTP/1.1 200"))
            assertTrue(firstStatus.endsWith("CANCELLED"))
            val second = request(
                port,
                "POST /upload?name=two.txt&fileCount=2&fileIndex=2 HTTP/1.1\r\nHost: localhost\r\n" +
                    "Cookie: $cookie\r\nX-AF-Batch-ID: $batch\r\nContent-Length: 2\r\n\r\nok",
            )
            assertTrue(second.startsWith("HTTP/1.1 201"))
            assertFalse(root.resolve("one.txt").exists())
            assertEquals("ok", root.resolve("two.txt").readText())
            assertEquals(
                listOf(TransferFileStatus.CANCELLED, TransferFileStatus.COMPLETED),
                updates.last().files.map { it.status },
            )
        }
    }

    @Test fun nearbyManifestRequiresAuthenticationAndPublishesOnlyCommittedKeepBothPaths() {
        val root = temporary.newFolder("manifest").apply { resolve("photo.txt").writeText("original") }
        val updates = java.util.concurrent.CopyOnWriteArrayList<LanUploadProgress>()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", onUploadProgress = { updates += it }).use { server ->
            val port = server.start().port
            val body = NearbyTransferManifest.encode(listOf(TransferFileProgress("photo.txt", 5), TransferFileProgress("empty.txt", 0)))
                .toString(StandardCharsets.UTF_8)
            fun manifest(cookie: String) = request(port,
                "POST /nearby/manifest HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            assertTrue(manifest("").startsWith("HTTP/1.1 401"))
            assertTrue(updates.isEmpty())
            val cookie = login(port)
            assertTrue(manifest(cookie).startsWith("HTTP/1.1 200"))
            assertEquals(2, updates.last().files.size)
            assertTrue(updates.last().files.all { it.localPath == null && it.status == TransferFileStatus.WAITING })
            val response = request(port,
                "POST /upload?name=photo.txt&fileCount=2&fileIndex=1&batchBytes=5 HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 5\r\n\r\nhello")
            assertTrue(response.startsWith("HTTP/1.1 201"))
            val file = updates.last().files[0]
            assertEquals(TransferFileStatus.COMPLETED, file.status)
            assertEquals("hello", java.io.File(requireNotNull(file.localPath)).readText())
            assertEquals("original", root.resolve("photo.txt").readText())
            // A completed row stays previewable while other queued files are still pending.
            assertTrue(updates.flatMap { it.files }.filterNot { it.status == TransferFileStatus.COMPLETED }.all { it.localPath == null })
            val mismatched = request(port,
                "POST /upload?name=wrong.txt&fileCount=2&fileIndex=2 HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 0\r\n\r\n")
            assertTrue(mismatched.startsWith("HTTP/1.1 400"))
            assertFalse(root.resolve("wrong.txt").exists())
        }
    }

    @Test fun incompleteUploadPublishesFailureButNeverAReadablePartialFile() {
        val root = temporary.newFolder("interrupted-progress")
        val updates = java.util.concurrent.CopyOnWriteArrayList<LanUploadProgress>()
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", onUploadProgress = { updates += it }).use { server ->
            val port = server.start().port
            val cookie = login(port)
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write(("POST /upload?name=partial.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 100\r\n\r\nshort").toByteArray())
                socket.shutdownOutput()
                socket.getInputStream().readBytes()
            }
            assertEquals(TransferFileStatus.FAILED, updates.last().files.single().status)
            assertTrue(updates.flatMap { it.files }.all { it.localPath == null })
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun readOnlyReceiverRejectsNearbyMetadata() {
        val root = temporary.newFolder("readonly-manifest")
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", readOnly = true).use { server ->
            val port = server.start().port
            val cookie = login(port)
            assertTrue(request(port, "POST /nearby/manifest HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 403"))
        }
    }

    @Test
    fun loginUsesOneTimeCodeAndAuthenticatedCookie() {
        val root = temporary.newFolder("shared").apply { resolve("visible.txt").writeText("hello") }
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()

            val anonymous = request(session.port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(anonymous.startsWith("HTTP/1.1 401"))
            assertFalse(anonymous.contains("visible.txt"))
            assertTrue(anonymous.contains("Enter the 8-digit one-time code"))
            assertFalse(anonymous.contains("Įveskite telefone"))

            val body = "code=12345678"
            val login = request(
                session.port,
                "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body",
            )
            assertTrue(login.startsWith("HTTP/1.1 200"))
            val cookie = login.lineSequence().first { it.startsWith("Set-Cookie:") }
                .substringAfter("Set-Cookie:").substringBefore(';').trim()

            val listing = request(session.port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n")
            assertTrue(listing.startsWith("HTTP/1.1 200"))
            assertTrue(listing.contains("visible.txt"))
            assertFalse(listing.contains(root.canonicalPath))
        }
    }

    @Test
    fun webInterfaceUsesTheSelectedLithuanianLanguage() {
        val root = temporary.newFolder("localized")
        LanHttpServer(
            root,
            InetAddress.getLoopbackAddress(),
            requestedCode = "12345678",
            language = AppLanguageManager.LITHUANIAN,
        ).use { server ->
            val response = request(server.start().port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(response.contains("Įveskite telefone rodomą 8 skaitmenų vienkartinį kodą."))
            assertTrue(response.contains("Prisijungti"))
        }
    }

    @Test
    fun traversalOutsideRootIsRejected() {
        val parent = temporary.newFolder("boundary")
        val root = parent.resolve("root").apply { mkdir() }
        parent.resolve("secret.txt").writeText("secret-value")
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            val cookie = login(session.port)

            val response = request(
                session.port,
                "GET /download?path=..%2Fsecret.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertFalse(response.contains("secret-value"))
        }
    }

    @Test
    fun uploadUsesBoundedPartialThenAtomicVisibleName() {
        val root = temporary.newFolder("upload")
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            val cookie = login(session.port)
            val content = "uploaded-content"

            val response = request(
                session.port,
                "POST /upload?dir=&name=report.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${content.toByteArray().size}\r\nContent-Type: application/octet-stream\r\n\r\n$content",
            )

            assertTrue(response.startsWith("HTTP/1.1 201"))
            assertEquals(content, root.resolve("report.txt").readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        }
    }

    @Test
    fun phoneTransferCreatesFolderTreesAndReportsReceiverProgress() {
        val root = temporary.newFolder("phone-tree")
        val progress = mutableListOf<LanUploadProgress>()
        LanHttpServer(
            rootDirectory = root,
            bindAddress = InetAddress.getLoopbackAddress(),
            requestedCode = "12345678",
            onUploadProgress = { update -> synchronized(progress) { progress += update } },
        ).use { server ->
            val session = server.start()
            val cookie = login(session.port)
            val mkdir = request(
                session.port,
                "POST /mkdir?path=album%2Fnested HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 0\r\n\r\n",
            )
            assertTrue(mkdir.startsWith("HTTP/1.1 201"))

            val content = "photo-content"
            val upload = request(
                session.port,
                "POST /upload?dir=album%2Fnested&name=photo.txt&fileIndex=2&fileCount=3&batchBytes=100&batchOffset=20 HTTP/1.1\r\n" +
                    "Host: localhost\r\nCookie: $cookie\r\nContent-Length: ${content.length}\r\nContent-Type: application/octet-stream\r\n\r\n$content",
            )

            assertTrue(upload.startsWith("HTTP/1.1 201"))
            assertEquals(content, root.resolve("album/nested/photo.txt").readText())
            val completed = synchronized(progress) { progress.last() }
            assertTrue(completed.completed)
            assertEquals("photo.txt", completed.currentFile)
            assertEquals(2, completed.currentFileIndex)
            assertEquals(3, completed.totalFiles)
            assertEquals(content.length.toLong(), completed.currentFileBytes)
            assertEquals(20L + content.length, completed.receivedBytes)
            assertEquals(100L, completed.totalBytes)
        }
    }

    @Test
    fun phoneTransferFolderCreationRejectsTraversal() {
        val parent = temporary.newFolder("phone-tree-boundary")
        val root = parent.resolve("root").apply { mkdir() }
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            val cookie = login(session.port)

            val response = request(
                session.port,
                "POST /mkdir?path=..%2Foutside HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: 0\r\n\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertFalse(parent.resolve("outside").exists())
        }
    }

    @Test
    fun readOnlySessionKeepsBrowsingButRejectsUploads() {
        val root = temporary.newFolder("read-only-web").apply { resolve("visible.txt").writeText("visible") }
        LanHttpServer(
            root,
            InetAddress.getLoopbackAddress(),
            requestedCode = "custom-pass",
            readOnly = true,
        ).use { server ->
            val session = server.start()
            val body = "code=custom-pass"
            val login = request(
                session.port,
                "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body",
            )
            val cookie = login.lineSequence().first { it.startsWith("Set-Cookie:") }
                .substringAfter("Set-Cookie:").substringBefore(';').trim()
            val listing = request(session.port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n")
            assertTrue(listing.contains("visible.txt"))
            assertFalse(listing.contains("onclick=\"upload()\""))

            val content = "blocked"
            val upload = request(
                session.port,
                "POST /upload?dir=&name=blocked.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${content.length}\r\n\r\n$content",
            )
            assertTrue(upload.startsWith("HTTP/1.1 403"))
            assertFalse(root.resolve("blocked.txt").exists())
        }
    }

    @Test
    fun resourceAndAuthenticationLimitsAreExplicit() {
        assertEquals(4, LanHttpServer.MAX_CONCURRENT_REQUESTS)
        assertEquals(16, LanHttpServer.MAX_QUEUED_REQUESTS)
        assertEquals(10_000, LanHttpServer.MAX_REQUESTS_PER_SESSION)
        assertEquals(20, LanHttpServer.MAX_AUTH_FAILURES)
        assertEquals(120, LanHttpServer.MAX_SESSION_MINUTES)
    }

    @Test
    fun customPortIsUsedWhenItIsAvailable() {
        val address = InetAddress.getLoopbackAddress()
        val requestedPort = ServerSocket(0, 1, address).use { it.localPort }
        val root = temporary.newFolder("custom-port")

        LanHttpServer(
            rootDirectory = root,
            bindAddress = address,
            requestedPort = requestedPort,
            requestedCode = "12345678",
        ).use { server ->
            assertEquals(requestedPort, server.start().port)
        }
    }

    @Test fun interruptedThirtyMiBUploadKeepsOriginalsAndReceiverUsable() {
        val root = temporary.newFolder("large-upload-interruption")
        root.resolve("large.bin").writeText("previous destination")
        val source = temporary.newFile("source-large.bin")
        val buffer = ByteArray(64 * 1_024) { (it % 251).toByte() }
        source.outputStream().use { output -> repeat(480) { output.write(buffer) } }
        fun checksum(file: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val originalChecksum = checksum(source)
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val port = server.start().port
            val cookie = login(port)
            Socket(InetAddress.getLoopbackAddress(), port).use { upload ->
                upload.soTimeout = 5_000
                val output = upload.getOutputStream()
                output.write(("POST /upload?name=large.bin HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n" +
                    "Content-Length: ${source.length()}\r\n\r\n").toByteArray())
                source.inputStream().use { input -> repeat(240) { check(input.read(buffer) == buffer.size); output.write(buffer) } }
                output.flush()
                upload.shutdownOutput()
                assertTrue(upload.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 400"))
            }
            assertEquals("previous destination", root.resolve("large.bin").readText())
            assertEquals(setOf("large.bin"), root.listFiles()!!.map { it.name }.toSet())
            assertEquals(originalChecksum, checksum(source))
            assertEquals(30L * 1_024 * 1_024, source.length())
            assertTrue(request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n")
                .startsWith("HTTP/1.1 200"))
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".partial") })
        }
    }

    @Test fun lastIdleGroupMemberExpiresWithoutStoppingTheOrganizer() {
        val root = temporary.newFolder("group-idle-expiry")
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val joined = java.util.concurrent.atomic.AtomicBoolean(false)
        val expired = java.util.concurrent.CountDownLatch(1)
        val address = privateAddress()
        LanHttpServer(root, address, requestedCode = "12345678", groupMode = true, nowMillis = now::get,
            onGroupMembers = { _, members -> if (joined.get() && members.size == 1) expired.countDown() }).use { server ->
            val port = server.start().port
            val cookie = login(port, address)
            val peer = NearbyPairing.create(address.hostAddress, 24_001, "87654321", "Member")
            val body = peer.encoded()
            assertTrue(request(port, "POST /nearby/group/join HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body", address).startsWith("HTTP/1.1 200"))
            joined.set(true)
            now.addAndGet(31_000)
            assertTrue(expired.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: ${login(port, address)}\r\n\r\n", address)
                .startsWith("HTTP/1.1 200"))
        }
    }

    private fun privateAddress(): InetAddress = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }.first { it is java.net.Inet4Address && it.isSiteLocalAddress }

    private fun login(port: Int, address: InetAddress = InetAddress.getLoopbackAddress()): String {
        val body = "code=12345678"
        val response = request(
            port,
            "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body", address,
        )
        return response.lineSequence().first { it.startsWith("Set-Cookie:") }
            .substringAfter("Set-Cookie:").substringBefore(';').trim()
    }

    private fun request(port: Int, request: String, address: InetAddress = InetAddress.getLoopbackAddress()): String = Socket(address, port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }
}
