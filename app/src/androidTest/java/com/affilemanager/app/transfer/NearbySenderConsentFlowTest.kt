package com.affilemanager.app.transfer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class NearbySenderConsentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    @Test fun actualSenderAndReceiverWaitForTheOwnerThenTransferAnExactFile() = exercise(allow = true)
    @Test fun rejectingTheActualSenderEndsItsWaitWithoutUploadingOrStagingAnything() = exercise(allow = false)

    private fun exercise(allow: Boolean) = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk", true))
        val fixture = File(app.cacheDir, "consent-flow-${System.nanoTime()}").apply { check(mkdir()) }
        val source = File(fixture, "source.txt").apply { writeText("actual AF sender to actual AF receiver, explicit owner consent") }
        val destination = File(fixture, "receive").apply { check(mkdir()) }
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }.hostAddress!!
        var prepared: PreparedNearbyTransfer? = null
        try {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            LanTransferController.start(app, destination.path, options = LanTransferOptions(password = "owned-flow-code"),
                bindAddress = address, phoneReceiver = true)
            withTimeout(15_000) { while (LanTransferController.state.value.status !in setOf(LanTransferStatus.RUNNING, LanTransferStatus.ERROR)) delay(25) }
            val receiver = LanTransferController.state.value
            assertEquals(receiver.message, LanTransferStatus.RUNNING, receiver.status)
            val uri = java.net.URI(receiver.url!!)
            prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
            NearbyTransferController.start(app, NearbyPairing.create(uri.host, uri.port, receiver.code!!), prepared!!)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("nearby_sender_allow").fetchSemanticsNodes().isNotEmpty() }
            delay(1200)
            assertTrue(destination.list()!!.isEmpty())
            assertTrue(NearbyTransferController.state.value.message.orEmpty().contains("patvirtinimo"))
            compose.onNodeWithTag(if (allow) "nearby_sender_allow" else "nearby_sender_deny").performClick()
            withTimeout(25_000) { while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25) }
            val finished = NearbyTransferController.state.value
            assertEquals(finished.message, if (allow) NearbyTransferStatus.COMPLETED else NearbyTransferStatus.ERROR, finished.status)
            if (allow) assertEquals(source.readText(), File(destination, source.name).readText()) else assertTrue(destination.list()!!.isEmpty())
            assertEquals("actual AF sender to actual AF receiver, explicit owner consent", source.readText())
        } finally {
            LanTransferController.stop(app)
            withTimeout(10_000) { while (LanTransferController.state.value.status == LanTransferStatus.RUNNING) delay(25) }
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            prepared?.let { app.graph.nearbySources.discard(it) }
            check(fixture.deleteRecursively())
        }
    }
}
