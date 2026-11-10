package com.affilemanager.app.transfer

import android.os.Build
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class NearbySaveFailureIsolationTest {
    @Test fun oneDeniedSaveDoesNotPreventLaterFiles() = exercise(parallelism = 1)

    @Test fun oneDeniedSaveDoesNotCancelIndependentParallelFiles() = exercise(parallelism = 3)

    @Test fun allowedMediaFinishesInAnOrdinarySharedFolderWithSpaces() = exercise(parallelism = 3, shared = true)

    private fun exercise(parallelism: Int, shared: Boolean = false): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(Build.MODEL.contains("sdk", ignoreCase = true) && Os.getuid() >= 10_000)
        val fixtureParent = if (shared) Environment.getExternalStorageDirectory() else app.cacheDir
        val root = File(fixtureParent, "nearby-save-isolation-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val sourceDir = root.resolve("sources").apply { check(mkdir()) }
        val receive = root.resolve(if (shared) "Files Sharing" else "receive").apply { check(mkdir()) }
        val videos = receive.resolve("Videos").apply { check(mkdir()) }
        val keep = videos.resolve("existing.txt").apply { writeText("original receiver file") }
        val oldMode = if (shared) null else Os.stat(videos.path).st_mode and 0x1ff
        val sources = listOf("01-denied.mp4" to 1_818_418, "02-allowed.jpg" to 1_484_770, "03-allowed.txt" to 1_235)
            .mapIndexed { index, (name, length) -> sourceDir.resolve(name).apply {
                outputStream().use { output ->
                    val block = ByteArray(64 * 1024) { (it + index).toByte() }
                    var remaining = length
                    while (remaining > 0) {
                        val count = minOf(remaining, block.size)
                        output.write(block, 0, count)
                        remaining -= count
                    }
                }
            } }
        val originals = sources.map(::digest)
        val diagnostics = ConcurrentLinkedQueue<TransferDiagnosticEvent>()
        val deniedMoves = AtomicInteger()
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        // Revoke only this test directory's owner write bit after the full file
        // was staged and synced. Both the real move and the real copy must fail.
        val committer = ReceivedFileCommitter { staging, destination ->
            if (!shared && destination.parentFile == videos) {
                deniedMoves.incrementAndGet()
                Os.chmod(videos.path, OsConstants.S_IRUSR or OsConstants.S_IXUSR)
            }
            Files.move(staging.toPath(), destination.toPath())
        }
        var prepared: PreparedNearbyTransfer? = null
        try {
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            LanHttpServer(receive, address, concurrentUploads = parallelism,
                receiveDestinations = mapOf(EntryKind.VIDEO to videos), receivedFileCommitter = committer,
                onDiagnostic = { diagnostics.add(it) }, diagnosticsEnabled = { true }).use { server ->
                val session = server.start()
                val peer = NearbyPairing.create(session.address, session.port, session.code)
                val selection = app.graph.nearbySources.prepareLocalPaths(sources.map(File::getAbsolutePath)).getOrThrow()
                prepared = selection
                assertEquals(sources.map(File::getName), selection.relativePaths)
                NearbyTransferController.start(app, peer, selection)
                val result = withTimeout(45_000) {
                    while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(50)
                    NearbyTransferController.state.value
                }
                if (shared) {
                    assertEquals(result.message, NearbyTransferStatus.COMPLETED, result.status)
                    assertTrue(result.files.all { it.status == TransferFileStatus.COMPLETED })
                    assertEquals(3, result.completedFiles)
                    assertArrayEquals(originals.first(), digest(videos.resolve(sources.first().name)))
                    assertFalse(root.walkTopDown().any { it.name.endsWith(".partial") })
                } else {
                    assertEquals(1, deniedMoves.get()) // No blind retry of a permanent permission failure.
                    assertTrue("Real final copy creation was not denied", diagnostics.any {
                        it.role == TransferRole.RECEIVE && it.phase == TransferPhase.COMMIT_COPY_OPEN && it.failure == TransferFailure.PERMISSION
                    })
                    assertEquals(NearbyTransferStatus.ERROR, result.status)
                    assertEquals(listOf(TransferFileStatus.FAILED, TransferFileStatus.COMPLETED, TransferFileStatus.COMPLETED),
                        result.files.map { it.status })
                    assertEquals(TransferFailure.PERMISSION, result.files.first().failure)
                    assertEquals(2, result.completedFiles)
                    assertFalse(videos.resolve(sources.first().name).exists())
                }
                assertNotNull(NearbyTransferController.connection.cookieFor(peer))
                assertEquals("original receiver file", keep.readText())
                sources.forEachIndexed { index, source ->
                    assertArrayEquals(originals[index], digest(source))
                    if (index > 0) assertArrayEquals(originals[index], digest(receive.resolve(source.name)))
                }
            }
        } finally {
            if (oldMode != null) Os.chmod(videos.path, oldMode)
            NearbyTransferController.cancel(app)
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            prepared?.let { app.graph.nearbySources.discard(it) }
            check(root.canonicalFile.parentFile == fixtureParent.canonicalFile && root.name.startsWith("nearby-save-isolation-"))
            check(root.deleteRecursively())
        }
    }

    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                update(block, 0, count)
            }
        }
        digest()
    }
}
