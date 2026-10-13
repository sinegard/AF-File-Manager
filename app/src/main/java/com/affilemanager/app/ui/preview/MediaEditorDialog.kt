@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.ui.preview

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.affilemanager.app.media.editing.*
import com.affilemanager.app.ui.components.AfActionRow
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.ImageCropStore
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfButton as Button
import com.affilemanager.app.ui.theme.AfFilterChip as FilterChip
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

internal enum class MediaEditorMode { TRIM, EXTRACT_AUDIO, COMPRESS }

/** The only input is an existing private edit session. Saving uses the shared Save As browser. */
@Composable
internal fun MediaEditorDialog(
    source: PreviewSource.Working,
    mode: MediaEditorMode,
    onDismiss: () -> Unit,
    onExported: (MediaExportFile) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = remember(source.key) { source.uri(context) }
    val input by produceState<Result<MediaEditInfo>?>(null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { MediaExportEngine.info(context, uri, source.localFile.length()) } }
    }
    val info = input?.getOrNull()
    var format by remember(source.key, mode) { mutableStateOf(if (mode == MediaEditorMode.EXTRACT_AUDIO || source.kind == com.affilemanager.app.model.EntryKind.AUDIO) MediaExportFormat.M4A else MediaExportFormat.MP4) }
    var name by remember(source.key) { mutableStateOf(source.name.substringBeforeLast('.', source.name) + "-edited") }
    var interval by remember(info) { mutableStateOf(0f..(info?.durationMs?.toFloat() ?: 1000f)) }
    var crop by remember(source.key) { mutableStateOf(MediaCrop()) }
    var cropEnabled by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf<Int?>(null) }
    var fps by remember { mutableStateOf<Int?>(null) }
    var gifHeight by remember { mutableStateOf<Int?>(480) }
    var gifFps by remember { mutableIntStateOf(10) }
    var bitrate by remember { mutableStateOf<Int?>(if (mode == MediaEditorMode.COMPRESS) 2_000_000 else null) }
    var audioBitrate by remember { mutableIntStateOf(192_000) }
    var speed by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var tags by remember { mutableStateOf(MediaTags()) }
    var tagsEdited by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var coverLoading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    val editorScroll = rememberScrollState()
    LaunchedEffect(failure, input?.exceptionOrNull()) {
        if (failure != null || input?.isFailure == true) editorScroll.animateScrollTo(0)
    }
    LaunchedEffect(source.key) {
        try { val original = withContext(Dispatchers.IO) { MediaExportEngine.tags(context, uri) }; if (!tagsEdited) tags = original }
        catch (error: Throwable) { if (error is CancellationException) throw error; failure = error }
    }
    val options = MediaEditOptions(format, interval.start.toLong(), interval.endInclusive.toLong(), speed, pitch, volume,
        if (cropEnabled && format.video) crop else MediaCrop(),
        if (format == MediaExportFormat.GIF) gifHeight else if (format.video) height else null,
        if (format == MediaExportFormat.GIF) gifFps else if (format.video) fps else null,
        if (format == MediaExportFormat.MP4) bitrate else null, audioBitrate, tags)
    val optionError = info?.let { runCatching { options.validate(it); MediaEditNames.outputName(name, format) }.exceptionOrNull() }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected != null) scope.launch {
            coverLoading = true
            try { cover = withContext(Dispatchers.IO) {
                val jobContext = coroutineContext
                val bitmap = ImageCropStore.load(context, selected) { jobContext.ensureActive() }
                var scaled: Bitmap? = null
                try {
                    val factor = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
                    scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
                    ByteArrayOutputStream().use { output ->
                        require(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output))
                        require(output.size() <= 2 * 1024 * 1024) { "Paveikslėlis per didelis" }
                        output.toByteArray()
                    }
                } finally { if (scaled !== bitmap) scaled?.recycle(); bitmap.recycle() }
            } } catch (error: Throwable) { if (error is CancellationException) throw error; failure = error }
            finally { coverLoading = false }
        }
    }
    val dismiss: () -> Unit = { exportJob?.cancel(); onDismiss() }
    AfModalDialog(title = when (mode) { MediaEditorMode.TRIM -> "Kirpti mediją"; MediaEditorMode.EXTRACT_AUDIO -> "Išgauti garsą"; MediaEditorMode.COMPRESS -> "Suspausti vaizdo įrašą" },
        icon = Icons.Rounded.Crop, onDismissRequest = dismiss, expandedContent = true,
        modifier = Modifier.testTag("media_editor"), actions = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(onClick = dismiss, modifier = Modifier.testTag("media_edit_cancel")) { LText("Atšaukti") }
            Button(enabled = info != null && optionError == null && !exporting && !coverLoading,
                modifier = Modifier.testTag("media_edit_export"), onClick = {
                    val metadata = info ?: return@Button
                    focus.clearFocus(); keyboard?.hide()
                    exportJob = scope.launch {
                        exporting = true; failure = null; progress = null
                        var owned: MediaExportFile? = null
                        try {
                            owned = MediaExportEngine(context).export(source.localFile, uri, metadata, options, name, if (format == MediaExportFormat.GIF) null else cover) { progress = it }
                            ensureActive()
                            if (onExported(requireNotNull(owned))) { owned = null; onDismiss() }
                            else error("Redaguojama kopija pasikeitė")
                        } catch (error: Throwable) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            failure = if (error is TimeoutCancellationException) IllegalStateException("Medijos konvertuoti nepavyko", error) else error
                        }
                        finally {
                            try { withContext(NonCancellable + Dispatchers.IO) { owned?.file?.let { if (MediaExportEngine.ownedFile(context, it)) it.delete() } } }
                            finally { exporting = false; progress = null; exportJob = null }
                        }
                    }
                }) { LText("Paruošti ir išsaugoti kaip") }
        }) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxSize().verticalScroll(editorScroll).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LText("Originalas nepakeičiamas. Po paruošimo pasirinkite telefono arba serverio aplanką.", style = MaterialTheme.typography.bodySmall)
            if (input == null) CircularProgressIndicator()
            (failure ?: input?.exceptionOrNull())?.let { MediaEditError(it) }
            if (info != null) {
                if (!exporting) MediaEditPreview(uri, info, options, cropEnabled && format.video, crop, { crop = it }, Modifier.fillMaxWidth())
                else {
                    val displayedProgress = progress
                    if (displayedProgress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { displayedProgress.toFloat() / 100 }, modifier = Modifier.fillMaxWidth())
                    LText("Ruošiama…")
                    progress?.let { Text("$it %") }
                }
                LText("Kirpimo intervalas")
                Text("${mediaTime(interval.start.toLong())} – ${mediaTime(interval.endInclusive.toLong())}")
                RangeSlider(interval, { interval = it }, enabled = !exporting, valueRange = 0f..info.durationMs.toFloat(), modifier = Modifier.testTag("media_trim_range"))
                AfActionRow {
                    val choices = if (info.hasVideo) MediaExportFormat.entries else listOf(MediaExportFormat.M4A, MediaExportFormat.WAV)
                    choices.filter { it.video || info.hasAudio }.forEach { choice ->
                        FilterChip(selected = format == choice, enabled = !exporting, onClick = {
                            focus.clearFocus(); keyboard?.hide()
                            format = choice
                        }, label = { Text(choice.name) }, modifier = Modifier.testTag("media_format_${choice.name}"))
                    }
                }
                OutlinedTextField(name, { name = it.take(220) }, enabled = !exporting, label = { LText("Failo pavadinimas") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("media_output_name"), suffix = { Text(".${format.extension}") })
                if (format.video) {
                    AfActionRow {
                        Checkbox(cropEnabled, { cropEnabled = it }, enabled = !exporting, modifier = Modifier.testTag("media_crop_enabled"))
                        LText("Apkirpti")
                        TextButton(onClick = { crop = MediaCrop() }, enabled = !exporting) { LText("Atkurti") }
                    }
                    if (cropEnabled) LText("Vilkite rėmelį arba jo apatinį kampą.", style = MaterialTheme.typography.bodySmall)
                    LText("Išvesties raiška")
                    AfActionRow {
                        (listOf<Int?>(null, 240, 360, 480) + if (format != MediaExportFormat.GIF) listOf(720, 1080, 1440, 2160) else emptyList()).forEach { size ->
                            FilterChip(options.height == size, { if (format == MediaExportFormat.GIF) gifHeight = size else height = size }, enabled = !exporting,
                                modifier = Modifier.testTag("media_height_${size ?: "original"}"), label = { if (size == null) LText("Originali") else Text("${size}p") })
                        }
                    }
                    LText("Kadrų sparta")
                    AfActionRow {
                        (if (format == MediaExportFormat.GIF) listOf(5, 10, 15) else listOf<Int?>(null, 15, 24, 30, 60)).forEach { rate ->
                            FilterChip(options.frameRate == rate, { if (format == MediaExportFormat.GIF) gifFps = requireNotNull(rate) else fps = rate }, enabled = !exporting,
                                modifier = Modifier.testTag("media_fps_${rate ?: "original"}"), label = { if (rate == null) LText("Originali") else Text(if (format == MediaExportFormat.GIF) "$rate fps" else "≤ $rate fps") })
                        }
                    }
                    if (format == MediaExportFormat.GIF) LText("GIF: iki 60 sekundžių, 600 kadrų ir 480 pikselių kraštinė; be garso.", style = MaterialTheme.typography.bodySmall)
                    else {
                        LText("Vaizdo bitų sparta")
                        AfActionRow { listOf<Int?>(null, 500_000, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000).forEach { rate ->
                            FilterChip(bitrate == rate, { bitrate = rate }, enabled = !exporting, label = { if (rate == null) LText("Automatinė") else Text("${decimal(rate / 1_000_000f)} Mbps") })
                        } }
                        LText("Mažesnis dydis gali sumažinti kokybę. Dydis yra apytikslis, ne garantuotas.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (format == MediaExportFormat.GIF) AfActionRow { LText("Dydis"); Text("≤ ${decimal(GifSequenceWriter.MAX_BYTES / (1024f * 1024))} MiB") }
                }
                MediaValueSlider("Greitis", speed, .25f..4f, !exporting) { speed = it }
                if (format != MediaExportFormat.GIF && info.hasAudio) {
                    MediaValueSlider("Garso aukštis", pitch, .5f..2f, !exporting) { pitch = it }
                    MediaValueSlider("Garsumas", volume, 0f..2f, !exporting) { volume = it }
                    if (volume > 1f) LText("Garsinimas virš 100 % pritaikomas tik išvesties failui.", style = MaterialTheme.typography.bodySmall)
                    if (format != MediaExportFormat.WAV) {
                        LText("Garso bitų sparta")
                        AfActionRow { listOf(32, 64, 128, 192, 256, 320).forEach { kbps ->
                            FilterChip(audioBitrate == kbps * 1000, { audioBitrate = kbps * 1000 }, enabled = !exporting, label = { Text("$kbps kbps") })
                        } }
                    }
                }
                options.estimatedBytes(info)?.let { bytes -> AfActionRow { LText("Apytikslis dydis"); Text("${decimal(bytes / (1024f * 1024))} MiB") } }
                LText("Redaguojami failai: iki 256 MiB ir 1 valandos. Raiška priklauso nuo įrenginio kodavimo galimybių.", style = MaterialTheme.typography.bodySmall)
                if (format != MediaExportFormat.GIF) {
                    TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); showTags = !showTags }, enabled = !exporting) { LText("Metaduomenys") }
                    if (showTags) {
                        MediaTagFields(tags, !exporting) { tags = it; tagsEdited = true }
                        AfActionRow {
                            TextButton(onClick = { coverPicker.launch("image/*") }, enabled = !exporting && !coverLoading) { LText("Pasirinkti viršelį") }
                            if (cover != null) TextButton(onClick = { cover = null }, enabled = !exporting) { LText("Pašalinti viršelį") }
                        }
                        if (coverLoading) CircularProgressIndicator(Modifier.size(24.dp))
                        if (cover != null) LText("Viršelis pasirinktas")
                        LText("Kai kurie grotuvai nerodo visų metaduomenų.", style = MaterialTheme.typography.bodySmall)
                        LText("Pasirinkite viršelį iš naujo. Ne visi pradinio failo metaduomenys perkeliami automatiškai.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                optionError?.let { MediaEditError(it) }
            }
        }
    }
}

