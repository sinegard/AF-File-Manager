package com.affilemanager.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.ui.localization.UiTranslator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundPlaybackPhase { PREPARING, PLAYING, PAUSED, ERROR }

data class BackgroundPlaybackState(val uri: String, val title: String, val phase: BackgroundPlaybackPhase) {
    val active: Boolean get() = phase != BackgroundPlaybackPhase.ERROR
}

/** One explicitly started session, controlled both inside AF and by Android's media controls. */
class BackgroundPlaybackService : Service() {
    companion object {
        private const val ACTION_PLAY = "com.affilemanager.app.action.PLAY_IN_BACKGROUND"
        private const val ACTION_STOP = "com.affilemanager.app.action.STOP_BACKGROUND_PLAYBACK"
        private const val ACTION_TOGGLE = "com.affilemanager.app.action.TOGGLE_BACKGROUND_PLAYBACK"
        private const val CHANNEL_ID = "background_media"
        internal const val NOTIFICATION_ID = 44
        private const val PREPARE_TIMEOUT_MILLIS = 30_000L
        private val current = MutableStateFlow<BackgroundPlaybackState?>(null)
        val state = current.asStateFlow()

        fun play(context: Context, uri: Uri, title: String, positionMillis: Long, loop: Boolean, speed: Float, volume: Float) {
            require(uri.scheme in setOf("content", "file") && uri.toString().length <= 16_384)
            require(speed.isFinite() && volume.isFinite())
            ContextCompat.startForegroundService(context, Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra("uri", uri.toString())
                .putExtra("title", title.take(512))
                .putExtra("position", positionMillis.coerceAtLeast(0L))
                .putExtra("loop", loop)
                .putExtra("speed", speed.coerceIn(0.5f, 2f))
                .putExtra("volume", volume.coerceIn(0f, 1f)))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BackgroundPlaybackService::class.java).setAction(ACTION_STOP))
        }

        fun toggle(context: Context) {
            context.startService(Intent(context, BackgroundPlaybackService::class.java).setAction(ACTION_TOGGLE))
        }
    }

    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private val handler = Handler(Looper.getMainLooper())
    private val prepareTimeout = Runnable { if (current.value?.phase == BackgroundPlaybackPhase.PREPARING) finishPlayback(failed = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, localized("Foninis medijos atkūrimas"), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = localized("Naudotojo paleista medija atkuriama fone") })
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttributes())
            .setOnAudioFocusChangeListener({ change -> if (change < 0) pausePlayback() }, handler)
            .build()
        session = MediaSession(this, "AF background playback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onStop() = finishPlayback()
                override fun onPause() = pausePlayback()
                override fun onPlay() = resumePlayback()
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == ACTION_STOP) finishPlayback()
                }
            }, handler)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> startPlayback(intent)
            ACTION_STOP -> finishPlayback()
            ACTION_TOGGLE -> when (current.value?.phase) {
                BackgroundPlaybackPhase.PLAYING -> pausePlayback()
                BackgroundPlaybackPhase.PAUSED -> resumePlayback()
                BackgroundPlaybackPhase.PREPARING -> Unit
                else -> finishPlayback()
            }
            else -> if (player == null) finishPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent) {
        val uriText = intent.getStringExtra("uri").orEmpty()
        val uri = Uri.parse(uriText)
        if (uriText.length > 16_384 || uri.scheme !in setOf("content", "file")) {
            finishPlayback(failed = true)
            return
        }
        releasePlayer()
        current.value = BackgroundPlaybackState(uriText, intent.getStringExtra("title").orEmpty().take(512)
            .ifBlank { "AF File Manager" }, BackgroundPlaybackPhase.PREPARING)
        val position = intent.getLongExtra("position", 0L).coerceAtLeast(0L)
        val speed = intent.getFloatExtra("speed", 1f).takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f
        val volume = intent.getFloatExtra("volume", 1f).takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        runCatching {
            updateControls()
            val created = MediaPlayer()
            player = created
            created.setAudioAttributes(mediaAttributes())
            created.setDataSource(this, uri)
            created.isLooping = intent.getBooleanExtra("loop", true)
            created.setVolume(volume, volume)
            created.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                handler.removeCallbacks(prepareTimeout)
                runCatching {
                    // PlaybackParams may start MediaPlayer; take audio focus first.
                    check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    if (speed != 1f) ready.playbackParams = ready.playbackParams.setSpeed(speed)
                    if (position > 0L) ready.seekTo(position.coerceAtMost(ready.duration.toLong()).toInt())
                    ready.start()
                    current.value = current.value?.copy(phase = BackgroundPlaybackPhase.PLAYING)
                    updateControls()
                }.onFailure { finishPlayback(failed = true) }
            }
            created.setOnCompletionListener { completed -> if (player === completed) finishPlayback() }
            created.setOnErrorListener { failed, _, _ ->
                if (player === failed) finishPlayback(failed = true)
                true
            }
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MILLIS)
            created.prepareAsync()
        }.onFailure { finishPlayback(failed = true) }
    }

    private fun pausePlayback() {
        if (current.value?.phase != BackgroundPlaybackPhase.PLAYING) return
        runCatching {
            player?.pause()
            audioManager.abandonAudioFocusRequest(focusRequest)
            current.value = current.value?.copy(phase = BackgroundPlaybackPhase.PAUSED)
            updateControls()
        }.onFailure { finishPlayback(failed = true) }
    }

    private fun resumePlayback() {
        if (current.value?.phase != BackgroundPlaybackPhase.PAUSED) return
        runCatching {
            check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            requireNotNull(player).start()
            current.value = current.value?.copy(phase = BackgroundPlaybackPhase.PLAYING)
            updateControls()
        }.onFailure { finishPlayback(failed = true) }
    }

    private fun finishPlayback(failed: Boolean = false) {
        releasePlayer()
        session.isActive = false
        current.value = if (failed) current.value?.copy(phase = BackgroundPlaybackPhase.ERROR) else null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(prepareTimeout)
        val old = player
        player = null // Late callbacks cannot restart a stopped or replaced session.
        old?.setOnPreparedListener(null)
        old?.setOnErrorListener(null)
        old?.setOnCompletionListener(null)
        runCatching { old?.stop() }
        old?.release()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun onDestroy() {
        releasePlayer()
        session.release()
        if (current.value?.phase != BackgroundPlaybackPhase.ERROR) current.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun updateControls() {
        val now = current.value ?: return
        val playing = now.phase == BackgroundPlaybackPhase.PLAYING
        val preparing = now.phase == BackgroundPlaybackPhase.PREPARING
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, now.title).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setState(when (now.phase) {
                BackgroundPlaybackPhase.PLAYING -> PlaybackState.STATE_PLAYING
                BackgroundPlaybackPhase.PAUSED -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_BUFFERING
            }, runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L), if (playing) 1f else 0f)
            .setActions(PlaybackState.ACTION_STOP or if (preparing) 0L else
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .addCustomAction(ACTION_STOP, localized("Sustabdyti"), android.R.drawable.ic_menu_close_clear_cancel)
            .build())
        session.isActive = true
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app).setContentTitle(now.title)
            .setContentText(localized(when (now.phase) {
                BackgroundPlaybackPhase.PREPARING -> "Ruošiamas foninis atkūrimas"
                BackgroundPlaybackPhase.PAUSED -> "Pauzė"
                else -> "Atkuriama fone"
            }))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_TRANSPORT)
        if (!preparing) builder.addAction(Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            localized(if (playing) "Pauzė" else "Tęsti"), commandIntent(ACTION_TOGGLE, 2),
        ).build())
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            localized("Sustabdyti"), commandIntent(ACTION_STOP, 1)).build())
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(*if (preparing) intArrayOf(0) else intArrayOf(0, 1)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun commandIntent(action: String, request: Int) = PendingIntent.getService(this, request,
        Intent(this, BackgroundPlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun mediaAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun localized(source: String): String = UiTranslator.translate(source,
        AppCompatDelegate.getApplicationLocales().get(0)?.language ?: resources.configuration.locales[0].language)
}
