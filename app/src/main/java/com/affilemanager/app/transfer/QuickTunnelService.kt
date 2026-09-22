package com.affilemanager.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.affilemanager.app.R
import com.affilemanager.app.ui.localization.UiTranslator
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class QuickTunnelService : Service() {
    companion object {
        const val ACTION_START = "com.affilemanager.app.action.START_QUICK_TUNNEL"
        const val ACTION_STOP = "com.affilemanager.app.action.STOP_QUICK_TUNNEL"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_EXPIRES_AT = "expires_at"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "quick_tunnel"
        private const val NOTIFICATION_ID = 71
        private val EDGE_HOSTS = listOf(
            "region1.v2.argotunnel.com",
            "region2.v2.argotunnel.com",
        )
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicLong()
    @Volatile private var process: Process? = null
    @Volatile private var expiryTask: ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized("AF Quick Tunnel"), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, notification(localized("Kuriama laikina vieša nuoroda"), null))
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().take(96)
        try {
            require(requestId.isNotBlank()) { "Tunelio užklausa netinkama" }
            val origin = requireLocalOrigin(intent.getStringExtra(EXTRA_ORIGIN))
            val expiresAt = requireExpiry(intent.getLongExtra(EXTRA_EXPIRES_AT, 0L))
            startTunnel(requestId, origin, expiresAt)
        } catch (error: Throwable) {
            QuickTunnelController.publishError(requestId.ifBlank { null }, safeMessage(error))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        generation.incrementAndGet()
        cancelExpiry()
        destroyProcess()
        worker.shutdownNow()
        timer.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel(requestId: String, origin: String, expiresAt: Long) {
        val run = generation.incrementAndGet()
        cancelExpiry()
        destroyProcess()
        QuickTunnelController.publishStarting(requestId)
        updateNotification(localized("Kuriama laikina vieša nuoroda"), null)

        if (expiresAt > 0L) {
            val delay = (expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)
            expiryTask = timer.schedule({
                if (generation.get() == run) {
                    stopTunnel()
                    stopSelf()
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
        worker.execute { runCloudflared(run, requestId, origin) }
    }

    private fun runCloudflared(run: Long, requestId: String, origin: String) {
        var lastLine: String? = null
        val outputParser = QuickTunnelOutputParser()
        try {
            val binary = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
            require(binary.isFile) { "Šio telefono procesoriui Quick Tunnel komponentas nerastas" }
            val command = buildList {
                add(binary.absolutePath)
                add("tunnel")
                add("--url")
                add(origin)
                add("--protocol")
                add("http2")
                add("--no-autoupdate")
                add("--loglevel")
                add("info")
                add("--metrics")
                add("127.0.0.1:0")
                addAll(staticEdgeArguments())
            }
            val launched = ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["TMPDIR"] = cacheDir.absolutePath
                    environment()["GODEBUG"] = "netdns=cgo"
                }
                .start()
            process = launched
            launched.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val iterator = lines.iterator()
                while (generation.get() == run && iterator.hasNext()) {
                    val line = iterator.next()
                    lastLine = line.replace('\n', ' ').replace('\r', ' ').take(240)
                    outputParser.accept(line)?.let { publicUrl ->
                        QuickTunnelController.publishRunning(requestId, publicUrl)
                        updateNotification(localized("Quick Tunnel veikia"), publicUrl)
                    }
                }
            }
            val exitCode = launched.waitFor()
            if (generation.get() == run) {
                error(lastLine ?: "cloudflared sustojo (kodas $exitCode)")
            }
        } catch (error: Throwable) {
            if (generation.get() == run) {
                QuickTunnelController.publishError(requestId, safeMessage(error))
                updateNotification(localized("Quick Tunnel sustojo dėl klaidos"), null)
                stopSelf()
            }
        } finally {
            if (generation.get() == run) process = null
        }
    }

    private fun stopTunnel() {
        generation.incrementAndGet()
        cancelExpiry()
        destroyProcess()
        QuickTunnelController.publishStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun destroyProcess() {
        val active = process ?: return
        process = null
        active.destroy()
        try {
            if (!active.waitFor(2, TimeUnit.SECONDS)) active.destroyForcibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            active.destroyForcibly()
        }
    }

    private fun cancelExpiry() {
        expiryTask?.cancel(false)
        expiryTask = null
    }

    private fun notification(text: String, detail: String?): Notification {
        val stop = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent(this, QuickTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(localized("AF File Manager"))
            .setContentText(detail ?: text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, localized("Sustabdyti"), stop)
            .build()
    }

    private fun updateNotification(text: String, detail: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, detail))
    }

    private fun localized(text: String): String = UiTranslator.translate(
        text,
        resources.configuration.locales[0]?.language ?: "en",
    )

    private fun requireLocalOrigin(value: String?): String {
        return QuickTunnelRules.localOrigin(value.orEmpty())
    }

    private fun requireExpiry(value: Long): Long {
        if (value == 0L) return 0L
        val now = System.currentTimeMillis()
        require(value in (now + 1)..(now + QuickTunnelRules.MAX_TUNNEL_DURATION_MILLIS)) {
            "Tunelio pabaigos laikas netinkamas"
        }
        return value
    }

    private fun safeMessage(error: Throwable): String = error.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.take(240)
        ?.takeIf(String::isNotBlank)
        ?: "Tunelio paleisti nepavyko"

    /**
     * Go cannot perform SRV discovery through Android's resolver on every old
     * Android release because Android does not expose a traditional
     * resolv.conf. Resolve Cloudflare's documented region hosts through the
     * platform first and pass literal, TLS-authenticated edge addresses. If
     * platform DNS is unavailable, cloudflared retains its normal discovery.
     */
    private fun staticEdgeArguments(): List<String> {
        val addresses = EDGE_HOSTS.flatMap { host ->
            runCatching { InetAddress.getAllByName(host).asList() }
                .getOrDefault(emptyList())
                .filterIsInstance<Inet4Address>()
                .map(InetAddress::getHostAddress)
                .distinct()
                .take(2)
        }.distinct()
        if (addresses.isEmpty()) return emptyList()
        return buildList {
            add("--edge-ip-version")
            add("4")
            addresses.forEach { address ->
                add("--edge")
                add("$address:7844")
            }
        }
    }
}
