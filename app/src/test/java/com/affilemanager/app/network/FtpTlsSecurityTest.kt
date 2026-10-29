package com.affilemanager.app.network

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Real loopback handshakes. No public server, stored account, or global trust-store changes. */
class FtpTlsSecurityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun productionFtpsRejectsASelfSignedEndpointBeforeSendingLoginData() = runBlocking {
        val material = certificateMaterial()
        Fixture(material.context).use { fixture ->
            val secret = UUID.randomUUID().toString().toCharArray()
            var connected: FtpRemoteClient? = null
            val failure = try {
                runCatching { FtpRemoteClient.connect(profile(fixture.port, NetworkProtocol.FTPS), secret).also { connected = it } }.exceptionOrNull()
            } finally { connected?.close() }
            assertTrue("Production FTPS accepted a certificate outside the system trust store", failure != null)
            assertTrue("Certificate rejection must remain a TLS error", generateSequence(failure) { it.cause }.any { it is SSLException })
            assertFalse("A login command was sent to an untrusted endpoint", fixture.loginReceived.get())
            assertTrue("Transient password was not cleared", secret.all { it == '\u0000' })
        }
    }

    @Test fun fixtureCompletesFtpsWhenItsCertificateIsExplicitlyTrusted() {
        val material = certificateMaterial()
        Fixture(material.context).use { fixture ->
            val client = FTPSClient(false).apply {
                setEndpointCheckingEnabled(true)
                setTrustManager(TrustManagerUtils.getDefaultTrustManager(material.store))
                connectTimeout = 5_000
                defaultTimeout = 5_000
            }
            try {
                client.connect("localhost", fixture.port)
                assertTrue(client.login("fixture-user", UUID.randomUUID().toString()))
                client.execPBSZ(0); client.execPROT("P")
                assertTrue(fixture.loginReceived.get())
            } finally { if (client.isConnected) client.disconnect() }
        }
    }

    @Test fun plaintextFtpStillUsesItsExplicitlySelectedLegacyMode() = runBlocking {
        Fixture(null).use { fixture ->
            val secret = UUID.randomUUID().toString().toCharArray()
            val client = FtpRemoteClient.connect(profile(fixture.port, NetworkProtocol.FTP), secret)
            try {
                assertTrue(fixture.loginReceived.get())
                assertTrue(secret.all { it == '\u0000' })
            } finally { client.close() }
        }
    }

    private fun profile(port: Int, protocol: NetworkProtocol) = NetworkProfile(
        id = "loopback-only", name = "TLS regression fixture", protocol = protocol,
        host = "localhost", port = port, username = "fixture-user", basePath = "/",
    )

    private data class Material(val context: SSLContext, val store: KeyStore)

    private fun certificateMaterial(): Material {
        val storeFile = File(temporary.newFolder(), "loopback.p12")
        val secret = UUID.randomUUID().toString().toCharArray()
        try {
            val tool = File(requireNotNull(System.getProperty("java.home")), "bin/keytool" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "")
            check(tool.isFile) { "The configured test JDK must provide keytool" }
            val process = ProcessBuilder(
                tool.path, "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", storeFile.path, "-storepass:env", "AF_TLS_FIXTURE_PASS", "-noprompt",
            ).redirectErrorStream(true).apply { environment()["AF_TLS_FIXTURE_PASS"] = secret.concatToString() }.start()
            try {
                check(process.waitFor(20, TimeUnit.SECONDS)) { "Loopback certificate generation timed out" }
                check(process.exitValue() == 0) { "Loopback certificate generation failed" }
            } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close() }
            val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, secret) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, secret) }
            return Material(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }, store)
        } finally {
            secret.fill('\u0000')
            check(storeFile.delete() || !storeFile.exists()) { "Temporary private test key must not remain on disk" }
        }
    }

    private class Fixture(private val tls: SSLContext?) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        private val connection = AtomicReference<Socket?>()
        val loginReceived = AtomicBoolean(false)
        val port: Int get() = server.localPort
        private val worker = thread(name = "af-ftps-loopback", isDaemon = true) {
            try {
                val plain = server.accept().apply { soTimeout = 5_000; connection.set(this) }
                var active = plain
                var input = active.getInputStream().bufferedReader(Charsets.US_ASCII)
                var output = active.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                fun reply(value: String) { output.write("$value\r\n"); output.flush() }
                reply("220 AF loopback fixture")
                repeat(40) {
                    val command = input.readLine()?.substringBefore(' ')?.uppercase() ?: return@thread
                    when (command) {
                        "AUTH" -> {
                            check(tls != null)
                            reply("234 Begin TLS")
                            active = (tls.socketFactory.createSocket(plain, "localhost", port, true) as SSLSocket).apply {
                                useClientMode = false; soTimeout = 5_000; connection.set(this); startHandshake()
                            }
                            input = active.getInputStream().bufferedReader(Charsets.US_ASCII)
                            output = active.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                        }
                        "USER" -> { loginReceived.set(true); reply("331 Password required") }
                        "PASS" -> reply("230 Login accepted")
                        "FEAT" -> reply("211 No extensions")
                        "SYST" -> reply("215 UNIX Type: L8")
                        "PBSZ", "PROT", "TYPE", "NOOP" -> reply("200 Accepted")
                        "QUIT" -> { reply("221 Goodbye"); return@thread }
                        else -> reply("502 Not implemented")
                    }
                }
            } catch (_: java.io.IOException) {
                // A rejected certificate closes this synthetic connection during handshake.
            } finally { connection.getAndSet(null)?.close() }
        }
        override fun close() {
            connection.getAndSet(null)?.close(); server.close(); worker.join(6_000)
            check(!worker.isAlive) { "Loopback fixture failed to stop" }
        }
    }
}