@Composable
private fun MediaValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onValue: (Float) -> Unit) {
    AfActionRow { LText(label); Text("${decimal(value)}×") }
    Slider(value, onValue, valueRange = range, enabled = enabled, modifier = Modifier.testTag("media_value_$label"))
}

@Composable
private fun MediaTagFields(tags: MediaTags, enabled: Boolean, onChange: (MediaTags) -> Unit) {
    val fields = listOf<Triple<String, String, (String) -> MediaTags>>(
        Triple("Pavadinimas", tags.title) { tags.copy(title = it) }, Triple("Atlikėjas", tags.artist) { tags.copy(artist = it) },
        Triple("Albumas", tags.album) { tags.copy(album = it) }, Triple("Albumo atlikėjas", tags.albumArtist) { tags.copy(albumArtist = it) },
        Triple("Kompozitorius", tags.composer) { tags.copy(composer = it) }, Triple("Leidėjas", tags.publisher) { tags.copy(publisher = it) },
        Triple("Data", tags.date) { tags.copy(date = it) }, Triple("Žanras", tags.genre) { tags.copy(genre = it) },
        Triple("Dainos tekstas", tags.lyrics) { tags.copy(lyrics = it) }, Triple("Komentaras", tags.comment) { tags.copy(comment = it) })
    fields.forEach { (label, value, change) ->
        OutlinedTextField(value, { next -> if (next.toByteArray(Charsets.UTF_8).size <= if (label == "Dainos tekstas") 32768 else 4096) onChange(change(next)) },
            enabled = enabled, label = { LText(label) }, singleLine = label != "Dainos tekstas", maxLines = if (label == "Dainos tekstas") 4 else 1, modifier = Modifier.fillMaxWidth().testTag("media_tag_$label"))
    }
}

