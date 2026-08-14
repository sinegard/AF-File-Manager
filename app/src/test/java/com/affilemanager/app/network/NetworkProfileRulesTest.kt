package com.affilemanager.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileRulesTest {
    @Test
    fun normalizesHarmlessOuterWhitespaceAndIpv6Brackets() {
        val normalized = NetworkProfileRules.normalize(
            profile().copy(
                name = "  Office FTP  ",
                host = " [2001:db8::1] ",
                username = " user ",
                basePath = " /files ",
            ),
        )

        assertEquals("Office FTP", normalized.name)
        assertEquals("2001:db8::1", normalized.host)
        assertEquals("user", normalized.username)
        assertEquals("/files", normalized.basePath)
    }

    @Test
    fun removesVisibleAndInvisibleSpacingFromSingleServerAddress() {
        val spacedAddress = " \u200e203 . 0 . 113 . 190\u00a0\u200f "

        assertEquals("203.0.113.190", NetworkProfileRules.sanitizeHostInput(spacedAddress))
        assertEquals(null, NetworkProfileRules.hostError(spacedAddress))
        assertEquals(
            "203.0.113.190",
            NetworkProfileRules.normalize(profile().copy(host = spacedAddress)).host,
        )
    }

    @Test
    fun rejectsCredentialBlockPastedIntoServerField() {
        val marker = "TOP_SECRET_MARKER"
        val credentialBlock = "192.0.2.10\naccount\n$marker"
        val normalized = NetworkProfileRules.normalize(profile().copy(host = credentialBlock))
        val error = assertThrows(IllegalArgumentException::class.java) {
            NetworkProfileRules.validate(normalized)
        }

        assertEquals(credentialBlock, NetworkProfileRules.sanitizeHostInput(credentialBlock))
        assertEquals(credentialBlock, normalized.host)
        assertEquals("Serverio lauke palikite tik vieną IP adreso arba domeno eilutę", error.message)
        assertTrue(marker !in error.message.orEmpty())
        assertEquals(
            "Serverio lauke palikite tik vieną IP adreso arba domeno eilutę",
            NetworkProfileRules.hostError("192.0.2.10\u2028account"),
        )
    }

    @Test
    fun rejectsMultilineDisplayNameAndPortEmbeddedInHost() {
        assertEquals(
            "Jungties pavadinimas turi būti vienoje eilutėje",
            NetworkProfileRules.nameError("Office\naccount"),
        )
        assertEquals(
            "Prievadą įrašykite atskirame lauke",
            NetworkProfileRules.hostError("example.test:21"),
        )
    }

    private fun profile() = NetworkProfile(
        id = "profile",
        name = "Office FTP",
        protocol = NetworkProtocol.FTP,
        host = "192.0.2.10",
        port = 21,
        username = "account",
        basePath = "/",
    )
}
