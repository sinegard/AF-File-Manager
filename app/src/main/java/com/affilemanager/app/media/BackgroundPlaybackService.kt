package com.affilemanager.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.ui.localization.UiTranslator

/**
 * Plays audio (including a video's audio track) after the preview is backgrounded.
 * Playback only starts after an explicit user action and remains visible as an
 * ongoing Android media notification with a stop action.
 */
class BackgroundPlaybackService : Service() {
    companion object {
        private const val ACTION_PLAY = "com.affilemanager.app.action.PLAY_IN_BACKGROUND"
        private const val ACTION_STOP = "com.affilemanager.app.action.STOP_BACKGROUND_PLAYBACK"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_VOLUME = "volume"
        private const val CHANNEL_ID = "background_media"
        private const val NOTIFICATION_ID = 44

        fun play(
            context: Context,
            uri: Uri,
            title: String,
            positionMillis: Long,
            loop: Boolean,
            speed: Float,
            volume: Float,
        ) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSITION, positionMillis.coerceAtLeast(0L))
                .putExtra(EXTRA_LOOP, loop)
                .putExtra(EXTRA_SPEED, speed.coerceIn(0.5f, 2f))
                .putExtra(EXTRA_VOLUME, volume.coerceIn(0f, 1f))
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BackgroundPlaybackService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private var player: MediaPlayer? = null
    private var currentTitle: String = "AF File Manager"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_PLAY) return START_NOT_STICKY

        val uri = intent.getStringExtra(EXTRA_URI)?.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (uri == null) {
            stopPlayback()
            return START_NOT_STICKY
        }
        currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "AF File Manager" }
        val position = intent.getLongExtra(EXTRA_POSITION, 0L).coerceAtLeast(0L)
        val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.5f, 2f)
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)

        startAsForeground(notification(preparing = true))
        releasePlayer()
        val created = MediaPlayer()
        player = created
        runCatching {
            created.setDataSource(this, uri)
            created.isLooping = loop
            created.setVolume(volume, volume)
            created.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                runCatching { ready.playbackParams = ready.playbackParams.setSpeed(speed) }
                if (position > 0L) ready.seekTo(position.coerceAtMost(ready.duration.toLong()).toInt())
                ready.start()
                startAsForeground(notification(preparing = false))
            }
            created.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            created.prepareAsync()
        }.onFailure { stopPlayback() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun stopPlayback() {
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        player = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized("Foninis medijos atkūrimas"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = localized("Naudotojo paleista medija atkuriama fone") }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(preparing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BackgroundPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(currentTitle)
            .setContentText(localized(if (preparing) "Ruošiamas foninis atkūrimas" else "Atkuriama fone"))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, localized("Sustabdyti"), stopIntent)
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun localized(source: String): String =
        UiTranslator.translate(source, resources.configuration.locales[0].language)
}
