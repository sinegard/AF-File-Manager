package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.affilemanager.app.ui.theme.AfSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.media.BackgroundPlaybackPhase
import com.affilemanager.app.media.BackgroundPlaybackService
import com.affilemanager.app.media.BackgroundPlaybackState
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
fun BackgroundPlaybackBar(stopOnly: Boolean = false) {
    val state by BackgroundPlaybackService.state.collectAsStateWithLifecycle()
    val current = state ?: return
    val context = LocalContext.current
    var commandFailed by remember(current.uri) { mutableStateOf(false) }
    BackgroundPlaybackControls(current,
        onToggle = { commandFailed = runCatching { BackgroundPlaybackService.toggle(context) }.isFailure },
        onStop = { commandFailed = runCatching { BackgroundPlaybackService.stop(context) }.isFailure },
        commandFailed = commandFailed, stopOnly = stopOnly,
        onPrevious = { commandFailed = runCatching { BackgroundPlaybackService.skip(context, -1) }.isFailure },
        onNext = { commandFailed = runCatching { BackgroundPlaybackService.skip(context, 1) }.isFailure })
}

@Composable
internal fun BackgroundPlaybackControls(
    state: BackgroundPlaybackState,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    commandFailed: Boolean = false,
    stopOnly: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("background_playback_bar"),
        ) {
            val stacked = !stopOnly && state.canSkip && (maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f)
            val title: @Composable () -> Unit = {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!stopOnly) BackgroundArtwork(state.uri)
                Column(modifier = Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                LText(when {
                    commandFailed || state.phase == BackgroundPlaybackPhase.ERROR -> "Foninio atkūrimo paleisti nepavyko"
                    state.phase == BackgroundPlaybackPhase.PREPARING -> "Ruošiamas foninis atkūrimas"
                    state.phase == BackgroundPlaybackPhase.PAUSED -> "Pauzė"
                    else -> "Atkuriama fone"
                }, style = MaterialTheme.typography.bodySmall)
                }
              }
            }
            val controls: @Composable () -> Unit = {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.active && !stopOnly) {
                if (state.canSkip) IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp).testTag("background_previous")) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = uiText("Ankstesnis failas"))
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)
                    .testTag("background_toggle").combinedClickable(role = Role.Button,
                        enabled = state.phase != BackgroundPlaybackPhase.PREPARING, onClick = onToggle,
                        onLongClickLabel = uiText("Sustabdyti"), onLongClick = onStop)) {
                    CircularProgressIndicator(progress = { if (state.durationMillis > 0) (state.positionMillis.toFloat() / state.durationMillis).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.size(44.dp), strokeWidth = 2.dp)
                    Icon(if (state.phase == BackgroundPlaybackPhase.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = uiText(if (state.phase == BackgroundPlaybackPhase.PLAYING) "Pauzė" else "Tęsti"))
                }
                if (state.canSkip) IconButton(onClick = onNext, modifier = Modifier.size(48.dp).testTag("background_next")) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = uiText("Kitas failas"))
                }
            }
            FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp).testTag("background_stop")) {
                Icon(if (state.active) Icons.Rounded.Stop else Icons.Rounded.Close,
                    contentDescription = uiText(if (state.active) "Sustabdyti" else "Uždaryti"))
            }
              }
            }
            if (stacked) Column {
                title()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) { title() }
                controls()
            }
        }
    }
}

@Composable
private fun BackgroundArtwork(uri: String) {
    val context = LocalContext.current
    val artwork by produceState<android.graphics.Bitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, android.net.Uri.parse(uri))
                    retriever.embeddedPicture?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes ->
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } finally { retriever.release() }
            }.getOrNull()
        }
    }
    val bitmap = artwork
    if (bitmap == null) Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(36.dp))
    else Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp))
}
