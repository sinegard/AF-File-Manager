package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class NearbyBidirectionalTransferTest {
    @Test fun twoReceiversExchangeMoreBatchesWithoutReusingTheOneTimeLogin() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "nearby-duplex-${UUID.randomUUID()}").apply { check(mkdir()) }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val returnedPeer = AtomicReference<NearbyPairing?>()
        val first = File(root, "a").apply { mkdir() }
        val second = File(root, "b").apply { mkdir() }
        val input = File(root, "source.txt").apply { writeText("original content") }
        fun send(pairing: NearbyPairing, from: File, returnPairing: NearbyPairing? = null) {
            NearbyTransferController.start(app, pairing, PreparedNearbyTransfer(
                paths = listOf(from.path), relativePaths = listOf(from.name), directories = emptyList(), cleanupRootPath = null), returnPairing)
            val deadline = System.nanoTime() + 20_000_000_000L
            while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING) && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            val result = NearbyTransferController.state.value
            assertEquals(result.message, NearbyTransferStatus.COMPLETED, result.status)
        }
        try {
            NearbyTransferController.connection.clear()
            LanHttpServer(first, address, requestedCode = "12345678").use { a ->
                LanHttpServer(second, address, requestedCode = "87654321", onNearbyPeer = { peer, _ -> returnedPeer.set(peer) }).use { b ->
                    val sa = a.start(); val sb = b.start()
                    val pa = NearbyPairing.create(sa.address, sa.port, sa.code, "A")
                    val pb = NearbyPairing.create(sb.address, sb.port, sb.code, "B")
                    send(pb, input, pa)
                    assertEquals(pa, returnedPeer.get())
                    assertEquals(input.readText(), File(second, input.name).readText())
                    val extra = File(root, "extra.txt").apply { writeText("second batch") }
                    send(pb, extra, pa)
                    assertEquals("second batch", File(second, extra.name).readText())
                    // The other phone has its own process-local connection; use its received pairing.
                    NearbyTransferController.connection.clear()
                    send(requireNotNull(returnedPeer.get()), File(second, extra.name))
                    assertEquals("second batch", File(first, extra.name).readText())
                    assertEquals("original content", input.readText())
                }
            }
        } finally {
            NearbyTransferController.cancel(app)
            NearbyTransferController.connection.clear()
            NearbyTransferController.clearFinished()
            root.deleteRecursively()
        }
    }
}
