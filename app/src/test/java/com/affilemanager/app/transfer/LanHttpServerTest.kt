package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class LanHttpServerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun loginUsesOneTimeCodeAndAuthenticatedCookie() {
        val root = temporary.newFolder("shared").apply { resolve("visible.txt").writeText("hello") }
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()

            val anonymous = request(session.port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(anonymous.startsWith("HTTP/1.1 401"))
            assertFalse(anonymous.contains("visible.txt"))

            val body = "code=12345678"
            val login = request(
                session.port,
                "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body",
            )
            assertTrue(login.startsWith("HTTP/1.1 200"))
            val cookie = login.lineSequence().first { it.startsWith("Set-Cookie:") }
                .substringAfter("Set-Cookie:").substringBefore(';').trim()

            val listing = request(session.port, "GET / HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n")
            assertTrue(listing.startsWith("HTTP/1.1 200"))
            assertTrue(listing.contains("visible.txt"))
            assertFalse(listing.contains(root.canonicalPath))
        }
    }

    @Test
    fun traversalOutsideRootIsRejected() {
        val parent = temporary.newFolder("boundary")
        val root = parent.resolve("root").apply { mkdir() }
        parent.resolve("secret.txt").writeText("secret-value")
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            val cookie = login(session.port)

            val response = request(
                session.port,
                "GET /download?path=..%2Fsecret.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertFalse(response.contains("secret-value"))
        }
    }

    @Test
    fun uploadUsesBoundedPartialThenAtomicVisibleName() {
        val root = temporary.newFolder("upload")
        LanHttpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            val cookie = login(session.port)
            val content = "uploaded-content"

            val response = request(
                session.port,
                "POST /upload?dir=&name=report.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${content.toByteArray().size}\r\nContent-Type: application/octet-stream\r\n\r\n$content",
            )

            assertTrue(response.startsWith("HTTP/1.1 201"))
            assertEquals(content, root.resolve("report.txt").readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        }
    }

    @Test
    fun resourceAndAuthenticationLimitsAreExplicit() {
        assertEquals(4, LanHttpServer.MAX_CONCURRENT_REQUESTS)
        assertEquals(16, LanHttpServer.MAX_QUEUED_REQUESTS)
        assertEquals(10_000, LanHttpServer.MAX_REQUESTS_PER_SESSION)
        assertEquals(20, LanHttpServer.MAX_AUTH_FAILURES)
        assertEquals(60, LanHttpServer.MAX_SESSION_MINUTES)
    }

    private fun login(port: Int): String {
        val body = "code=12345678"
        val response = request(
            port,
            "POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body",
        )
        return response.lineSequence().first { it.startsWith("Set-Cookie:") }
            .substringAfter("Set-Cookie:").substringBefore(';').trim()
    }

    private fun request(port: Int, request: String): String = Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }
}
