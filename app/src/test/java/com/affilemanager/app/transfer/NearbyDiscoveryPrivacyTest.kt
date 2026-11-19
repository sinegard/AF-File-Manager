package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class NearbyDiscoveryPrivacyTest {
    @Test fun discoveryNeverPublishesAConnectionCredential() {
        val peer = NearbyPairing.create("192.168.1.20", 8080, "fixture-private-code", "Owned phone")
        val fields = NearbyDiscoveryPayload.attributes(peer, "Owned phone", "Android")
        assertFalse(fields.keys.any { it.lowercase() in setOf("code", "cookie", "token", "password", "pairing") })
        assertFalse(fields.values.any { it.contentEquals(peer.code.toByteArray()) })
    }

    @Test fun legacySecretBearingAdvertisementsAndNewAdvertisementsWithHiddenCredentialsAreRejected() {
        val peer = NearbyPairing.create("192.168.1.20", 8080, "fixture-private-code", "Owned phone")
        val public = NearbyDiscoveryPayload.attributes(peer, "Owned phone", "Android")
        for (key in listOf("code", "CoOkIe", "token", "password", "pairing")) {
            assertNull(NearbyDiscoveryPayload.decode("AF owned", 8080, peer.host, public + (key to peer.code.toByteArray())))
        }
        assertNull(NearbyDiscoveryPayload.decode("AF old", 8080, peer.host, public + ("v" to "1".toByteArray())))
    }
}
