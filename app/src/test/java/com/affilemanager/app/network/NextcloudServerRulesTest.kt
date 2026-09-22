package com.affilemanager.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudServerRulesTest {
    @Test
    fun defaultsToHttpsAndKeepsInstallationSubdirectory() {
        val server = NextcloudServerRules.parse("cloud.example.com/nextcloud/")

        assertEquals("https://cloud.example.com/nextcloud", server.url)
        assertEquals("/nextcloud", server.basePath)
        assertEquals(443, server.port)
        assertTrue(server.useTls)
        assertEquals(
            "https://cloud.example.com/nextcloud/index.php/login/v2",
            NextcloudServerRules.endpoint(server, "index.php/login/v2").toString(),
        )
    }

    @Test
    fun keepsCustomPortAndBuildsWebDavProfile() {
        val credentials = NextcloudLoginCredentials(
            server = "https://cloud.example.com:8443/nc",
            loginName = "person@example.com",
            userId = "person",
            appPassword = "temporary-secret".toCharArray(),
        )

        val profile = credentials.profile()

        assertEquals(NetworkProvider.NEXTCLOUD, profile.provider)
        assertEquals(NetworkProtocol.WEBDAV, profile.protocol)
        assertEquals("cloud.example.com", profile.host)
        assertEquals(8443, profile.port)
        assertEquals("/nc/remote.php/dav/files/person", profile.basePath)
        credentials.close()
        assertTrue(credentials.appPassword.all { it == '\u0000' })
    }

    @Test
    fun rejectsCredentialsOrRedirectsToAnotherOrigin() {
        val server = NextcloudServerRules.parse("https://cloud.example.com")

        assertThrows(IllegalArgumentException::class.java) {
            NextcloudServerRules.requireSameOrigin(server, "https://evil.example/login", "Login")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextcloudServerRules.validUserId("../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NextcloudServerRules.parse("ftp://cloud.example.com")
        }
    }
}
