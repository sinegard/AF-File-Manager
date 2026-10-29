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

class SmbEndpointRoundTripAndroidTest {
    @Test fun actualSmbAtomicPublicationFailureAndOrdinaryOperationsKeepTheirData() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the separately owned SMB2 endpoint", arguments.getString("afSmbAudit") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "smb-roundtrip-${UUID.randomUUID()}").apply { check(mkdir()) }
        val profile = NetworkProfile("owned-smb", "Owned SMB", NetworkProtocol.SMB, "127.0.0.1",
            requireNotNull(arguments.getString("afSmbAuditPort")).toInt(), "audit", "/", smbShare = "AF_AUDIT")
        val password = requireNotNull(arguments.getString("afSmbAuditPassword")).toCharArray()
        val client = SmbRemoteClient.connect(profile, password)
        try {
            assertTrue(password.all { it == '\u0000' })
            val source = File(root, "source.bin").apply { writeText("new remote version") }
            assertTrue(runCatching { client.upload(source, "/publish-failure.bin") }.isFailure)
            assertTrue("Rejected SMB rename removed the previous file", client.list("/").any { it.name == "publish-failure.bin" })
            val previous = File(root, "previous.bin")
            client.download("/publish-failure.bin", previous)
            assertEquals("previous remote version", previous.readText())
            client.upload(source, "/publish-failure.bin")
            val result = File(root, "result.bin")
            val other = File(root, "unselected.txt").apply { writeText("unselected") }
            val fixed = File(root, ".result.bin.partial")
            Files.createSymbolicLink(fixed.toPath(), other.toPath())
            assertTrue(runCatching { client.download("/publish-failure.bin", result, maxBytes = 1) }.isFailure)
            client.download("/publish-failure.bin", result)
            assertEquals("new remote version", result.readText())
            assertEquals("new remote version", source.readText())
            assertEquals("unselected", other.readText())
            assertTrue(Files.isSymbolicLink(fixed.toPath()))
            assertFalse(root.list().orEmpty().any { it.startsWith(".af-download-") })
            client.createDirectory("/work")
            client.createFile("/work/first.txt")
            client.rename("/work/first.txt", "/work/renamed.txt")
            assertEquals(listOf("renamed.txt"), client.list("/work").map { it.name })
            client.delete("/work", recursive = true)
            assertFalse(client.list("/").any { it.name.startsWith(".af-") || it.name == "work" })
            Files.delete(fixed.toPath())
        } finally {
            try { client.close() } finally { check(root.deleteRecursively()) }
        }
    }
}
