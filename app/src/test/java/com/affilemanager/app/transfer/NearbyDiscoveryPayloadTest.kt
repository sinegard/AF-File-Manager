package com.affilemanager.app.transfer

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyDiscoveryPayloadTest {
    @Test
    fun advertisedPrivateReceiverRoundTripsWithoutPersistingAnything() {
        val pairing = NearbyPairing.create("192.168.1.20", 8088, "12345678", "My phone")
        val attributes = NearbyDiscoveryPayload.attributes(pairing, "My phone", "Samsung M13")
        val result = NearbyDiscoveryPayload.decode("AF My phone", 8088, "192.168.1.20", attributes)

        assertEquals(pairing, result?.pairing)
        assertEquals("My phone", result?.receiverName)
        assertEquals("Samsung M13", result?.deviceName)
        assertTrue(attributes.values.sumOf(ByteArray::size) < 512)
    }

    @Test
    fun publicOrMalformedAdvertisementsAreIgnored() {
        val valid = NearbyDiscoveryPayload.attributes(
            NearbyPairing.create("10.0.0.2", 8080, "12345678", "Phone"),
            "Phone",
            "Android",
        ).toMutableMap()
        valid["host"] = "203.0.113.7".toByteArray(StandardCharsets.US_ASCII)
        assertNull(NearbyDiscoveryPayload.decode("public", 8080, "203.0.113.7", valid))

        valid["code"] = "short".toByteArray(StandardCharsets.UTF_8)
        valid["host"] = "10.0.0.2".toByteArray(StandardCharsets.US_ASCII)
        assertNull(NearbyDiscoveryPayload.decode("bad-code", 8080, null, valid))
    }
}
