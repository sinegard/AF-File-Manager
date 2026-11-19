package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

class NearbyBulkHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun authenticatedBulkSessionSurvivesTheOldTenThousandRequestBoundary() {
        LanHttpServer(temporary.newFolder("receiver"), InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val port = server.start().port
            val login = request(port, "POST", "/login", body = "code=12345678".toByteArray())
            assertTrue(login.startsWith("HTTP/1.1 200"))
            assertTrue(login.contains("${NearbyTransferCapacity.HEADER}: 30000\r\n"))
            val cookie = login.lineSequence().first { it.startsWith("Set-Cookie:") }.substringAfter(": ").substringBefore(';')
            val batch = UUID.randomUUID().toString()
            val manifest = NearbyTransferManifest.encode(List(30_000) { TransferFileProgress("$it.txt", 0) })
            assertTrue(request(port, "POST", "/nearby/manifest", cookie, batch, manifest).startsWith("HTTP/1.1 200"))
            val started = System.nanoTime()
            repeat(10_010) {
                val status = request(port, "GET", "/nearby/file-status?fileIndex=1", cookie, batch)
                assertTrue("Session stopped at request $it", status.startsWith("HTTP/1.1 200") && status.endsWith("WAITING"))
            }
            println("AF_BULK_10010_HTTP_REQUESTS_MS=${(System.nanoTime() - started) / 1_000_000L}")
        }
    }

    private fun request(port: Int, method: String, route: String, cookie: String = "", batch: String = "", body: ByteArray = byteArrayOf()): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val header = buildString {
                append("$method $route HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
                if (cookie.isNotEmpty()) append("Cookie: $cookie\r\n")
                if (batch.isNotEmpty()) append("X-AF-Batch-ID: $batch\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8).also { check(it.length < 16_384) }
        }
}