@Composable
internal fun MediaEditPreview(uri: Uri, info: MediaEditInfo, options: MediaEditOptions, cropping: Boolean, crop: MediaCrop, onCrop: (MediaCrop) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(uri) { mutableStateOf<Throwable?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(options.startMs) }
    val memoryError = remember(info) { if (info.hasVideo) runCatching { requireVideoMemory(info.width, info.height) }.exceptionOrNull() else null }
    if (memoryError != null) { MediaEditError(memoryError); return }
    val player = remember(uri) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(localMediaSource(context, uri)))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 1500, 250, 500).setTargetBufferBytes(4 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setUsePlatformDiagnostics(false).build()
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlayerError(failure: PlaybackException) { error = failure }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        player.addListener(listener); lifecycle.addObserver(observer)
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.seekTo(options.startMs)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, options.speed, options.pitch, options.volume) {
        player.playbackParameters = PlaybackParameters(options.speed, options.pitch)
        player.volume = options.volume.coerceAtMost(1f) // Amplification above unity is applied by the export processor.
    }
    LaunchedEffect(player, options.startMs, options.endMs) {
        player.seekTo(options.startMs)
        while (true) {
            if (player.currentPosition >= options.endMs || player.currentPosition < options.startMs) player.seekTo(options.startMs)
            position = player.currentPosition.coerceIn(options.startMs, options.endMs)
            delay(100)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        error?.let { MediaEditError(it) }
        if (info.hasVideo) BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            val ratio = info.width.toFloat() / info.height
            val width = minOf(maxWidth, maxHeight * ratio)
            Box(Modifier.width(width).height(width / ratio)) {
                AndroidView(factory = { TextureView(it).also(player::setVideoTextureView) }, modifier = Modifier.fillMaxSize().testTag("media_edit_video"))
                if (cropping) MediaCropOverlay(crop, onCrop, Modifier.fillMaxSize())
            }
        }
        AfActionRow {
            TextButton(onClick = { if (playing) player.pause() else player.play() }, enabled = error == null, modifier = Modifier.testTag("media_edit_play")) { LText(if (playing) "Pristabdyti" else "Leisti") }
            Text("${mediaTime(position)} / ${mediaTime(options.endMs)}")
        }
        if (options.endMs - options.startMs >= 100) Slider(position.toFloat(), { player.seekTo(it.toLong()); position = it.toLong() }, valueRange = options.startMs.toFloat()..options.endMs.toFloat(), modifier = Modifier.testTag("media_edit_seek"))
    }
}

