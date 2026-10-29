package com.affilemanager.app.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Only playback intent is saved; platform players and their Activity are always released. */
internal class ForegroundPlaybackState(
    val sourceKey: String,
    positionMillis: Long = 0L,
    playRequested: Boolean = false,
    loopEnabled: Boolean = true,
    speed: Float = 1f,
    volume: Float = 1f,
) {
    val position = mutableLongStateOf(positionMillis.coerceAtLeast(0L))
    val requested = mutableStateOf(playRequested)
    val loop = mutableStateOf(loopEnabled)
    val speed = mutableFloatStateOf(speed.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f)
    val volume = mutableFloatStateOf(volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)

    companion object {
        fun saver(sourceKey: String) = listSaver<ForegroundPlaybackState, Any>(
            save = { listOf(it.sourceKey, it.position.longValue, it.requested.value, it.loop.value,
                it.speed.floatValue, it.volume.floatValue) },
            restore = { values ->
                if (values[0] != sourceKey) null else ForegroundPlaybackState(
                    sourceKey, values[1] as Long, values[2] as Boolean, values[3] as Boolean,
                    values[4] as Float, values[5] as Float,
                )
            },
        )
    }
}

@Composable
internal fun rememberForegroundPlayback(
    sourceKey: String,
    autoPlay: Boolean = false,
    loop: Boolean = true,
    speed: Float = 1f,
): ForegroundPlaybackState = rememberSaveable(sourceKey, saver = ForegroundPlaybackState.saver(sourceKey)) {
    ForegroundPlaybackState(sourceKey, playRequested = autoPlay, loopEnabled = loop, speed = speed)
}
