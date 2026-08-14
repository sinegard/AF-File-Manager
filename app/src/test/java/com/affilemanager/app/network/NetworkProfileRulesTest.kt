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
    fun rejectsCredentialBlockPastedIntoServerField() {
        val marker = "TOP_SECRET_MARKER"
        val error = assertThrows(IllegalArgumentException::class.java) {
            NetworkProfileRules.validate(profile().copy(host = "192.0.2.10\naccount\n$marker"))
        }

        assertEquals("Serverio lauke palikite tik vieną IP adreso arba domeno eilutę", error.message)
        assertTrue(marker !in error.message.orEmpty())
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
