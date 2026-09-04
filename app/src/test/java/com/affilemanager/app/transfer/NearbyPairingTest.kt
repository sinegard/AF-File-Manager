package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPairingTest {
    @Test
    fun roundTripKeepsPrivateDestinationAndSecret() {
        val original = NearbyPairing.create("192.168.43.1", 49152, "12345678", "Test phone")
        assertEquals(original, NearbyPairing.parse(original.encoded()))
    }

    @Test
    fun rejectsPublicOrLookalikeDestinations() {
        assertThrows(IllegalArgumentException::class.java) {
            NearbyPairing.create("203.0.113.10", 49152, "12345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyPairing.parse("https://192.168.1.2:49152/?code=12345678")
        }
    }

    @Test
    fun privateAddressRulesAreNarrowAndNumeric() {
        assertTrue(NearbyPairing.isPrivateIpv4("10.0.0.1"))
        assertTrue(NearbyPairing.isPrivateIpv4("172.31.255.254"))
        assertFalse(NearbyPairing.isPrivateIpv4("172.32.0.1"))
        assertFalse(NearbyPairing.isPrivateIpv4("192.168.001.1"))
        assertFalse(NearbyPairing.isPrivateIpv4("example.com"))
    }
}
