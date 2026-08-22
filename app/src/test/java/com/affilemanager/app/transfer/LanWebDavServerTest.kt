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
        assertEquals(30_000L, LanWebDavServer.AUTH_LOCK_MILLIS)
        assertEquals(256, LanWebDavServer.MAX_LOCKS)
        assertEquals(64 * 1_024, LanWebDavServer.MAX_LOCK_BODY_BYTES)
        assertEquals(3_600L, LanWebDavServer.MAX_LOCK_SECONDS)
    }

    @Test
    fun chunkedPutStoresTheCompleteBody() {
        val root = temporary.newFolder("dav-chunked")
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val response = request(
                server.start().port,
                authenticated(
                    "PUT /chunked.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n",
                ) + "5;source=test\r\nhello\r\n7\r\n WebDAV\r\n0\r\nX-Test: done\r\n\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 201"))
            assertEquals("hello WebDAV", root.resolve("chunked.txt").readText())
        }
    }

    @Test
    fun literalPlusInAPathIsNotDecodedAsSpace() {
        val root = temporary.newFolder("dav-plus-path")
        val body = "plus"
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val response = request(
                server.start().port,
                authenticated("PUT /a+b.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\n") + body,
            )

            assertTrue(response.startsWith("HTTP/1.1 201"))
            assertEquals(body, root.resolve("a+b.txt").readText())
            assertFalse(root.resolve("a b.txt").exists())
        }
    }

    @Test
    fun expectContinueIsAcknowledgedBeforeTheFinalPutResponse() {
        val root = temporary.newFolder("dav-continue")
        val body = "continue without delay"
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val response = request(
                server.start().port,
                authenticated(
                    "PUT /continue.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Expect: 100-continue\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n",
                ) + body,
            )

            assertTrue(response.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201"))
            assertEquals(body, root.resolve("continue.txt").readText())
        }
    }

    @Test
    fun optionsLocksAndUnlocksFollowTheAdvertisedDavContract() {
        val root = temporary.newFolder("dav-locks")
        val lockBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:lockinfo xmlns:D="DAV:">
              <D:lockscope><D:exclusive/></D:lockscope>
              <D:locktype><D:write/></D:locktype>
              <D:owner><D:href>test</D:href></D:owner>
            </D:lockinfo>
        """.trimIndent()
        LanWebDavServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val port = server.start().port
            val options = request(port, authenticated("OPTIONS / HTTP/1.1\r\nHost: localhost\r\n"))
            assertTrue(options.contains("DAV: 1, 2"))
            assertTrue(options.contains("LOCK"))
            assertTrue(options.contains("UNLOCK"))

            val lockResponse = request(
                port,
                authenticated(
                    "LOCK /locked.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Depth: 0\r\n" +
                        "Timeout: Second-120\r\n" +
                        "Content-Length: ${lockBody.toByteArray().size}\r\n",
                ) + lockBody,
            )
            assertTrue(lockResponse.startsWith("HTTP/1.1 201"))
            val token = Regex("Lock-Token: <([^>]+)>", RegexOption.IGNORE_CASE)
                .find(lockResponse)?.groupValues?.get(1)
            assertTrue(!token.isNullOrBlank())

            val content = "locked content"
            val blockedPut = request(
                port,
                authenticated(
                    "PUT /locked.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${content.toByteArray().size}\r\n",
                ) + content,
            )
            assertTrue(blockedPut.startsWith("HTTP/1.1 423"))
            assertFalse(root.resolve("locked.txt").exists())

            val acceptedPut = request(
                port,
                authenticated(
                    "PUT /locked.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "If: (<$token>)\r\n" +
                        "Content-Length: ${content.toByteArray().size}\r\n",
                ) + content,
            )
            assertTrue(acceptedPut.startsWith("HTTP/1.1 201"))
            assertEquals(content, root.resolve("locked.txt").readText())

            val refresh = request(
                port,
                authenticated(
                    "LOCK /locked.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "If: (<$token>)\r\n" +
                        "Timeout: Second-180\r\n" +
                        "Content-Length: 0\r\n",
                ),
            )
            assertTrue(refresh.startsWith("HTTP/1.1 200"))

            val unlock = request(
                port,
                authenticated(
                    "UNLOCK /locked.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Lock-Token: <$token>\r\n",
                ),
            )
            assertTrue(unlock.startsWith("HTTP/1.1 204"))
        }
    }

    @Test
    fun authenticationLockExpiresAndValidCredentialsRecover() {
        val root = temporary.newFolder("dav-auth-lock")
        var now = 100_000L
        LanWebDavServer(
            root,
            InetAddress.getLoopbackAddress(),
            requestedCode = "12345678",
            nowMillis = { now },
        ).use { server ->
            val port = server.start().port
            repeat(LanWebDavServer.MAX_AUTH_FAILURES - 1) {
                assertTrue(request(port, "OPTIONS / HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 401"))
            }
            assertTrue(request(port, "OPTIONS / HTTP/1.1\r\nHost: localhost\r\n\r\n").startsWith("HTTP/1.1 429"))
            assertTrue(request(port, authenticated("OPTIONS / HTTP/1.1\r\nHost: localhost\r\n")).startsWith("HTTP/1.1 429"))

            now += LanWebDavServer.AUTH_LOCK_MILLIS + 1
            assertTrue(request(port, authenticated("OPTIONS / HTTP/1.1\r\nHost: localhost\r\n")).startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun customCredentialsAndReadOnlyModeKeepWebDavNonMutating() {
        val root = temporary.newFolder("dav-read-only").apply { resolve("visible.txt").writeText("visible") }
        LanWebDavServer(
            rootDirectory = root,
            bindAddress = InetAddress.getLoopbackAddress(),
            requestedUsername = "owner",
            requestedCode = "temporary-pass",
            readOnly = true,
        ).use { server ->
            val session = server.start()
            assertEquals("owner", session.username)
            assertTrue(session.readOnly)
            val token = Base64.getEncoder().encodeToString("owner:temporary-pass".toByteArray(StandardCharsets.UTF_8))
            fun authorized(lines: String): String =
                lines.trimEnd('\r', '\n') + "\r\nAuthorization: Basic $token\r\n\r\n"

            val options = request(session.port, authorized("OPTIONS / HTTP/1.1\r\nHost: localhost\r\n"))
            assertTrue(options.startsWith("HTTP/1.1 200"))
            assertTrue(options.contains("Allow: OPTIONS, PROPFIND, GET, HEAD"))
            assertFalse(options.contains("PUT, DELETE"))

            val listing = request(session.port, authorized("PROPFIND / HTTP/1.1\r\nHost: localhost\r\nDepth: 1\r\n"))
            assertTrue(listing.startsWith("HTTP/1.1 207"))
            assertTrue(listing.contains("visible.txt"))

            val blocked = request(
                session.port,
                authorized("PUT /blocked.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n") + "x",
            )
            assertTrue(blocked.startsWith("HTTP/1.1 403"))
            assertFalse(root.resolve("blocked.txt").exists())
        }
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
