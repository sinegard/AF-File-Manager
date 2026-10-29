package com.affilemanager.app.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.model.EntryKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.util.UUID

class NearbyReceiveDestinationSafetyAndroidTest {
    @Test fun aTypeDestinationRejectsAnExistingParentAlias() = withFixture { base, typed, victim ->
        val alias = File(typed, "batch")
        Files.createSymbolicLink(alias.toPath(), victim.toPath())
        try {
            val result = runCatching { NearbyReceiveDestinations(base, mapOf(EntryKind.DOCUMENT to typed)).directory("batch", "payload.txt") }
            assertTrue("Type routing silently followed an unselected directory", result.isFailure)
            assertEquals("unselected", File(victim, "marker.txt").readText())
        } finally { Files.deleteIfExists(alias.toPath()) }
    }

    @Test fun aTypeDestinationCannotCreateAChildThroughAParentAlias() = withFixture { base, typed, victim ->
        val alias = File(typed, "batch")
        Files.createSymbolicLink(alias.toPath(), victim.toPath())
        try {
            val result = runCatching { NearbyReceiveDestinations(base, mapOf(EntryKind.DOCUMENT to typed)).directory("batch/new-folder", "payload.txt") }
            assertFalse("Type routing created an unselected referent's child", File(victim, "new-folder").exists())
            assertTrue(result.isFailure)
        } finally { Files.deleteIfExists(alias.toPath()) }
    }

    @Test fun aBrokenTypeDestinationAliasDoesNotCreateItsMissingReferent() = withFixture { base, typed, _ ->
        val alias = File(typed, "batch")
        val missing = File(typed, "missing-referent")
        Files.createSymbolicLink(alias.toPath(), missing.toPath())
        try {
            val result = runCatching { NearbyReceiveDestinations(base, mapOf(EntryKind.DOCUMENT to typed)).directory("batch/child", "payload.txt") }
            assertFalse("Type routing created a broken alias's referent", missing.exists())
            assertTrue(result.isFailure)
        } finally { Files.deleteIfExists(alias.toPath()) }
    }

    @Test fun aQueuedUploadRejectsAnAliasInItsTypeDestination() = queuedUpload(true)
    @Test fun anOrdinaryQueuedUploadReachesItsSelectedTypeDestination() = queuedUpload(false)

    private fun queuedUpload(aliasTarget: Boolean) = withFixture { base, typed, victim ->
        check(File(base, "batch").mkdir())
        val alias = File(typed, "batch")
        if (aliasTarget) Files.createSymbolicLink(alias.toPath(), victim.toPath())
        val batch = UUID.randomUUID().toString()
        val payload = "received bytes".toByteArray()
        try {
            LanHttpServer(base, InetAddress.getByName("127.0.0.1"),
                receiveDestinations = mapOf(EntryKind.DOCUMENT to typed)).use { server ->
                val session = server.start()
                val origin = "http://127.0.0.1:${session.port}"
                val login = request(origin, "/login", "code=${URLEncoder.encode(session.code, "UTF-8")}".toByteArray())
                assertEquals(200, login.first)
                val cookie = requireNotNull(login.second)
                val manifest = NearbyTransferManifest.encode(listOf(TransferFileProgress("batch/payload.txt", payload.size.toLong())))
                assertEquals(200, request(origin, "/nearby/manifest", manifest, cookie, batch).first)
                val status = request(origin, "/upload?dir=batch&name=payload.txt&fileIndex=1&fileCount=1", payload, cookie, batch).first
                assertEquals("unselected", File(victim, "marker.txt").readText())
                if (aliasTarget) {
                    assertFalse("Authenticated upload wrote into an unselected referent", File(victim, "payload.txt").exists())
                    assertTrue("Alias upload was not visibly rejected: $status", status in 400..499)
                    assertTrue(Files.isSymbolicLink(alias.toPath()))
                } else {
                    assertEquals(201, status)
                    assertArrayEquals(payload, File(typed, "batch/payload.txt").readBytes())
                    assertFalse(File(base, "batch/payload.txt").exists())
                }
                assertFalse(typed.walkTopDown().any { !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(".af-upload-") })
            }
        } finally { if (aliasTarget) Files.deleteIfExists(alias.toPath()) }
    }

    private fun request(origin: String, path: String, body: ByteArray, cookie: String? = null, batch: String? = null): Pair<Int, String?> {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            batch?.let { connection.setRequestProperty("X-AF-Batch-ID", it) }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val acceptedCookie = connection.getHeaderField("Set-Cookie")?.substringBefore(';')
            (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
            return status to acceptedCookie
        } finally { connection.disconnect() }
    }

    private fun withFixture(block: (File, File, File) -> Unit) {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "receive-route-${UUID.randomUUID()}").apply { check(mkdir()) }
        val base = File(root, "base").apply { check(mkdir()) }
        val typed = File(root, "typed").apply { check(mkdir()) }
        val victim = File(typed, "unselected").apply { check(mkdir()) }
        File(victim, "marker.txt").writeText("unselected")
        try { block(base, typed, victim) } finally {
            assertEquals("unselected", File(victim, "marker.txt").readText())
            check(root.deleteRecursively())
        }
    }
}
