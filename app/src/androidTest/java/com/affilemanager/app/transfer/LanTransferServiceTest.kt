package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LanTransferServiceTest {
    @Test
    fun foregroundServiceStartsAuthenticatesAndStopsOnPrivateEmulatorNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "lan-service-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "visible.txt").writeText("hello")
        try {
            LanTransferController.start(context, root.absolutePath, 1)
            val running = awaitState { it.status == LanTransferStatus.RUNNING || it.status == LanTransferStatus.ERROR }
            check(running.status == LanTransferStatus.RUNNING) { running.message ?: "LAN paslauga nepasileido" }
            val uri = URI(running.url!!)

            val anonymous = request(uri.host, uri.port, "GET / HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n")
            assertTrue(anonymous.startsWith("HTTP/1.1 401"))
            assertFalse(anonymous.contains("visible.txt"))

            val body = "code=${running.code}"
            val login = request(
                uri.host,
                uri.port,
                "POST /login HTTP/1.1\r\nHost: ${uri.host}\r\nContent-Length: ${body.length}\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n$body",
            )
            assertTrue(login.startsWith("HTTP/1.1 200"))
            val cookie = login.lineSequence().first { it.startsWith("Set-Cookie:") }
                .substringAfter("Set-Cookie:").substringBefore(';').trim()
            val listing = request(uri.host, uri.port, "GET / HTTP/1.1\r\nHost: ${uri.host}\r\nCookie: $cookie\r\n\r\n")
            assertTrue(listing.startsWith("HTTP/1.1 200"))
            assertTrue(listing.contains("visible.txt"))
            assertFalse(listing.contains(root.canonicalPath))
        } finally {
            LanTransferController.stop(context)
            awaitState { it.status == LanTransferStatus.STOPPED }
            root.deleteRecursively()
        }
    }

    private suspend fun awaitState(predicate: (LanTransferState) -> Boolean): LanTransferState {
        repeat(100) {
            LanTransferController.state.value.let { state -> if (predicate(state)) return state }
            delay(50)
        }
        error("LAN paslaugos būsena nepasikeitė laiku: ${LanTransferController.state.value}")
    }

    private fun request(host: String, port: Int, request: String): String = Socket(host, port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }
}
