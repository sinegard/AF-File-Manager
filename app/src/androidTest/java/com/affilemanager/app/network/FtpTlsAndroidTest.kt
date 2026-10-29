package com.affilemanager.app.network

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.net.ssl.SSLException

/** Actual Android provider and production client, with the same in-memory endpoint as normal-R8 UI. */
class FtpTlsAndroidTest {
    @Test fun productionFtpsRejectsAnUntrustedEndpointBeforeLogin() = runBlocking {
        FtpTlsFixture.Material().use { material ->
            FtpTlsFixture.Endpoint(material.context).use { fixture ->
                val password = UUID.randomUUID().toString().toCharArray()
                var client: FtpRemoteClient? = null
                val failure = try {
                    runCatching { FtpRemoteClient.connect(profile(fixture.port, NetworkProtocol.FTPS), password).also { client = it } }.exceptionOrNull()
                } finally { client?.close() }
                assertNotNull("The actual Android client accepted a non-system certificate", failure)
                assertTrue("Rejection must be TLS-related", generateSequence(failure) { it.cause }.any { it is SSLException })
                assertFalse("Credentials were sent to an untrusted endpoint", fixture.loginReceived.get())
                assertTrue(password.all { it == '\u0000' })
            }
        }
    }

    @Test fun theSameEndpointWorksWithExplicitCertificateTrust() {
        FtpTlsFixture.Material().use { material ->
            FtpTlsFixture.Endpoint(material.context).use { fixture ->
                val client = FTPSClient(false).apply {
                    setEndpointCheckingEnabled(true)
                    setTrustManager(TrustManagerUtils.getDefaultTrustManager(material.trustStore))
                    connectTimeout = 5000
                    defaultTimeout = 5000
                }
                try {
                    client.connect("localhost", fixture.port)
                    assertTrue(client.login("fixture-user", UUID.randomUUID().toString()))
                    client.execPBSZ(0); client.execPROT("P")
                    assertTrue(fixture.loginReceived.get())
                } catch (error: Throwable) {
                    fixture.failure.get()?.let(error::addSuppressed)
                    throw error
                } finally { if (client.isConnected) client.disconnect() }
            }
        }
    }

    @Test fun explicitlySelectedPlaintextFtpStillConnects() = runBlocking {
        FtpTlsFixture.Endpoint(null).use { fixture ->
            val password = UUID.randomUUID().toString().toCharArray()
            val client = FtpRemoteClient.connect(profile(fixture.port, NetworkProtocol.FTP), password)
            try { assertTrue(fixture.loginReceived.get()); assertTrue(password.all { it == '\u0000' }) }
            finally { client.close() }
        }
    }

    private fun profile(port: Int, protocol: NetworkProtocol) = NetworkProfile(
        id = "android-tls-fixture", name = "Android TLS fixture", protocol = protocol,
        host = "localhost", port = port, username = "fixture-user", basePath = "/",
    )
}
