package com.affilemanager.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SftpUploadPublicationAndroidTest {
    @Test fun rejectedPublicationPreservesTheOldFileAndAllowsAnOrdinaryRetry() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an owned SFTP publish-failure fixture", arguments.getString("afSftpAudit") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "sftp-publication-${UUID.randomUUID()}").apply { check(mkdir()) }
        val profile = NetworkProfile("owned-publish", "Owned publish", NetworkProtocol.SFTP, "127.0.0.1",
            requireNotNull(arguments.getString("afSftpAuditPort")).toInt(), "audit", "/", allowFirstUseTrust = true)
        val client = SftpRemoteClient.connect(profile, requireNotNull(arguments.getString("afSftpAuditPassword")).toCharArray(), charArrayOf())
        try {
            val source = File(root, "new.txt").apply { writeText("edited replacement") }
            assertTrue("The configured publication denial was not observed", runCatching {
                client.upload(source, "/publish-failure.bin")
            }.isFailure)
            assertTrue("A failed replacement removed the previous remote file", client.list("/").any { it.name == "publish-failure.bin" })
            val old = File(root, "old.txt")
            client.download("/publish-failure.bin", old)
            assertEquals("previous remote version", old.readText())
            assertEquals("edited replacement", source.readText())
            assertFalse(client.list("/").any { it.name.startsWith(".af-") })
            client.upload(source, "/publish-failure.bin")
            val retried = File(root, "retried.txt")
            client.download("/publish-failure.bin", retried)
            assertEquals("edited replacement", retried.readText())
            assertFalse(client.list("/").any { it.name.startsWith(".af-") })
        } finally {
            try { client.close() } finally { check(root.deleteRecursively()) }
        }
    }
}
