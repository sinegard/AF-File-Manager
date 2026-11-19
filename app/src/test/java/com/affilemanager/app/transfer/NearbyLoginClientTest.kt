package com.affilemanager.app.transfer

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class NearbyLoginClientTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun sharedClientWaitsAndReturnsACookieOnlyAfterExplicitLocalApproval() = exercise("approve")
    @Test fun sharedClientStopsOnRejectionWithoutAnyWritePermission() = exercise("reject")
    @Test fun sharedClientCancellationWithdrawsThePendingRequest() = exercise("cancel")
    @Test fun interruptedGroupWorkerWithdrawsThePendingRequestToo() = exercise("interrupt")
    private fun exercise(action: String) {
        val root = temporary.newFolder(action)
        val address = LanTestNetwork.privateAddress()
        val executor = Executors.newSingleThreadExecutor()
        val cancelled = AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        LanHttpServer(root, address, requestedCode = "owned-client-code", requireSenderApproval = true).use { server ->
            val session = server.start()
            val peer = NearbyPairing.create(session.address, session.port, session.code)
            val future = executor.submit<NearbyLoginClient.Accepted> {
                NearbyLoginClient.login(OkHttpClient(), peer, "Owned client", checkCancelled = {
                    if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("owned cancellation")
                }, onWaiting = { if (action == "interrupt") Thread.currentThread().interrupt(); waiting.countDown() })
            }
            try {
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                if (action != "interrupt") assertFalse(future.isDone)
                assertTrue(root.list()!!.isEmpty())
                if (action != "interrupt") {
                    val request = server.pendingNearbySenders().single()
                    when (action) {
                        "approve" -> server.decideNearbySender(request.id, true)
                        "reject" -> server.decideNearbySender(request.id, false)
                        else -> cancelled.set(true)
                    }
                }
                if (action == "approve") {
                    val accepted = future.get(5, TimeUnit.SECONDS)
                    assertTrue(accepted.cookie.startsWith("af_session="))
                    assertEquals("AcceptedNearbySession", accepted.toString())
                } else assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
                assertTrue(server.pendingNearbySenders().isEmpty())
                assertTrue(root.list()!!.isEmpty())
            } finally { cancelled.set(true); executor.shutdownNow() }
        }
    }
}
