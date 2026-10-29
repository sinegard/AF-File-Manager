package com.affilemanager.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.media.BackgroundPlaybackService
import com.affilemanager.app.media.editing.MediaTestFixtures
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MediaRotationLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun playingAudioSurvivesRecreationAndLandscapeWithoutStartingBackgroundService() = checkPlayback(false, true)
    @Test fun pausedAudioKeepsPositionAndControlsThroughRotation() = checkPlayback(false, false)
    @Test fun playingVideoSurvivesRecreationAndLandscapeWithoutStartingBackgroundService() = checkPlayback(true, true)
    @Test fun pausedVideoKeepsPositionAndControlsThroughRotation() = checkPlayback(true, false)

    private fun checkPlayback(video: Boolean, play: Boolean) {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "rotation-${System.nanoTime()}").apply { check(mkdirs()) }
        val file = File(root, if (video) "rotation.mp4" else "rotation.wav")
        if (video) MediaTestFixtures.video(file, audio = false, frameTimesUs = (0..40).map { it * 500_000L })
        else MediaTestFixtures.wav(file, durationMs = 30_000)
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val prefix = if (video) "video" else "audio"
        fun ready() = compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("${prefix}_play_pause").performScrollTo().assertIsEnabled(); true }.getOrDefault(false)
        }
        fun progress() = compose.onNodeWithTag("${prefix}_seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        fun playing() = compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        try {
            compose.runOnUiThread { vm.open(app.graph.localFiles.toEntry(file)) }
            ready()
            compose.onNodeWithTag("${prefix}_seek").performSemanticsAction(SemanticsActions.SetProgress) { it(.35f) }
            compose.onNodeWithText("1.0×").performScrollTo().performClick()
            compose.onNodeWithText("1.5×").performClick()
            compose.onNodeWithText("Repeat").performScrollTo().performClick()
            compose.onNodeWithTag("${prefix}_volume").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
            if (play) compose.onNodeWithTag("${prefix}_play_pause").performScrollTo().performClick()
            compose.waitUntil(5_000) { progress() > .28f && playing() == play }
            val before = progress()
            compose.activityRule.scenario.recreate()
            ready()
            compose.waitUntil(5_000) { playing() == play && progress() >= before - .08f }
            compose.onNodeWithText("1.5×").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Repeat").performScrollTo().assertIsNotSelected()
            assertEquals(.4f, compose.onNodeWithTag("${prefix}_volume").fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, .02f)
            assertNull(BackgroundPlaybackService.state.value)
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ready()
            compose.waitUntil(5_000) { playing() == play }
            compose.onNodeWithTag("${prefix}_background_start").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("${prefix}_volume").performScrollTo().assertIsDisplayed()
            val bounds = compose.onNodeWithTag("${prefix}_player").fetchSemanticsNode().boundsInRoot
            if (!video) {
                val art = compose.onNodeWithTag("audio_artwork").fetchSemanticsNode().boundsInRoot
                assertTrue("Artwork must fit within the short player", art.height <= bounds.height && art.width <= bounds.width * .4f)
            } else {
                val footer = compose.onNodeWithTag("video_controls_footer").fetchSemanticsNode().boundsInRoot
                assertEquals("Controls must reach the bottom, without an unused gap", bounds.bottom, footer.bottom, 2f)
                assertTrue(compose.onNodeWithTag("video_surface").fetchSemanticsNode().boundsInRoot.height > 0f)
            }
            val capture = File(root, "$prefix-landscape-$play.png")
            // Compose's clock does not advance API26's separate system-window
            // rotation animation. Snapshot the settled window, not that transition.
            if (android.os.Build.VERSION.SDK_INT < 28) android.os.SystemClock.sleep(1_200L)
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                compose.onNodeWithTag("${prefix}_player").captureToImage().asAndroidBitmap()
            } else requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            capture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertNull(BackgroundPlaybackService.state.value)
            if (play) compose.onNodeWithTag("${prefix}_play_pause").performScrollTo().performClick()
            compose.onNodeWithTag("${prefix}_seek").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            compose.waitUntil(5_000) { progress() < .02f && !playing() }
            compose.activityRule.scenario.recreate()
            ready()
            compose.waitUntil(5_000) { progress() < .02f && !playing() }
            compose.runOnUiThread { vm.closePreview() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("${prefix}_player").fetchSemanticsNodes().isEmpty() }
            compose.runOnUiThread { vm.open(app.graph.localFiles.toEntry(file)) }
            ready()
            assertFalse("Opening a fresh preview must not inherit prior autoplay", playing())
        } finally {
            compose.runOnUiThread { vm.closePreview(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            // Retain screenshots for host inspection; only the uniquely owned media fixtures are disposable.
            file.delete()
        }
    }
}
