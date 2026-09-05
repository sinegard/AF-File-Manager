package com.affilemanager.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

enum class LanTransferStatus { STOPPED, STARTING, RUNNING, ERROR }
enum class LanTransferProtocol { WEB, FTP, WEBDAV }

data class LanTransferState(
    val status: LanTransferStatus = LanTransferStatus.STOPPED,
    val rootPath: String? = null,
    val rootName: String? = null,
    val url: String? = null,
    val code: String? = null,
    val username: String? = null,
    val protocol: LanTransferProtocol = LanTransferProtocol.WEB,
    val readOnly: Boolean = false,
    val expiresAtMillis: Long? = null,
    val message: String? = null,
    val incomingUpload: LanUploadProgress? = null,
)

object LanTransferController {
    private val _state = MutableStateFlow(LanTransferState())
    val state: StateFlow<LanTransferState> = _state.asStateFlow()

    fun start(
        context: Context,
        rootPath: String,
        durationMinutes: Int = 15,
        protocol: LanTransferProtocol = LanTransferProtocol.WEB,
        options: LanTransferOptions = LanTransferOptions(),
    ) {
        val validatedOptions = options.validated(protocol)
        val intent = Intent(context, LanTransferService::class.java)
            .setAction(LanTransferService.ACTION_START)
            .putExtra(LanTransferService.EXTRA_ROOT, rootPath)
            .putExtra(LanTransferService.EXTRA_DURATION_MINUTES, durationMinutes.coerceIn(1, LanHttpServer.MAX_SESSION_MINUTES))
            .putExtra(LanTransferService.EXTRA_PROTOCOL, protocol.name)
            .putExtra(LanTransferService.EXTRA_PORT, validatedOptions.port)
            .putExtra(LanTransferService.EXTRA_USERNAME, validatedOptions.username)
            .putExtra(LanTransferService.EXTRA_PASSWORD, validatedOptions.password)
            .putExtra(LanTransferService.EXTRA_READ_ONLY, validatedOptions.readOnly)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, LanTransferService::class.java).setAction(LanTransferService.ACTION_STOP))
    }

    internal fun publish(state: LanTransferState) {
        _state.value = state
    }
}

class LanTransferService : Service() {
    companion object {
        const val ACTION_START = "com.affilemanager.app.action.START_LAN_TRANSFER"
        const val ACTION_STOP = "com.affilemanager.app.action.STOP_LAN_TRANSFER"
        const val EXTRA_ROOT = "root"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_READ_ONLY = "read_only"
        private const val CHANNEL_ID = "lan_transfer"
        private const val NOTIFICATION_ID = 41
    }

    private var server: TemporaryLanServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer("Sustabdyta naudotojo")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        startAsForeground(startingNotification())
        if (server != null) {
            LanTransferController.publish(LanTransferController.state.value.copy(message = "LAN sesija jau veikia"))
            return START_NOT_STICKY
        }

        val rootPath = intent.getStringExtra(EXTRA_ROOT).orEmpty()
        val duration = intent.getIntExtra(EXTRA_DURATION_MINUTES, 15).coerceIn(1, LanHttpServer.MAX_SESSION_MINUTES)
        val protocol = runCatching {
            LanTransferProtocol.valueOf(intent.getStringExtra(EXTRA_PROTOCOL).orEmpty())
        }.getOrDefault(LanTransferProtocol.WEB)
        val rawOptions = LanTransferOptions(
            port = intent.getIntExtra(EXTRA_PORT, 0),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
            readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false),
        )
        val options = runCatching { rawOptions.validated(protocol) }.getOrElse { error ->
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.ERROR,
                    rootPath = rootPath,
                    rootName = File(rootPath).name,
                    protocol = protocol,
                    readOnly = rawOptions.readOnly,
                    message = error.message ?: "Netinkami bendrinimo nustatymai",
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        LanTransferController.publish(
            LanTransferState(
                status = LanTransferStatus.STARTING,
                rootPath = rootPath,
                rootName = File(rootPath).name,
                protocol = protocol,
                readOnly = options.readOnly,
                message = "Ieškomas privatus vietinio tinklo adresas",
            ),
        )
        runCatching {
            val root = File(rootPath).canonicalFile
            require(root.isDirectory && root.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
            val address = privateLanAddress() ?: throw IllegalStateException("Privatus Wi-Fi arba Ethernet IPv4 adresas nerastas")
            val stopped: (String) -> Unit = { reason ->
                LanTransferController.publish(LanTransferState(status = LanTransferStatus.STOPPED, message = reason))
                stopSelf()
            }
            when (protocol) {
                LanTransferProtocol.WEB -> LanHttpServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    language = resources.configuration.locales[0].language,
                    onUploadProgress = { progress ->
                        val current = LanTransferController.state.value
                        if (current.status == LanTransferStatus.RUNNING) {
                            LanTransferController.publish(current.copy(incomingUpload = progress))
                        }
                    },
                    onStopped = stopped,
                )
                LanTransferProtocol.FTP -> LanFtpServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedUsername = options.username.ifBlank { null },
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    onStopped = stopped,
                )
                LanTransferProtocol.WEBDAV -> LanWebDavServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedUsername = options.username.ifBlank { null },
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    onStopped = stopped,
                )
            }.also { server = it }.start()
        }.onSuccess { session ->
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.RUNNING,
                    rootPath = rootPath,
                    rootName = session.rootName,
                    url = session.url,
                    code = session.code,
                    username = session.username,
                    protocol = protocol,
                    readOnly = session.readOnly,
                    expiresAtMillis = session.expiresAtMillis,
                    message = "Serveris pasiekiamas tik pasirinktame privačiame tinkle",
                ),
            )
            startAsForeground(runningNotification(session))
        }.onFailure { error ->
            server = null
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.ERROR,
                    rootPath = rootPath,
                    rootName = File(rootPath).name,
                    protocol = protocol,
                    readOnly = options.readOnly,
                    message = error.message ?: "LAN serverio paleisti nepavyko",
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop("LAN paslauga sustabdyta")
        server = null
        super.onDestroy()
    }

    private fun stopServer(reason: String) {
        server?.stop(reason)
        server = null
        LanTransferController.publish(LanTransferState(status = LanTransferStatus.STOPPED, message = reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.lan_transfer_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.lan_transfer_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startingNotification(): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.lan_transfer_starting_title))
        .setContentText(getString(R.string.lan_transfer_starting_text))
        .build()

    private fun runningNotification(session: LanServerSession): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.lan_transfer_running_title))
        .setContentText(session.url)
        .addAction(0, getString(R.string.stop), stopPendingIntent())
        .build()

    private fun notificationBuilder(): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, LanTransferService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun privateLanAddress(): InetAddress? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .sortedBy { network ->
                when {
                    network.name.startsWith("wlan", true) || network.name.startsWith("wifi", true) -> 0
                    network.name.startsWith("eth", true) -> 1
                    else -> 2
                }
            }
        return interfaces.asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull(InetAddress::isSiteLocalAddress)
    }
}
