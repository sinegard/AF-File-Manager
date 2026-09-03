package com.affilemanager.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class WebDavRemoteClientTest {
    @Test
    fun collectionUrlsAndSameOriginRedirectsPreserveAuthenticationMethodAndBody() = runBlocking {
        Fixture { request ->
            when (request.path) {
                "/dav/" -> Reply(302, location = "/canonical/")
                "/canonical/" -> Reply(207, listing())
                "/canonical/hello%20world.txt" -> Reply(200, "hello from WebDAV")
                else -> Reply(404)
            }
        }.use { fixture ->
            val password = "synthetic-only".toCharArray()
            val client = WebDavRemoteClient.connect(profile(fixture.port), password)
            val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir
            val downloaded = File(cache, "webdav-redirect-${System.nanoTime()}.txt")
            try {
                assertTrue(password.all { it == '\u0000' })
                val entries = client.list("/dav/")
                assertEquals(listOf("folder", "hello world.txt"), entries.map { it.name })
                assertEquals("/canonical/hello world.txt", entries.last().path)
                assertTrue(entries.first().directory)
                client.download(entries.last().path, downloaded)
                assertEquals("hello from WebDAV", downloaded.readText())
                val reads = fixture.requests.filter { it.method == "PROPFIND" }
                assertEquals(listOf("/dav/", "/canonical/", "/dav/", "/canonical/"), reads.map { it.path })
                assertTrue(reads.all { it.headers["depth"] == "1" && "propfind" in it.body })
                assertTrue(fixture.requests.all { it.headers["authorization"] == Credentials.basic("test-user", "synthetic-only", Charsets.UTF_8) })
            } finally {
                client.close()
                downloaded.delete()
            }
        }
    }

    @Test
    fun plainCollectionAndHttpFailuresWorkWithoutRedirects() = runBlocking {
        Fixture { Reply(207, listing("/dav")) }.use { fixture ->
            val client = WebDavRemoteClient.connect(profile(fixture.port), "test".toCharArray())
            try { assertEquals(2, client.list("/dav").size) } finally { client.close() }
        }
        for (status in listOf(401, 403, 404, 405, 500)) {
            Fixture { Reply(status, "TOP_SECRET_MARKER") }.use { fixture ->
                val password = "test".toCharArray()
                val failure = runCatching { WebDavRemoteClient.connect(profile(fixture.port), password) }.exceptionOrNull()
                check(failure is WebDavHttpException)
                val shown = RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, failure)
                assertEquals("WEBDAV-CONNECT-HTTP-$status", shown.diagnosticCode)
                assertFalse(shown.toString().contains("TOP_SECRET_MARKER"))
                assertTrue(password.all { it == '\u0000' })
            }
        }
    }

    @Test
    fun rejectsCrossOriginRedirectBeforeSendingCredentialsAndBoundsLoops() = runBlocking {
        Fixture { Reply(207, listing()) }.use { otherOrigin ->
            Fixture { Reply(302, location = "http://127.0.0.1:${otherOrigin.port}/dav/") }.use { fixture ->
                val failure = runCatching { WebDavRemoteClient.connect(profile(fixture.port), "test".toCharArray()) }.exceptionOrNull()
                check(failure is WebDavRedirectException)
                assertEquals(WebDavRedirectFailure.UNSAFE, failure.reason)
                assertTrue(otherOrigin.requests.isEmpty())
            }
        }
        Fixture { Reply(302, location = "/dav/") }.use { fixture ->
            val failure = runCatching { WebDavRemoteClient.connect(profile(fixture.port), "test".toCharArray()) }.exceptionOrNull()
            check(failure is WebDavRedirectException)
            assertEquals(WebDavRedirectFailure.LIMIT, failure.reason)
            assertEquals(WebDavRedirects.MAX_REDIRECTS + 1, fixture.requests.size)
        }
    }

    @Test
    fun rejectedRedirectDoesNotRepeatAMutatingRequest() = runBlocking {
        Fixture { request -> if (request.method == "PROPFIND") Reply(207, listing("/dav")) else Reply(307, location = "/other/") }.use { fixture ->
            val client = WebDavRemoteClient.connect(profile(fixture.port), "test".toCharArray())
            try {
                val failure = runCatching { client.createDirectory("/dav/new") }.exceptionOrNull()
                check(failure is WebDavRedirectException)
                assertEquals(WebDavRedirectFailure.UNSUPPORTED, failure.reason)
                assertEquals(listOf("/dav/new"), fixture.requests.filter { it.method == "MKCOL" }.map { it.path })
                assertFalse(fixture.requests.any { it.path == "/other/" })
            } finally { client.close() }
        }
    }

    private fun profile(port: Int) = NetworkProfile(
        id = "webdav-regression", name = "WebDAV regression", protocol = NetworkProtocol.WEBDAV,
        host = "127.0.0.1", port = port, username = "test-user", basePath = "/dav", webDavUseTls = false,
    )

    private fun listing(path: String = "/canonical") = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>$path/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>$path/hello%20world.txt</d:href><d:propstat><d:prop><d:displayname>hello world.txt</d:displayname><d:getcontentlength>17</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>$path/folder/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()

    private data class RecordedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Reply(val code: Int, val body: String = "", val location: String? = null)

    /** A bounded, loopback-only fixture; no test relies on a public account or external service. */
    private class Fixture(private val respond: (RecordedRequest) -> Reply) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val error = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "webdav-test") {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        check(requests.size < 32)
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val line = checkNotNull(reader.readLine()).split(' ')
                        val headers = linkedMapOf<String, String>()
                        repeat(64) {
                            val header = checkNotNull(reader.readLine())
                            if (header.isEmpty()) {
                                val length = headers["content-length"]?.toInt() ?: 0
                                check(length in 0..65_536)
                                val body = CharArray(length)
                                var position = 0
                                while (position < body.size) {
                                    val count = reader.read(body, position, body.size - position)
                                    check(count > 0)
                                    position += count
                                }
                                val request = RecordedRequest(line[0], line[1], headers, body.concatToString())
                                requests += request
                                val reply = respond(request)
                                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                                val responseHeaders = buildString {
                                    append("HTTP/1.1 ${reply.code} Test\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/xml\r\nConnection: close\r\n")
                                    reply.location?.let { append("Location: $it\r\n") }
                                    append("\r\n")
                                }
                                socket.getOutputStream().apply {
                                    write(responseHeaders.toByteArray(Charsets.UTF_8))
                                    write(bytes)
                                    flush()
                                }
                                return@use
                            }
                            check(header.length <= 8_192)
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        error("Too many request headers")
                    }
                }
            } catch (failure: Throwable) {
                if (failure !is SocketException || !server.isClosed) error.set(failure)
            }
        }

        override fun close() {
            server.close()
            worker.join(6_000)
            check(!worker.isAlive) { "WebDAV fixture did not stop" }
            error.get()?.let { throw AssertionError("WebDAV fixture failed", it) }
        }
    }
}
