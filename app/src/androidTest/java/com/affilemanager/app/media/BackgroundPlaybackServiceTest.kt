package com.affilemanager.app.media

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BackgroundPlaybackServiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixtures get() = File(context.cacheDir, "background-native-tests").apply { mkdirs() }
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @After fun cleanup() {
        compose.runOnUiThread { BackgroundPlaybackService.stop(context) }
        compose.waitUntil(5_000) { BackgroundPlaybackService.state.value == null }
        fixtures.deleteRecursively()
    }

    @Test fun inAppPauseResumeAndStopControlTheRealPlayerAfterBackgrounding() {
        start(wave("controls.wav"))
        waitFor(BackgroundPlaybackPhase.PLAYING)
        compose.onNodeWithTag("background_toggle").assertIsDisplayed().performClick()
        waitFor(BackgroundPlaybackPhase.PAUSED)
        compose.onNodeWithTag("background_toggle").performClick()
        waitFor(BackgroundPlaybackPhase.PLAYING)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(BackgroundPlaybackPhase.PLAYING, BackgroundPlaybackService.state.value?.phase)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("background_stop").assertIsDisplayed().performClick()
        waitForStopped()
    }

    @Test fun notificationAndMediaSessionControlTheSameSession() {
        start(wave("system-controls.wav"))
        waitFor(BackgroundPlaybackPhase.PLAYING)
        val notification = requireNotNull(notification())
        @Suppress("DEPRECATION")
        val token = requireNotNull(notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
        val controller = MediaController(context, token)
        assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState?.state)
        controller.transportControls.pause()
        waitFor(BackgroundPlaybackPhase.PAUSED)
        controller.transportControls.play()
        waitFor(BackgroundPlaybackPhase.PLAYING)
        assertTrue(requireNotNull(notification()).actions.all { it.getIcon() != null })
        requireNotNull(notification()).actions.last().actionIntent.send()
        waitForStopped()
    }

    @Test fun stopQueuedDuringPreparationCannotBeUndoneByLatePreparedCallback() {
        val file = wave("cancel-prepare.wav")
        compose.runOnUiThread {
            BackgroundPlaybackService.play(context, Uri.fromFile(file), file.name, 0L, true, 1f, 0f)
            BackgroundPlaybackService.stop(context)
        }
        // Let both start commands and native callbacks reach the main loop before checking absence.
        compose.waitForIdle()
        compose.waitUntil(5_000) { BackgroundPlaybackService.state.value == null && notification() == null }
        val observer = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ observer.countDown() }, 700L)
        assertTrue(observer.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(BackgroundPlaybackService.state.value)
        assertNull(notification())
    }

    @Test fun naturalCompletionRemovesPlayerSessionAndNotification() {
        start(wave("completion.wav"), loop = false)
        waitFor(BackgroundPlaybackPhase.PLAYING)
        waitForStopped()
    }

    @Test fun unreadableOrCorruptReplacementStopsOldPlayerAndShowsDismissibleFailure() {
        start(wave("old-loop.wav"))
        waitFor(BackgroundPlaybackPhase.PLAYING)
        val corrupt = File(fixtures, "corrupt.wav").apply { writeText("not a WAV file") }
        start(corrupt)
        waitFor(BackgroundPlaybackPhase.ERROR)
        assertNull(notification())
        compose.onNodeWithTag("background_stop").assertIsDisplayed().performClick()
        waitForStopped()
        start(wave("recovered.wav"))
        waitFor(BackgroundPlaybackPhase.PLAYING)
    }

    @Test fun replacementAndRepeatedStopDoNotRestartThePreviousFile() {
        start(wave("first.wav"))
        waitFor(BackgroundPlaybackPhase.PLAYING)
        val second = wave("second.wav")
        start(second)
        compose.waitUntil(5_000) { BackgroundPlaybackService.state.value?.title == second.name &&
            BackgroundPlaybackService.state.value?.phase == BackgroundPlaybackPhase.PLAYING }
        compose.runOnUiThread { repeat(3) { BackgroundPlaybackService.stop(context) } }
        waitForStopped()
    }

    private fun start(file: File, loop: Boolean = true) = compose.runOnUiThread {
        BackgroundPlaybackService.play(context, Uri.fromFile(file), file.name, 0L, loop, 1f, 0f)
    }

    private fun waitFor(phase: BackgroundPlaybackPhase) = compose.waitUntil(8_000) {
        BackgroundPlaybackService.state.value?.phase == phase
    }

    private fun waitForStopped() = compose.waitUntil(8_000) {
        BackgroundPlaybackService.state.value == null && notification() == null
    }

    private fun notification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == BackgroundPlaybackService.NOTIFICATION_ID }?.notification

    private fun wave(name: String): File {
        val samples = 16_000
        val bytes = samples * 2
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        return File(fixtures, name).apply { writeBytes(buffer.array()) }
    }
}
