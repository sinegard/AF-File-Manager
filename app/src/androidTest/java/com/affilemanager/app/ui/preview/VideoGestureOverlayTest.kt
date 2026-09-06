package com.affilemanager.app.ui.preview

import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoGestureOverlayTest {
    @get:Rule val compose = createComposeRule()
    @Test fun oppositeEdgeIndicatorsControlOnlyPlayerVolumeAndDialogBrightnessAndRestoreOnClose() {
        val show = mutableStateOf(true)
        var window: Window? = null
        var initialBrightness = -1f
        var volume = .5f
        compose.setContent { MaterialTheme { if (show.value) Dialog(onDismissRequest = {}) {
            val owner = (LocalView.current.parent as DialogWindowProvider).window
            SideEffect { if (window == null) { window = owner; initialBrightness = owner.attributes.screenBrightness } }
            var gain by remember { mutableFloatStateOf(volume) }
            Box(Modifier.size(300.dp, 360.dp)) { VideoGestureOverlay(gain, { gain = it; volume = it }, {}) }
        } } }
        compose.onNodeWithTag("video_gesture_surface").performTouchInput {
            down(Offset(width * .9f, height * .8f)); moveTo(Offset(width * .9f, height * .4f), 300)
        }
        compose.onNodeWithTag("video_volume_indicator").assertIsDisplayed()
        compose.runOnIdle { assertTrue(volume > .7f); assertEquals(initialBrightness, window!!.attributes.screenBrightness) }
        compose.onNodeWithTag("video_gesture_surface").performTouchInput { up() }
        compose.onNodeWithTag("video_volume_indicator").assertDoesNotExist()
        compose.onNodeWithTag("video_gesture_surface").performTouchInput {
            down(Offset(width * .1f, height * .8f)); moveTo(Offset(width * .1f, height * .3f), 300)
        }
        compose.onNodeWithTag("video_brightness_indicator").assertIsDisplayed()
        compose.runOnIdle { assertTrue(window!!.attributes.screenBrightness >= .01f) }
        compose.onNodeWithTag("video_gesture_surface").performTouchInput { up() }
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        assertEquals(initialBrightness, window!!.attributes.screenBrightness)
    }
}
