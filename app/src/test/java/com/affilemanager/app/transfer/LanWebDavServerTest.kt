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
import java.util.Base64

class LanWebDavServerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun basicAuthenticationPropfindAndPutWorkInsideRoot() {
        val root = temporary.newFolder("dav-root").apply { resolve("visible.txt").writeText("hello") }
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val port = server.start().port
            val anonymous = request(port, "OPTIONS / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(anonymous.startsWith("HTTP/1.1 401"))

            val propfind = request(port, authenticated("PROPFIND / HTTP/1.1\r\nHost: localhost\r\nDepth: 1\r\n"))
            assertTrue(propfind.startsWith("HTTP/1.1 207"))
            assertTrue(propfind.contains("visible.txt"))
            assertFalse(propfind.contains(root.canonicalPath))

            val body = "saved through dav"
            val put = request(
                port,
                authenticated("PUT /new.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.toByteArray().size}\r\n") + body,
            )
            assertTrue(put.startsWith("HTTP/1.1 201"))
            assertEquals(body, root.resolve("new.txt").readText())
        }
    }

    @Test
    fun traversalIsRejectedAndLimitsAreExplicit() {
        val parent = temporary.newFolder("dav-boundary")
        val root = parent.resolve("root").apply { mkdir() }
        parent.resolve("secret.txt").writeText("secret")
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val response = request(server.start().port, authenticated("GET /%2e%2e/secret.txt HTTP/1.1\r\nHost: localhost\r\n"))
            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertFalse(response.contains("secret"))
        }
        assertEquals(10_000, LanWebDavServer.MAX_REQUESTS)
        assertEquals(100_000, LanWebDavServer.MAX_TREE_ENTRIES)
    }

    private fun authenticated(firstLines: String): String {
        val token = Base64.getEncoder().encodeToString("af:12345678".toByteArray(StandardCharsets.UTF_8))
        return firstLines.trimEnd('\r', '\n') + "\r\nAuthorization: Basic $token\r\n\r\n"
    }

    private fun request(port: Int, request: String): String = Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }
}