@Composable
internal fun MediaCropOverlay(crop: MediaCrop, onCrop: (MediaCrop) -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(crop)
    val changed by rememberUpdatedState(onCrop)
    val outline = MaterialTheme.colorScheme.primary
    Canvas(modifier.testTag("media_crop_canvas").pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var working = latest
            val end = Offset((working.x + working.width) * size.width, (working.y + working.height) * size.height)
            val resizing = (down.position - end).getDistance() <= 32.dp.toPx()
            if (!resizing && (down.position.x !in working.x * size.width..end.x || down.position.y !in working.y * size.height..end.y)) return@awaitEachGesture
            down.consume(); var previous = down.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                val delta = change.position - previous; previous = change.position; change.consume()
                working = if (resizing) working.resize(delta.x / size.width, delta.y / size.height) else working.move(delta.x / size.width, delta.y / size.height)
                changed(working)
            }
        }
    }) {
        val x = crop.x * size.width; val y = crop.y * size.height; val w = crop.width * size.width; val h = crop.height * size.height
        val shade = Color.Black.copy(alpha = .45f)
        drawRect(shade, Offset.Zero, Size(size.width, y)); drawRect(shade, Offset(0f, y + h), Size(size.width, size.height - y - h))
        drawRect(shade, Offset(0f, y), Size(x, h)); drawRect(shade, Offset(x + w, y), Size(size.width - x - w, h))
        drawRect(outline, Offset(x, y), Size(w, h), style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 11.dp.toPx(), Offset(x + w, y + h)); drawCircle(outline, 8.dp.toPx(), Offset(x + w, y + h))
    }
}

