package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class NearbyConnectionTest {
    @Test fun rejectedSessionIsForgottenWithoutClearingAnotherPeerOrRetryingFiles() {
        val connection = NearbyConnection { 0L }
        val peer = NearbyPairing.create("192.168.1.2", 8080, "12345678")
        connection.remember(peer, "af_session=private")
        assertFalse(connection.clearIfRejected(peer, 500))
        assertTrue(connection.clearIfRejected(peer.copy(port = 8081), 401))
        assertNotNull(connection.cookieFor(peer))
        assertTrue(connection.clearIfRejected(peer, 403))
        assertNull(connection.pairing())
        assertNull(connection.cookieFor(peer))
    }
    @Test fun repeatedBatchesReuseOnlyTheExactPeerAndExpireWithoutRenewingOnReads() {
        var time = 100L
        val connection = NearbyConnection { time }
        val peer = NearbyPairing.create("192.168.1.2", 8080, "12345678")
        connection.remember(peer, "af_session=private", expires = 200L)
        assertEquals("af_session=private", connection.cookieFor(peer))
        assertNull(connection.cookieFor(peer.copy(code = "87654321")))
        time = 199
        assertEquals(peer, connection.pairing())
        time = 200
        assertNull(connection.pairing())
        assertNull(connection.cookieFor(peer))
    }
    @Test fun changingPeerAndDisconnectForgetOldCredentialsAndLifetimeIsBounded() {
        var time = 0L
        val connection = NearbyConnection { time }
        val peer = NearbyPairing.create("192.168.1.2", 8080, "12345678")
        connection.remember(peer, "af_session=private", expires = Long.MAX_VALUE)
        connection.remember(peer.copy(port = 8081))
        assertNull(connection.cookieFor(peer.copy(port = 8081)))
        connection.clear()
        assertNull(connection.pairing())
        connection.remember(peer, "af_session=private", expires = Long.MAX_VALUE)
        time = 15 * 60_000L
        assertNull(connection.cookieFor(peer))
    }
}
