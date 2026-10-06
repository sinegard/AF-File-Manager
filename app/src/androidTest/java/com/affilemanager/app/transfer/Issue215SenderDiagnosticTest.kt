package com.affilemanager.app.transfer

import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual sender Service regressions. Emulator TCP is not physical Wi-Fi/OEM acceptance. */
class Issue215SenderDiagnosticTest {
    private enum class Mode { OK, FAST_FAILED, STILL_BUSY, COMMITTED, HTTP500, HTTP500_COMMITTED, STATUS_UNAVAILABLE, AUTH_REJECTED, CLEAR_DURING_CANCEL, STORAGE_ERROR, STORAGE_STATUS }

    @Test fun fastFailedReceiverIsRetriedAndPairingSurvives() = exercise(Mode.FAST_FAILED) { fixture, peer, state ->
        assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
        assertEquals(2, fixture.uploads.get())
        assertEquals(0, fixture.cancellations.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun receiverStillBusyAfterFiveSecondsRecoversWithoutPrematureCancellation() = exercise(Mode.STILL_BUSY) { fixture, peer, state ->
        assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
        assertEquals(2, fixture.uploads.get())
        assertTrue(fixture.statusChecks.get() in 5..10)
        assertEquals(0, fixture.cancellations.get())
        assertNotNull("Network failure must not be confused with losing pairing", NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun lostReplyAfterCommittedFileDoesNotUploadADuplicate() = exercise(Mode.COMMITTED) { fixture, peer, state ->
        assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
        assertEquals(1, fixture.uploads.get())
        assertEquals(1, fixture.statusChecks.get())
        assertEquals(0, fixture.cancellations.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun transientHttp500IsRetriedOnlyAfterAuthoritativeFailedStatus() = exercise(Mode.HTTP500) { fixture, peer, state ->
        assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
        assertEquals(2, fixture.uploads.get())
        assertEquals(1, fixture.statusChecks.get())
        assertEquals(0, fixture.cancellations.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun http500WithAuthoritativeCompletedStatusDoesNotUploadADuplicate() = exercise(Mode.HTTP500_COMMITTED) { fixture, peer, state ->
        assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
        assertEquals(1, fixture.uploads.get())
        assertEquals(1, fixture.statusChecks.get())
        assertEquals(0, fixture.cancellations.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun unavailableStatusEndsWithinItsFiniteBudgetWithoutReplayingOrLosingPairing() = exercise(Mode.STATUS_UNAVAILABLE) { fixture, peer, state ->
        assertEquals(NearbyTransferStatus.ERROR, state.status)
        assertEquals(1, fixture.uploads.get())
        assertTrue(fixture.statusChecks.get() in 9..12)
        assertEquals(1, fixture.cancellations.get())
        assertTrue("Recovery exceeded its missing-status budget: ${fixture.cancelAfterLossMillis}",
            fixture.cancelAfterLossMillis in 15_000..20_000)
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun knownStorageFailureIsVisibleWithoutRetryingOrLosingPairing() = exercise(Mode.STORAGE_ERROR) { fixture, peer, state ->
        assertEquals(NearbyTransferStatus.ERROR, state.status)
        assertTrue(state.message.orEmpty().contains("AF-XFER-SPACE"))
        assertEquals(1, fixture.uploads.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun lostReplyWithPermanentStorageStatusAlsoDoesNotRetry() = exercise(Mode.STORAGE_STATUS) { fixture, peer, state ->
        assertEquals(NearbyTransferStatus.ERROR, state.status)
        assertTrue(state.message.orEmpty().contains("AF-XFER-SPACE"))
        assertEquals(1, fixture.uploads.get())
        assertNotNull(NearbyTransferController.connection.cookieFor(peer))
    }

    @Test fun onlyAnAuthenticationRejectionActuallyClearsThePairing() = exercise(Mode.AUTH_REJECTED) { fixture, peer, state ->
        assertEquals(NearbyTransferStatus.ERROR, state.status)
        assertEquals(1, fixture.statusChecks.get())
        assertNull(NearbyTransferController.connection.cookieFor(peer))
        assertNull(NearbyTransferController.connection.pairing())
    }

    @Test fun thirtyMiBTransfersAsAStreamAfterHomeAndScreenOff() = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 30)
        Fixture(Mode.OK, hash(source), holdUpload = true).use { fixture ->
            try {
                val peer = fixture.peer()
                send(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                assertTrue(fixture.uploadStarted.await(15, TimeUnit.SECONDS))
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                automation.executeShellCommand("input keyevent 223").close()
                fixture.releaseUpload.countDown()
                val state = finish()
                assertEquals(state.message, NearbyTransferStatus.COMPLETED, state.status)
                assertEquals(30L * 1_048_576, fixture.receivedBytes)
                assertEquals(1, fixture.uploads.get())
                assertEquals(hash(source), fixture.receivedHash)
                assertNotNull(NearbyTransferController.connection.cookieFor(peer))
                Log.i("Issue215Diagnostic", "background_screen_off status=${state.status} bytes=${fixture.receivedBytes} javaHeap=${javaHeap()} nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}")
                assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 224").close()
                cleanup(app, root)
            }
        }
    }

    @Test fun localAndDocumentUriTransfersPreserveThirtyMiBOriginalsAndAtomicOutput(): Unit = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 30)
        val before = hash(source)
        val destination = File(root, "received").apply { check(mkdir()) }
        LanHttpServer(destination, localAddress(), requestedCode = "12345678").use { server ->
            try {
                val session = server.start()
                val peer = NearbyPairing.create(session.address, session.port, session.code)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", source)
                send(app, peer, app.graph.nearbySources.prepareContentUris(listOf(uri), copyToPrivateStage = false).getOrThrow())
                assertEquals(NearbyTransferStatus.COMPLETED, finish().status)
                assertEquals(before, hash(File(destination, source.name)))
                send(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                assertEquals(NearbyTransferStatus.COMPLETED, finish().status)
                assertEquals(2, destination.listFiles().orEmpty().size)
                destination.listFiles().orEmpty().forEach { assertEquals(before, hash(it)) }
                assertEquals(before, hash(source))
                assertFalse(destination.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                Log.i("Issue215Diagnostic", "local_uri_30MiB twice_completed javaHeap=${javaHeap()} nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}")
            } finally { cleanup(app, root) }
        }
    }

    @Test fun realReceiverCommitSurvivesAnErrorReplyWithoutDuplicatingTheFile(): Unit = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 5)
        val before = hash(source)
        val destination = File(root, "received").apply { check(mkdir()) }
        val commits = AtomicInteger()
        LanHttpServer(destination, localAddress(), requestedCode = "12345678", onMutation = {
            commits.incrementAndGet()
            throw java.io.IOException("injected callback failure must not undo the committed file")
        }).use { server ->
            try {
                val session = server.start()
                val peer = NearbyPairing.create(session.address, session.port, session.code)
                send(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                assertEquals(NearbyTransferStatus.COMPLETED, finish().status)
                assertEquals(1, commits.get())
                assertEquals(1, destination.listFiles().orEmpty().size)
                assertEquals(before, hash(File(destination, source.name)))
                assertEquals(before, hash(source))
                assertNotNull(NearbyTransferController.connection.cookieFor(peer))
                assertFalse(destination.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            } finally { cleanup(app, root) }
        }
    }

    @Test fun changedDocumentSizeFailsBeforeAnyUploadAndDoesNotRemoveTheOriginal(): Unit = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 5)
        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", source)
        val prepared = app.graph.nearbySources.prepareContentUris(listOf(uri), copyToPrivateStage = false).getOrThrow()
        val before = hash(source)
        Fixture(Mode.OK, before).use { fixture ->
            try {
                send(app, fixture.peer(), prepared.copy(fileSizes = listOf(source.length() + 1)))
                val state = finish()
                assertEquals(NearbyTransferStatus.ERROR, state.status)
                assertEquals(0, fixture.uploads.get())
                assertEquals(before, hash(source))
                Log.i("Issue215Diagnostic", "changed_document status=${state.status} uploads=0")
            } finally { cleanup(app, root) }
        }
    }

    @Test fun externallyReceivedStreamAcrossForcedIdleIsMeasuredWithoutAssumingWifiHardware(): Unit = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("issue215HostPort")?.toIntOrNull()
        org.junit.Assume.assumeTrue("Run separately with the owned loopback host fixture", port != null)
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 30)
        val before = hash(source)
        val power = app.getSystemService(android.os.PowerManager::class.java)
        fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) } }
        try {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            val peer = NearbyPairing.create("10.0.2.2", requireNotNull(port), "12345678")
            send(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
            withTimeout(15_000) { while (NearbyTransferController.state.value.sentBytes < 1_048_576) delay(25) }
            shell("input keyevent 223")
            shell("dumpsys battery unplug")
            val forced = shell("dumpsys deviceidle force-idle")
            withTimeout(3_000) { while (!power.isDeviceIdleMode) delay(50) }
            assertTrue("Force idle was not entered: $forced", power.isDeviceIdleMode)
            delay(4_000)
            val duringIdle = NearbyTransferController.state.value
            Log.i("Issue215Diagnostic", "external_during_forced_idle status=${duringIdle.status} bytes=${duringIdle.sentBytes} isIdle=${power.isDeviceIdleMode} javaHeap=${javaHeap()}")
            shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224")
            val state = finish()
            assertEquals(before, hash(source))
            Log.i("Issue215Diagnostic", "external_after_idle status=${state.status} bytes=${state.sentBytes} paired=${NearbyTransferController.connection.cookieFor(peer) != null} javaHeap=${javaHeap()} nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}")
        } finally {
            shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224")
            cleanup(app, root)
        }
    }

    @Test fun cancellationThenClearingVisibleHistoryWhileWorkerIsStillUnwinding(): Unit = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 5)
        val before = hash(source)
        Fixture(Mode.CLEAR_DURING_CANCEL, before, holdUpload = true).use { fixture ->
            try {
                send(app, fixture.peer(), app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                assertTrue(fixture.uploadStarted.await(15, TimeUnit.SECONDS))
                NearbyTransferController.cancel(app)
                assertTrue(fixture.statusGateEntered.await(5, TimeUnit.SECONDS))
                assertEquals(NearbyTransferStatus.CANCELLED, NearbyTransferController.state.value.status)
                assertEquals(before, hash(source))
                NearbyTransferController.clearFinished()
                assertEquals(NearbyTransferStatus.IDLE, NearbyTransferController.state.value.status)
                Log.i("Issue215Diagnostic", "cancel_clear_history_worker_still_alive original_hash_unchanged")
                fixture.releaseStatus.countDown()
                delay(3_000)
                assertEquals(NearbyTransferStatus.IDLE, NearbyTransferController.state.value.status)
                Fixture(Mode.OK, before).use { next ->
                    send(app, next.peer(), app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                    assertEquals(NearbyTransferStatus.COMPLETED, finish().status)
                    assertEquals(1, next.uploads.get())
                    assertEquals(before, next.receivedHash)
                    assertEquals(before, hash(source))
                    assertTrue(next.errors.toString(), next.errors.isEmpty())
                }
            } finally { fixture.releaseStatus.countDown(); cleanup(app, root) }
        }
    }

    private fun exercise(mode: Mode, verify: (Fixture, NearbyPairing, NearbyTransferState) -> Unit): Unit = runBlocking {
        val app = app()
        val root = fixtureRoot(app)
        val source = payload(root, 5)
        val before = hash(source)
        Fixture(mode, before).use { fixture ->
            try {
                val peer = fixture.peer()
                send(app, peer, app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow())
                val state = finish()
                verify(fixture, peer, state)
                assertEquals(before, hash(source))
                assertEquals(before, fixture.receivedHash)
                assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
                Log.i("Issue215Diagnostic", "$mode status=${state.status} uploads=${fixture.uploads.get()} checks=${fixture.statusChecks.get()} cancels=${fixture.cancellations.get()} cancelAfterLossMs=${fixture.cancelAfterLossMillis} javaHeap=${javaHeap()}")
            } finally { cleanup(app, root) }
        }
    }

    private fun app(): AFFileManagerApplication {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        return ApplicationProvider.getApplicationContext()
    }
    private fun fixtureRoot(app: AFFileManagerApplication) = File(app.cacheDir, "issue215-${UUID.randomUUID()}").apply { check(mkdir()) }
    private fun payload(root: File, mebibytes: Int) = File(root, "video.mp4").apply {
        val block = ByteArray(65_536) { (it % 251).toByte() }
        outputStream().use { output -> repeat(mebibytes * 16) { output.write(block) } }
    }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(65_536)
        while (true) { val read = input.read(block); if (read < 0) break; digest.update(block, 0, read) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun javaHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun send(app: AFFileManagerApplication, peer: NearbyPairing, prepared: PreparedNearbyTransfer) {
        NearbyTransferController.start(app, peer, prepared)
    }
    private suspend fun finish(): NearbyTransferState = withTimeout(30_000) {
        while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
        NearbyTransferController.state.value
    }
    private suspend fun cleanup(app: AFFileManagerApplication, root: File) {
        NearbyTransferController.cancel(app)
        delay(900)
        NearbyTransferController.connection.clear()
        NearbyTransferController.clearFinished()
        check(root.canonicalPath.startsWith(File(app.cacheDir, "issue215-").canonicalPath))
        root.deleteRecursively()
    }

    private class Fixture(val mode: Mode, private val expectedHash: String, holdUpload: Boolean = false) : Closeable {
        val uploads = AtomicInteger()
        val statusChecks = AtomicInteger()
        val cancellations = AtomicInteger()
        val errors = CopyOnWriteArrayList<Throwable>()
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(if (holdUpload) 1 else 0)
        val statusGateEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        @Volatile var receivedHash = ""
        @Volatile var receivedBytes = 0L
        @Volatile var lostAtNanos = 0L
        @Volatile var cancelAfterLossMillis = -1L
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 8, localAddress()).apply { soTimeout = 1_000 }
        private val pool = Executors.newFixedThreadPool(4)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        init {
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            pool.submit {
                while (running.get()) {
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                        catch (_: java.net.SocketException) { break }
                    sockets.add(socket)
                    pool.submit {
                        try { socket.use(::handle) } catch (failure: Throwable) { if (running.get()) errors += failure }
                        finally { sockets.remove(socket) }
                    }
                }
            }
        }
        fun peer() = NearbyPairing.create(server.inetAddress.hostAddress!!, server.localPort, "12345678")
        private fun handle(socket: Socket) {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().buffered()
            val route = line(input).split(' ')[1].substringBefore('?')
            var length = 0L
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toLong()
            }
            if (route == "/upload") {
                uploadStarted.countDown()
                check(releaseUpload.await(12, TimeUnit.SECONDS))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(count > 0)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            if (route == "/upload") {
                val count = uploads.incrementAndGet()
                receivedBytes = length
                receivedHash = digest.digest().joinToString("") { "%02x".format(it) }
                check(receivedHash == expectedHash) { "fixture hash mismatch" }
                if (count == 1 && mode != Mode.OK) {
                    lostAtNanos = System.nanoTime()
                    if (mode in setOf(Mode.HTTP500, Mode.HTTP500_COMMITTED)) reply(socket, 500, "temporary storage failure")
                    if (mode == Mode.STORAGE_ERROR) reply(socket, 500, "Server error (AF-XFER-SPACE)", failure = TransferFailure.SPACE)
                    return // EOF without reply creates a transport IOException, not an HTTP failure.
                }
            }
            if (route == "/nearby/cancel") {
                cancellations.incrementAndGet()
                cancelAfterLossMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lostAtNanos)
            }
            if (route == "/nearby/file-status") {
                statusChecks.incrementAndGet()
                when (mode) {
                    Mode.CLEAR_DURING_CANCEL -> {
                        statusGateEntered.countDown()
                        check(releaseStatus.await(8, TimeUnit.SECONDS))
                        reply(socket, 200, "FAILED")
                    }
                    Mode.STATUS_UNAVAILABLE -> reply(socket, 503, "unreachable status")
                    Mode.AUTH_REJECTED -> reply(socket, 401, "rejected session")
                    Mode.COMMITTED, Mode.HTTP500_COMMITTED -> reply(socket, 200, "COMPLETED")
                    Mode.STILL_BUSY -> reply(socket, 200, if (System.nanoTime() - lostAtNanos < 5_000_000_000) "TRANSFERRING" else "FAILED")
                    Mode.STORAGE_ERROR, Mode.STORAGE_STATUS -> reply(socket, 200, "FAILED", failure = TransferFailure.SPACE)
                    else -> reply(socket, 200, "FAILED")
                }
            } else reply(socket, 200, "", login = route == "/login")
        }
        private fun reply(socket: Socket, status: Int, body: String, login: Boolean = false, failure: TransferFailure? = null) {
            val bytes = body.toByteArray()
            val extra = (if (login) "Set-Cookie: af_session=diagnostic-fixture; HttpOnly\r\nX-AF-Queue-Version: 1\r\n" else "") +
                (failure?.let { "X-AF-Error-Code: ${it.code}\r\n" } ?: "")
            socket.getOutputStream().write(("HTTP/1.1 $status Result\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
        private fun line(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 16_384) {
                val value = input.read()
                check(value >= 0)
                if (value == 10) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(value)
            }
            error("oversized fixture header")
        }
        override fun close() {
            releaseUpload.countDown()
            releaseStatus.countDown()
            running.set(false)
            server.close()
            sockets.forEach { it.close() }
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    companion object {
        private fun localAddress() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
    }
}
