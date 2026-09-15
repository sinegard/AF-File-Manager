package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSessionDurationTest {
    @Test
    fun manualAndTimedChoicesAreNormalizedWithoutAnUnboundedTimedValue() {
        assertEquals(0, LanSessionDuration.normalize(0))
        assertEquals(5, LanSessionDuration.normalize(1))
        assertEquals(5, LanSessionDuration.normalize(9))
        assertEquals(115, LanSessionDuration.normalize(119))
        assertEquals(120, LanSessionDuration.normalize(999))
    }

    @Test
    fun manualExpiryNeverExpiresButTimedExpiryDoes() {
        assertEquals(Long.MAX_VALUE, LanSessionDuration.expiresAt(1_000L, 0))
        val expiry = LanSessionDuration.expiresAt(1_000L, 5)
        assertEquals(301_000L, expiry)
        assertFalse(LanSessionDuration.isExpired(300_999L, expiry))
        assertTrue(LanSessionDuration.isExpired(expiry, expiry))
        assertFalse(LanSessionDuration.isExpired(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
