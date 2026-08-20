package com.affilemanager.app.terminal

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
import com.affilemanager.app.ui.TerminalLocation

class TerminalKeepAliveService : Service() {
    companion object {
        private const val ACTION_START = "com.affilemanager.app.action.START_TERMINAL"
        private const val ACTION_CLOSE = "com.affilemanager.app.action.CLOSE_TERMINAL"
        private const val EXTRA_LOCATION = "terminal_location"
        private const val CHANNEL_ID = "active_terminal"
        private const val NOTIFICATION_ID = 43
        private const val CLOSE_REQUEST_CODE = 43

        fun start(context: Context, location: TerminalLocation) {
            val intent = Intent(context, TerminalKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LOCATION, location.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalKeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = (application as AFFileManagerApplication).graph.terminalSessions
        if (intent?.action == ACTION_CLOSE) {
            store.closeNow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !store.state.value.visible) {
            stopSelf()
            return START_NOT_STICKY
        }

        val location = runCatching {
            TerminalLocation.valueOf(intent.getStringExtra(EXTRA_LOCATION).orEmpty())
        }.getOrDefault(store.state.value.location)
        startAsForeground(activeNotification(location))
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.terminal_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.terminal_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun activeNotification(location: TerminalLocation): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeIntent = PendingIntent.getService(
            this,
            CLOSE_REQUEST_CODE,
            Intent(this, TerminalKeepAliveService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val activeText = if (location == TerminalLocation.PHONE) {
            getString(R.string.terminal_phone_active)
        } else {
            getString(R.string.terminal_server_active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.terminal_notification_title))
            .setContentText(activeText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.close), closeIntent)
            .build()
    }
}
