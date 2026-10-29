package com.affilemanager.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.transfer.LanFtpServer
import com.affilemanager.app.transfer.LanHttpServer
import com.affilemanager.app.transfer.LanWebDavServer
import com.affilemanager.app.transfer.TemporaryLanServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.util.UUID

/** Selected aliases must never turn into mutations of an unselected referent. */
class LanServerAliasSafetyAndroidTest {
    @Test fun theSharedResolverRejectsBrokenLeafParentAndParentDotDotAliases() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "alias-resolver-${UUID.randomUUID()}").apply { check(mkdir()) }
        val directory = File(root, "original").apply { check(mkdir()) }
        val original = File(directory, "file.txt").apply { writeText("unselected original") }
        val leaf = File(root, "leaf")
        val parent = File(root, "parent")
        val broken = File(root, "broken")
        try {
            Files.createSymbolicLink(leaf.toPath(), original.toPath())
            Files.createSymbolicLink(parent.toPath(), directory.toPath())
            Files.createSymbolicLink(broken.toPath(), File(root, "does-not-exist").toPath())
            for (candidate in listOf(leaf, broken, File(parent, "file.txt"), File(parent, "new.txt"), File(parent, "../new.txt"))) {
                assertTrue("Resolver erased an alias: $candidate", runCatching {
                    com.affilemanager.app.core.FileSystemRules.containedNonSymbolicPath(root, candidate)
                }.isFailure)
            }
            assertEquals(original.canonicalFile, com.affilemanager.app.core.FileSystemRules.containedNonSymbolicPath(root, original))
            assertEquals("unselected original", original.readText())
            assertFalse(File(root, "new.txt").exists())
        } finally {
            for (link in listOf(leaf, parent, broken)) Files.deleteIfExists(link.toPath())
            check(root.deleteRecursively())
        }
    }

    @Test fun ftpDeleteRejectsALinkWithoutRemovingItsUnselectedOriginal() = mutation(NetworkProtocol.FTP, false)
    @Test fun ftpUploadRejectsALinkWithoutReplacingItsUnselectedOriginal() = mutation(NetworkProtocol.FTP, true)
    @Test fun webDavDeleteRejectsALinkWithoutRemovingItsUnselectedOriginal() = mutation(NetworkProtocol.WEBDAV, false)
    @Test fun webDavUploadRejectsALinkWithoutReplacingItsUnselectedOriginal() = mutation(NetworkProtocol.WEBDAV, true)

    private fun mutation(protocol: NetworkProtocol, upload: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "lan-alias-${UUID.randomUUID()}").apply { check(mkdir()) }
        val shared = File(root, "shared").apply { check(mkdir()) }
        val original = File(shared, "unselected.txt").apply { writeText("unselected original") }
        val alias = File(shared, "selected.txt")
        Files.createSymbolicLink(alias.toPath(), original.toPath())
        val source = File(root, "source.txt").apply { writeText("replacement must not land at the original") }
        val loopback = InetAddress.getByName("127.0.0.1")
        val secret = UUID.randomUUID().toString()
        val server: TemporaryLanServer = if (protocol == NetworkProtocol.FTP)
            LanFtpServer(shared, loopback, requestedUsername = "audit", requestedCode = secret)
        else LanWebDavServer(shared, loopback, requestedUsername = "audit", requestedCode = secret)
        var client: RemoteClient? = null
        try {
            val session = server.start()
            val profile = NetworkProfile("owned-alias", "Owned alias", protocol, "127.0.0.1", session.port,
                "audit", "/", webDavUseTls = false)
            client = if (protocol == NetworkProtocol.FTP) FtpRemoteClient.connect(profile, secret.toCharArray())
                else WebDavRemoteClient.connect(profile, secret.toCharArray())
            val result = runCatching {
                if (upload) client.upload(source, "/selected.txt") else client.delete("/selected.txt")
            }
            assertTrue("Unselected original was removed by a selected alias", original.isFile)
            assertEquals("Unselected original was overwritten through a selected alias", "unselected original", original.readText())
            assertTrue("The unsupported alias operation must be visibly rejected", result.isFailure)
            assertTrue(Files.isSymbolicLink(alias.toPath()))
            assertFalse(shared.list().orEmpty().any { it.startsWith(".af-") })
        } finally {
            try { client?.close() } finally { server.close(); Files.deleteIfExists(alias.toPath()); check(root.deleteRecursively()) }
        }
    }

    @Test fun webShareCannotCreateDirectoriesThroughAnAlias() = webAlias("mkdir", "alias/created", true)
    @Test fun webShareCannotReadAnAliasAsIfItWereTheOriginal() = webAlias("download", "alias/original.txt", false)

    private fun webAlias(action: String, relative: String, creates: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "web-alias-${UUID.randomUUID()}").apply { check(mkdir()) }
        val original = File(root, "original").apply { check(mkdir()) }
        val file = File(original, "original.txt").apply { writeText("unselected original") }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), original.toPath())
        LanHttpServer(root, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                val session = server.start()
                val base = "http://127.0.0.1:${session.port}"
                val login = URL("$base/login").openConnection() as HttpURLConnection
                val cookie = try {
                    login.instanceFollowRedirects = false; login.requestMethod = "POST"; login.doOutput = true
                    login.connectTimeout = 5000; login.readTimeout = 5000
                    login.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    login.outputStream.use { it.write("code=${URLEncoder.encode(session.code, "UTF-8")}".toByteArray()) }
                    assertEquals(200, login.responseCode)
                    requireNotNull(login.getHeaderField("Set-Cookie")).substringBefore(';')
                } finally { login.disconnect() }
                val connection = URL("$base/$action?path=${URLEncoder.encode(relative, "UTF-8")}").openConnection() as HttpURLConnection
                val status = try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    connection.setRequestProperty("Cookie", cookie)
                    if (creates) connection.requestMethod = "POST"
                    connection.responseCode
                } finally { connection.disconnect() }
                assertFalse("Web share created an unselected referent's child", File(original, "created").exists())
                assertEquals("unselected original", file.readText())
                assertTrue("Alias request was not rejected: $status", status in 400..499)
            } finally { Files.deleteIfExists(alias.toPath()); check(root.deleteRecursively()) }
        }
    }
}
