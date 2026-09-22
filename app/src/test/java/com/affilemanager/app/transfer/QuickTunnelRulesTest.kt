package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickTunnelRulesTest {
    @Test
    fun lanAddressIsReducedToAnAppLocalLoopbackOrigin() {
        assertEquals(
            "http://127.0.0.1:38417",
            QuickTunnelRules.loopbackOrigin("http://192.168.1.20:38417"),
        )
    }

    @Test
    fun nonHttpAndAmbiguousOriginsAreRejected() {
        listOf(
            "https://192.168.1.20:443",
            "ftp://192.168.1.20:21",
            "http://user@192.168.1.20:80",
            "http://192.168.1.20:80/?token=secret",
            "http://192.168.1.20",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { QuickTunnelRules.loopbackOrigin(value) }
        }
    }

    @Test
    fun expiryKeepsManualSessionsAtZeroAndBoundsTimedSessions() {
        val now = 1_000L
        assertEquals(0L, QuickTunnelRules.expiryMillis(null, now))
        assertEquals(0L, QuickTunnelRules.expiryMillis(now, now))
        assertEquals(8_000L, QuickTunnelRules.expiryMillis(8_000L, now))
        assertEquals(
            now + QuickTunnelRules.MAX_TUNNEL_DURATION_MILLIS,
            QuickTunnelRules.expiryMillis(Long.MAX_VALUE, now),
        )
    }

    @Test
    fun publicUrlIsExposedOnlyAfterTheEdgeConnectionIsRegistered() {
        val parser = QuickTunnelOutputParser()
        assertNull(
            parser.accept(
                "Your quick Tunnel has been created: HTTPS://Example-Tunnel.trycloudflare.com",
            ),
        )
        assertEquals(
            "https://example-tunnel.trycloudflare.com",
            parser.accept("INF Registered tunnel connection connIndex=0 protocol=http2"),
        )
        assertNull(parser.accept("INF Registered tunnel connection connIndex=1 protocol=http2"))
    }
}
