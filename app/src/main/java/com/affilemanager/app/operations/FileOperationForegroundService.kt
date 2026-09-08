package com.affilemanager.app.operations

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
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.localization.UiTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class FileOperationForegroundService : Service() {
    companion object {
        private const val ACTION_START = "com.affilemanager.app.action.START_FILE_OPERATIONS"
        private const val ACTION_CANCEL = "com.affilemanager.app.action.CANCEL_FILE_OPERATION"
        private const val EXTRA_OPERATION_ID = "operation_id"
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 44

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FileOperationForegroundService::class.java)
                .setAction(ACTION_START))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private val manager: FileOperationManager
        get() = (application as AFFileManagerApplication).graph.operationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_OPERATION_ID)?.takeIf { it.length in 1..80 }?.let(manager::cancel)
        }
        startAsForeground(notification(null))
        if (observation == null) observation = serviceScope.launch {
            manager.operations.collectLatest { operations ->
                val active = selectActive(operations)
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startAsForeground(notification(active))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observation = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun selectActive(operations: List<OperationSnapshot>): OperationSnapshot? =
        operations.firstOrNull { it.status == OperationStatus.RUNNING || it.status == OperationStatus.PAUSED }
            ?: operations.firstOrNull { it.status == OperationStatus.QUEUED }

    private fun notification(operation: OperationSnapshot?): Notification {
        val title = operation?.title?.let(::localized) ?: localized("Ruošiama failų operacija")
        val progress = operation?.progressPair()
        val current = operation?.currentName?.let { File(it).name.take(100) }
        val summary = when {
            current != null -> current
            operation?.totalBytes != null -> "${FileSystemRules.humanBytes(operation.completedBytes)} / ${FileSystemRules.humanBytes(operation.totalBytes)}"
            operation?.totalItems != null -> "${operation.completedItems} / ${operation.totalItems}"
            else -> localized("Ruošiama…")
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(summary)
            .setOnlyAlertOnce(true)
            .setOngoing(operation != null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (progress == null) builder.setProgress(1, 0, true)
        else builder.setProgress(progress.first, progress.second, false)
        operation?.let {
            builder.addAction(0, localized("Atšaukti"), PendingIntent.getService(this, it.id.hashCode(),
                Intent(this, FileOperationForegroundService::class.java).setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_OPERATION_ID, it.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun OperationSnapshot.progressPair(): Pair<Int, Int>? = when {
        totalBytes != null && totalBytes > 0 -> {
            val max = 10_000
            max to ((completedBytes.coerceIn(0, totalBytes).toDouble() / totalBytes) * max).toInt()
        }
        totalItems != null && totalItems > 0 -> totalItems.coerceAtMost(Int.MAX_VALUE) to completedItems.coerceIn(0, totalItems)
        else -> null
    }

    private fun localized(source: String) = UiTranslator.translate(source, resources.configuration.locales[0].language)

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, localized("Failų operacijos"), NotificationManager.IMPORTANCE_LOW).apply {
            description = localized("Kopijavimo, perkėlimo ir šalinimo eiga")
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
