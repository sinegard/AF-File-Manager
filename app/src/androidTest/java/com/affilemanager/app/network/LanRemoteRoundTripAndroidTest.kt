package com.affilemanager.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.transfer.LanFtpServer
import com.affilemanager.app.transfer.LanWebDavServer
import com.affilemanager.app.transfer.TemporaryLanServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.UUID

/** Real production clients and LAN servers; no user account or external network. */
class LanRemoteRoundTripAndroidTest {
    @Test fun ftpListsCreatesRenamesUploadsAndDownloadsWithoutBorrowingLocalStages() = exercise(NetworkProtocol.FTP)
    @Test fun webDavListsCreatesRenamesUploadsAndDownloadsWithoutBorrowingLocalStages() = exercise(NetworkProtocol.WEBDAV)

    private fun exercise(protocol: NetworkProtocol) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "lan-roundtrip-${UUID.randomUUID()}").apply { check(mkdir()) }
        val shared = File(root, "server").apply { check(mkdir()) }
        val local = File(root, "client").apply { check(mkdir()) }
        val loopback = InetAddress.getByName("127.0.0.1")
        val secret = UUID.randomUUID().toString()
        val server: TemporaryLanServer = when (protocol) {
            NetworkProtocol.FTP -> LanFtpServer(shared, loopback, requestedUsername = "audit", requestedCode = secret)
            NetworkProtocol.WEBDAV -> LanWebDavServer(shared, loopback, requestedUsername = "audit", requestedCode = secret)
            else -> error("Unexpected local fixture protocol")
        }
        var client: RemoteClient? = null
        try {
            val session = server.start()
            val profile = NetworkProfile("owned-audit", "Owned audit", protocol, "127.0.0.1", session.port,
                requireNotNull(session.username), "/", webDavUseTls = false)
            val password = secret.toCharArray()
            client = when (protocol) {
                NetworkProtocol.FTP -> FtpRemoteClient.connect(profile, password)
                NetworkProtocol.WEBDAV -> WebDavRemoteClient.connect(profile, password)
                else -> error("Unexpected protocol")
            }
            assertTrue("Connection must clear its password array", password.all { it == '\u0000' })
            client.createDirectory("/work")
            client.createFile("/work/empty.txt")
            assertTrue(client.list("/work").any { it.name == "empty.txt" && !it.directory && it.sizeBytes == 0L })
            client.rename("/work/empty.txt", "/work/renamed.txt")
            assertEquals(listOf("renamed.txt"), client.list("/work").map { it.name })

            val bytes = ByteArray(256 * 1024 + 17) { (it * 31).toByte() }
            val source = File(local, "source.bin").apply { writeBytes(bytes) }
            client.upload(source, "/work/data.bin")
            assertArrayEquals("Upload must preserve the local source", bytes, source.readBytes())
            assertArrayEquals("The actual server must receive all bytes", bytes, File(shared, "work/data.bin").readBytes())

            assertTrue("Rename must not replace a different existing file", runCatching {
                client.rename("/work/renamed.txt", "/work/data.bin")
            }.isFailure)
            assertArrayEquals("A rename conflict changed unselected existing data", bytes, File(shared, "work/data.bin").readBytes())
            assertTrue(File(shared, "work/renamed.txt").isFile)

            val result = File(local, "result.bin").apply { writeText("previous result") }
            val unselected = File(local, "unselected.txt").apply { writeText("unselected data") }
            val fixedStage = File(local, ".result.bin.partial")
            Files.createSymbolicLink(fixedStage.toPath(), unselected.toPath())
            assertTrue("A limited download must fail", runCatching { client.download("/work/data.bin", result, maxBytes = 1) }.isFailure)
            assertEquals("previous result", result.readText())
            // A failed FTP data command may invalidate its control channel. Open a new
            // ordinary connection rather than interpreting a stale session as staging loss.
            client.close()
            client = when (protocol) {
                NetworkProtocol.FTP -> FtpRemoteClient.connect(profile, secret.toCharArray())
                NetworkProtocol.WEBDAV -> WebDavRemoteClient.connect(profile, secret.toCharArray())
                else -> error("Unexpected protocol")
            }
            client.download("/work/data.bin", result)
            assertArrayEquals(bytes, result.readBytes())
            assertEquals("unselected data", unselected.readText())
            assertTrue("An unrelated linked stage must remain untouched", Files.isSymbolicLink(fixedStage.toPath()))
            assertFalse("Owned staging must be cleaned", local.list().orEmpty().any { it.startsWith(".af-download-") })
            client.delete("/work/renamed.txt")
            client.delete("/work", recursive = true)
            assertTrue(client.list("/").isEmpty())
            Files.delete(fixedStage.toPath())
        } finally {
            try { client?.close() } finally {
                server.close()
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("lan-roundtrip-"))
                check(root.deleteRecursively())
            }
        }
    }
}