private fun decimal(value: Float) = String.format(Locale.getDefault(), "%.2f", value)
private fun mediaTime(value: Long) = String.format(Locale.ROOT, "%02d:%02d.%03d", value / 60_000, value / 1000 % 60, value % 1000)

@Composable
private fun MediaEditError(error: Throwable) {
    Column(Modifier.fillMaxWidth()) {
        // A native codec/container exception is diagnostic data, not translated
        // interface text. Keep known AF explanations and localize unknown failures.
        val knownMessage = error.message?.takeIf(UiTranslator::hasKnownUiEntry)
        LText(if (error is PlaybackException) "Failo peržiūros sukurti nepavyko" else knownMessage ?: "Medijos konvertuoti nepavyko", color = MaterialTheme.colorScheme.error)
        if (knownMessage == null && error !is PlaybackException) Text("AF_MEDIA_${error.javaClass.simpleName.uppercase(Locale.ROOT)}", style = MaterialTheme.typography.bodySmall)
        if (error is PlaybackException) Text(error.errorCodeName, style = MaterialTheme.typography.bodySmall)
        (error.cause as? androidx.media3.transformer.ExportException)?.let { Text(it.errorCodeName, style = MaterialTheme.typography.bodySmall) }
        if (error.cause is TimeoutCancellationException) Text(MediaExportEngine.TIMEOUT_CODE, style = MaterialTheme.typography.bodySmall)
    }
}
