package com.affilemanager.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Explicitly opted-in, owned loopback endpoint. Never uses a saved profile. */
class SftpEndpointRoundTripAndroidTest {
    @Test fun actualSftpRoundTripAuthAndHostTrustPreserveSourcesAndUnselectedStages() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the separately owned loopback SFTP fixture", arguments.getString("afSftpAudit") == "true")
        val secret = requireNotNull(arguments.getString("afSftpAuditPassword"))
        val port = requireNotNull(arguments.getString("afSftpAuditPort")).toInt()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "sftp-roundtrip-${UUID.randomUUID()}").apply { check(mkdir()) }
        val profile = NetworkProfile("owned-sftp", "Owned SFTP", NetworkProtocol.SFTP, "127.0.0.1", port,
            "audit", "/", allowFirstUseTrust = true)
        var client: SftpRemoteClient? = null
        try {
            val wrong = "deliberately-wrong".toCharArray()
            assertTrue("Wrong password was accepted", runCatching { SftpRemoteClient.connect(profile, wrong, charArrayOf()) }.isFailure)
            assertTrue(wrong.all { it == '\u0000' })
            val mismatch = secret.toCharArray()
            assertTrue("Mismatched host key was accepted", runCatching {
                SftpRemoteClient.connect(profile.copy(allowFirstUseTrust = false,
                    expectedHostKeySha256 = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), mismatch, charArrayOf())
            }.isFailure)
            assertTrue(mismatch.all { it == '\u0000' })
            val password = secret.toCharArray()
            client = SftpRemoteClient.connect(profile, password, charArrayOf())
            assertTrue(password.all { it == '\u0000' })
            assertNotNull(client.verifiedHostFingerprint)
            client.createDirectory("/work")
            client.createFile("/work/empty.txt")
            client.rename("/work/empty.txt", "/work/renamed.txt")
            assertEquals(listOf("renamed.txt"), client.list("/work").map { it.name })
            val bytes = ByteArray(512 * 1024 + 31) { (it * 19).toByte() }
            val source = File(root, "source.bin").apply { writeBytes(bytes) }
            client.upload(source, "/work/data.bin")
            assertArrayEquals(bytes, source.readBytes())
            assertTrue("Rename replaced an existing file on the permissive fixture", runCatching {
                client.rename("/work/renamed.txt", "/work/data.bin")
            }.isFailure)
            assertTrue(client.list("/work").any { it.name == "renamed.txt" })
            val result = File(root, "result.bin").apply { writeText("previous result") }
            val other = File(root, "unselected.txt").apply { writeText("unselected data") }
            val fixed = File(root, ".result.bin.partial")
            Files.createSymbolicLink(fixed.toPath(), other.toPath())
            assertTrue(runCatching { client.download("/work/data.bin", result, maxBytes = 1) }.isFailure)
            assertEquals("previous result", result.readText())
            client.download("/work/data.bin", result)
            assertArrayEquals(bytes, result.readBytes())
            assertEquals("unselected data", other.readText())
            assertTrue(Files.isSymbolicLink(fixed.toPath()))
            assertFalse(root.list().orEmpty().any { it.startsWith(".af-download-") })
            client.delete("/work", recursive = true)
            assertTrue(client.list("/").isEmpty())
            Files.delete(fixed.toPath())
            System.out.println("AF_SFTP_ENDPOINT_ROUNDTRIP_OK")
        } finally {
            try { client?.close() } finally { check(root.deleteRecursively()) }
        }
    }
}
