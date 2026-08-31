package com.affilemanager.benchmark

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object AfBenchmarkEnvironment {
    const val PACKAGE_NAME = "com.affilemanager.app"
    const val SETUP_READY = "BENCHMARK_READY"
    const val REMOTE_PROFILE_ID = "af-benchmark-ftp"
    const val FTP_PORT = 21210

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun resetTargetData() {
        val result = UiDevice.getInstance(instrumentation)
            .executeShellCommand("pm clear $PACKAGE_NAME")
            .trim()
        check(result == "Success") { "Benchmark target data reset failed: $result" }
    }

    fun prepareTarget(device: UiDevice) {
        val intent = Intent().apply {
            component = ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.benchmark.BenchmarkSetupActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.text(SETUP_READY)), 120_000)) {
            "Benchmark data setup did not complete"
        }
        device.pressHome()
        device.waitForIdle()
    }

    fun localIntent(dataset: String) = mainIntent().putExtra("af_benchmark_dataset", dataset)

    fun remoteIntent() = mainIntent().putExtra("af_benchmark_remote_profile", REMOTE_PROFILE_ID)

    fun mainIntent() = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity")
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    fun MacrobenchmarkScope.awaitObject(resourceName: String, timeoutMillis: Long = 30_000): UiObject2 {
        // Compose exposes testTag values as raw accessibility resource names rather than
        // package-qualified Android resource IDs.
        check(device.wait(Until.hasObject(By.res(resourceName)), timeoutMillis)) {
            val networkError = device.findObject(By.res("network_error"))
            val visibleError = networkError?.flattenedText()?.takeIf(String::isNotBlank)
            if (visibleError == null) "Timed out waiting for $resourceName"
            else "Timed out waiting for $resourceName; visible network error: $visibleError"
        }
        return requireNotNull(device.findObject(By.res(resourceName)))
    }

    private fun UiObject2.flattenedText(): String = buildList {
        text?.takeIf(String::isNotBlank)?.let(::add)
        contentDescription?.takeIf(String::isNotBlank)?.let(::add)
        children.forEach { child -> child.flattenedText().takeIf(String::isNotBlank)?.let(::add) }
    }.distinct().joinToString(" | ")

    fun MacrobenchmarkScope.fling(resourceName: String, direction: Direction, times: Int) {
        repeat(times) {
            var completed = false
            var attempts = 0
            while (!completed && attempts < 5) {
                attempts += 1
                try {
                    awaitObject(resourceName).apply {
                        // Keep the gesture inside narrow dual-pane lists as well as phone layouts.
                        setGestureMargin(24)
                        fling(direction)
                    }
                    completed = true
                } catch (_: StaleObjectException) {
                    device.waitForIdle(250)
                }
            }
            check(completed) { "$resourceName kept changing while scrolling" }
        }
    }
}

/** A deterministic loopback FTP endpoint: no internet, credentials, or external server variance. */
internal class LoopbackFtpServer : Closeable {
    private val ipv4Loopback = InetAddress.getByName("127.0.0.1")
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "af-benchmark-ftp-client").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val unixList: ByteArray by lazy {
        buildString(760_000) {
            repeat(10_000) { index ->
                append("-rw-r--r-- 1 benchmark benchmark 32 Aug 16 12:00 remote-")
                append(index.toString().padStart(5, '0'))
                append(".txt\r\n")
            }
        }.toByteArray(StandardCharsets.US_ASCII)
    }
    private val machineList: ByteArray by lazy {
        buildString(720_000) {
            repeat(10_000) { index ->
                append("type=file;size=32;modify=20260816120000; remote-")
                append(index.toString().padStart(5, '0'))
                append(".txt\r\n")
            }
        }.toByteArray(StandardCharsets.US_ASCII)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(ipv4Loopback, AfBenchmarkEnvironment.FTP_PORT))
        }
        server = listener
        Log.i(LOG_TAG, "Listening on 127.0.0.1:${AfBenchmarkEnvironment.FTP_PORT}")
        acceptThread = Thread({
            while (running.get()) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                clients += socket
                workers.execute { handle(socket) }
            }
        }, "af-benchmark-ftp-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(socket: Socket) {
        var passive: ServerSocket? = null
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
            fun reply(line: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }
            fun enterPassive(extended: Boolean) {
                passive?.close()
                passive = ServerSocket(0, 1, ipv4Loopback).apply { soTimeout = 10_000 }
                val port = requireNotNull(passive).localPort
                if (extended) reply("229 Entering Extended Passive Mode (|||$port|)")
                else reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
            }

            reply("220 AF benchmark FTP ready")
            while (running.get()) {
                val raw = reader.readLine() ?: break
                val command = raw.substringBefore(' ').uppercase()
                Log.i(LOG_TAG, "Command $command")
                when (command) {
                    "USER" -> reply("331 Password required")
                    "PASS" -> reply("230 Logged in")
                    "SYST" -> reply("215 UNIX Type: L8")
                    "FEAT" -> {
                        writer.write("211-Features\r\n UTF8\r\n EPSV\r\n MLST type*;size*;modify*;\r\n211 End\r\n")
                        writer.flush()
                    }
                    "OPTS", "TYPE", "CWD", "NOOP" -> reply("200 OK")
                    "PWD" -> reply("257 \"/\" is current directory")
                    "PASV" -> enterPassive(extended = false)
                    "EPSV" -> enterPassive(extended = true)
                    "LIST", "MLSD" -> {
                        val dataServer = passive
                        if (dataServer == null) {
                            reply("425 Use PASV first")
                            continue
                        }
                        reply("150 Opening data connection")
                        dataServer.accept().use { data ->
                            data.getOutputStream().buffered().use { output ->
                                output.write(if (command == "MLSD") machineList else unixList)
                            }
                        }
                        dataServer.close()
                        passive = null
                        reply("226 Transfer complete")
                    }
                    "QUIT" -> {
                        reply("221 Goodbye")
                        break
                    }
                    else -> reply("502 Command not implemented")
                }
            }
        } catch (_: SocketTimeoutException) {
            // An idle control connection is expected while the benchmark scrolls a loaded list.
            Log.i(LOG_TAG, "Closing idle control connection")
        } finally {
            runCatching { passive?.close() }
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers.shutdownNow()
        acceptThread?.interrupt()
    }

    private companion object {
        const val LOG_TAG = "AfBenchmarkFtp"
    }
}
