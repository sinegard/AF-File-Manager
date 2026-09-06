package com.affilemanager.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.affilemanager.app.ui.localization.uiText
import kotlin.math.roundToInt

/** Gestures affect this player/window only; no global brightness permission or system setting. */
@Composable
internal fun VideoGestureOverlay(volume: Float, onVolume: (Float) -> Unit, onTap: () -> Unit) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    var brightness by remember(window) { mutableFloatStateOf(window?.attributes?.screenBrightness?.takeIf { it >= 0f }
        ?: (android.provider.Settings.System.getInt(view.context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.01f, 1f)) }
    var adjustingVolume by remember { mutableStateOf<Boolean?>(null) }
    val latestVolume by rememberUpdatedState(volume)
    val latestOnVolume by rememberUpdatedState(onVolume)
    val latestOnTap by rememberUpdatedState(onTap)
    DisposableEffect(window) {
        val original = window?.attributes?.screenBrightness
        onDispose { if (original != null) window.attributes = window.attributes.apply { screenBrightness = original } }
    }
    Box(Modifier.fillMaxSize().testTag("video_gesture_surface")
        .pointerInput(Unit) { detectTapGestures(onTap = { latestOnTap() }) }
        .pointerInput(window) {
            detectVerticalDragGestures(
                onDragStart = { position -> adjustingVolume = when {
                    position.x >= size.width * .75f -> true
                    position.x <= size.width * .25f && window != null -> false
                    else -> null
                } },
                onVerticalDrag = { change, delta ->
                    val volumeSide = adjustingVolume
                    if (volumeSide != null) {
                        change.consume()
                        val difference = -delta / size.height.coerceAtLeast(1)
                        if (volumeSide) latestOnVolume((latestVolume + difference).coerceIn(0f, 1f))
                        else {
                            brightness = (brightness + difference).coerceIn(.01f, 1f)
                            window?.attributes = window.attributes.apply { screenBrightness = brightness }
                        }
                    }
                },
                onDragEnd = { adjustingVolume = null }, onDragCancel = { adjustingVolume = null },
            )
        }) {
        adjustingVolume?.let { volumeSide ->
            Text("${uiText(if (volumeSide) "Garsumas" else "Ryškumas")}: ${((if (volumeSide) volume else brightness) * 100).roundToInt()}%",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(if (volumeSide) Alignment.CenterStart else Alignment.CenterEnd)
                    .background(Color.Black.copy(alpha = .8f), MaterialTheme.shapes.medium).padding(10.dp)
                    .testTag(if (volumeSide) "video_volume_indicator" else "video_brightness_indicator"))
        }
    }
}
