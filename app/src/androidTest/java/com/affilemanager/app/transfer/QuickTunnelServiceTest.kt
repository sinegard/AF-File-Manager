package com.affilemanager.app.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class QuickTunnelServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun stopTunnel() {
        QuickTunnelController.stop(context)
    }

    @Test
    fun bundledCloudflaredPublishesAndServesTheLocalOrigin() = runBlocking {
        assumeTrue(
            "Run explicitly with afQuickTunnelLive=true because this uses Cloudflare's external service",
            InstrumentationRegistry.getArguments().getString("afQuickTunnelLive") == "true",
        )
        LoopbackHttpFixture().use { fixture ->
            QuickTunnelController.start(context, "http://10.0.2.15:${fixture.port}", null)
            val publicUrl = awaitPublicUrl()
            assertTrue(publicUrl.endsWith(".trycloudflare.com"))
            // The URL is announced only after cloudflared registers its edge
            // connection, but public DNS can still take a few seconds to reach
            // the resolver used by this Android instance. Avoid poisoning the
            // platform's negative DNS cache by querying immediately.
            delay(10_000)
            assertEquals("AF tunnel fixture", awaitPublicBody(publicUrl))
        }
    }

    private suspend fun awaitPublicUrl(): String {
        repeat(240) {
            val state = QuickTunnelController.state.value
            state.publicUrl?.let { return it }
            require(state.status != QuickTunnelStatus.ERROR) { state.message ?: "Quick Tunnel failed" }
            delay(250)
        }
        error("Quick Tunnel did not publish a URL within 60 seconds")
    }

    private suspend fun awaitPublicBody(url: String): String {
        var lastFailure: Throwable? = null
        repeat(60) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                try {
                    require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                    return connection.inputStream.bufferedReader().use(BufferedReader::readText)
                } finally {
                    connection.disconnect()
                }
            }.onFailure { lastFailure = it }
            delay(1_000)
        }
        throw AssertionError("Public tunnel did not reach the local fixture", lastFailure)
    }

    private class LoopbackHttpFixture : AutoCloseable {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        private val executor = Executors.newSingleThreadExecutor()
        val port: Int get() = server.localPort

        init {
            executor.execute {
                while (!server.isClosed) {
                    runCatching { server.accept() }.getOrNull()?.use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val body = "AF tunnel fixture".toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().buffered().use { output ->
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(body)
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }
}
