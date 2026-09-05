package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.media.BackgroundPlaybackPhase
import com.affilemanager.app.media.BackgroundPlaybackService
import com.affilemanager.app.media.BackgroundPlaybackState
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
fun BackgroundPlaybackBar() {
    val state by BackgroundPlaybackService.state.collectAsStateWithLifecycle()
    val current = state ?: return
    val context = LocalContext.current
    var commandFailed by remember(current) { mutableStateOf(false) }
    BackgroundPlaybackControls(current,
        onToggle = { commandFailed = runCatching { BackgroundPlaybackService.toggle(context) }.isFailure },
        onStop = { commandFailed = runCatching { BackgroundPlaybackService.stop(context) }.isFailure },
        commandFailed = commandFailed)
}

@Composable
internal fun BackgroundPlaybackControls(
    state: BackgroundPlaybackState,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    commandFailed: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("background_playback_bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                LText(when {
                    commandFailed || state.phase == BackgroundPlaybackPhase.ERROR -> "Foninio atkūrimo paleisti nepavyko"
                    state.phase == BackgroundPlaybackPhase.PREPARING -> "Ruošiamas foninis atkūrimas"
                    state.phase == BackgroundPlaybackPhase.PAUSED -> "Pauzė"
                    else -> "Atkuriama fone"
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (state.active) {
                IconButton(onClick = onToggle, enabled = state.phase != BackgroundPlaybackPhase.PREPARING,
                    modifier = Modifier.size(48.dp).testTag("background_toggle")) {
                    Icon(if (state.phase == BackgroundPlaybackPhase.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = uiText(if (state.phase == BackgroundPlaybackPhase.PLAYING) "Pauzė" else "Tęsti"))
                }
            }
            FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp).testTag("background_stop")) {
                Icon(if (state.active) Icons.Rounded.Stop else Icons.Rounded.Close,
                    contentDescription = uiText(if (state.active) "Sustabdyti" else "Uždaryti"))
            }
        }
    }
}
