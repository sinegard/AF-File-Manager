@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WavMuxer
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.transformer.*
import com.affilemanager.app.editing.EditLimits
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MediaExportFile(val file: File, val displayName: String, val format: MediaExportFormat, val durationMs: Long,
    val sourceFile: File, val sourceSha256: String)

/** One optional export at a time; native codecs never run on the file browser's IO workers. */
class MediaExportEngine(private val context: Context) {
    companion object {
        const val TIMEOUT_CODE = "AF_MEDIA_EXPORT_TIMEOUT"
        fun ownedFile(context: Context, file: File) = MediaExportStorage.ownedFile(context, file)
        /** UI callers retain cancellation ownership even when platform parsing needs native fallback. */
        suspend fun readInfo(context: Context, uri: Uri, source: File): MediaEditInfo = withContext(Dispatchers.IO) {
            try {
                if(!source.extension.equals("wma",true) && !NativeMediaProbe.isAsf(source)) return@withContext info(context,uri,source.length())
            }catch(error:Exception){if(error is CancellationException)throw error}
            MediaWorkspace.create(context,1024L*1024).use {NativeMediaProbe.read(context,source,it).mediaInfo(source.length())}
        }
        suspend fun readTags(context: Context, source: File): MediaTags = withContext(Dispatchers.IO) {
            MediaWorkspace.create(context,1024L*1024).use {NativeMediaProbe.read(context,source,it).editableTags()}
        }
        fun info(context: Context, uri: Uri, size: Long, source: File? = null): MediaEditInfo {
            require(uri.scheme in setOf("content", "file")) { "Netinkamas kelias" }
            if (source!=null && (source.extension.equals("wma", true) || NativeMediaProbe.isAsf(source))) return NativeMediaProbe.fallback(context, source).mediaInfo(size)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, uri)
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val audioFormat = MediaExtractor().let { extractor ->
                    try {
                        extractor.setDataSource(context, uri, null)
                        (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull {
                            it.getString("mime")?.startsWith("audio/") == true
                        }
                    } finally { extractor.release() }
                }
                return MediaEditInfo(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                    if (rotation % 180 == 0) width else height, if (rotation % 180 == 0) height else width,
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes", size,
                    audioFormat?.takeIf { it.containsKey("sample-rate") }?.getInteger("sample-rate"),
                    audioFormat?.takeIf { it.containsKey("channel-count") }?.getInteger("channel-count"))
            } catch (error: Exception) {
                if(source == null)throw error
                return NativeMediaProbe.fallback(context,source).mediaInfo(size)
            } finally { reader.release() }
        }
        fun tags(context: Context, uri: Uri, source: File? = null): MediaTags {
            if(source != null)return NativeMediaProbe.fallback(context,source).editableTags()
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, uri)
                fun text(key: Int) = reader.extractMetadata(key).orEmpty().replace("\u0000", "").take(1024)
                return MediaTags(title = text(MediaMetadataRetriever.METADATA_KEY_TITLE), artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM), albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    composer = text(MediaMetadataRetriever.METADATA_KEY_COMPOSER), date = text(MediaMetadataRetriever.METADATA_KEY_YEAR), genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE))
            } finally { reader.release() }
        }
    }

    suspend fun export(source: File, uri: Uri, info: MediaEditInfo, options: MediaEditOptions, name: String,
        cover: ByteArray? = null, onProgress: (Int?) -> Unit = {}): MediaExportFile {
        options.validate(info)
        if (info.hasVideo) requireVideoMemory(info.width, info.height)
        require(source.isFile && source.length() == info.sourceBytes && uri.scheme in setOf("content", "file")) { "Šaltinis pasikeitė" }
        require(if (uri.scheme == "file") File(requireNotNull(uri.path)).canonicalFile == source.canonicalFile
            else uri == FileProvider.getUriForFile(context, "${context.packageName}.files", source)) { "Šaltinis pasikeitė" }
        require(cover == null || cover.size <= 2 * 1024 * 1024)
        require(MediaExportStorage.admission.tryAcquire()) { "Jau vyksta kita medijos operacija" }
        var output: File? = null
        var workspace: MediaWorkspace? = null
        var succeeded = false
        try {
            val exported = withTimeout(30 * 60_000L) {
                val root = withContext(Dispatchers.IO) {
                    MediaExportStorage.cleanOnce(context)
                    File(context.cacheDir, "media-exports").apply {
                        require(isDirectory || mkdirs())
                        require(usableSpace >= EditLimits.MAX_FILE_BYTES + EditLimits.MIN_FREE_BYTES) { "Nepakanka laisvos vietos" }
                        require((list()?.size ?: 0) < 32) { "Laikinų failų riba pasiekta" }
                    }
                }
                val resultFile = withContext(Dispatchers.IO) { File.createTempFile("af-export-", ".${options.format.extension}", root) }
                output = resultFile
                val jobContext = currentCoroutineContext()
                val before = withContext(Dispatchers.IO) { sha(source) { jobContext.ensureActive() } }
                val useNative = options.format != MediaExportFormat.GIF && withContext(Dispatchers.IO) { NativeMediaExport.needed(source,options) }
                if(useNative) {
                    val work=withContext(Dispatchers.IO){MediaWorkspace.create(context)}
                    workspace=work
                    withContext(Dispatchers.IO) { NativeMediaExport.export(context,source,uri,info,options,resultFile,cover,work,::transform,onProgress) }
                } else if (options.format == MediaExportFormat.GIF) withContext(Dispatchers.IO) {
                    gif(uri, info, options, resultFile) { progress -> withContext(Dispatchers.Main.immediate) { onProgress(progress) } }
                } else transform(uri, info, options, resultFile, onProgress)
                if (options.reverseAudio && !useNative) withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    ReversePcmWave.apply(resultFile, { job.ensureActive() }, options)
                }
                withContext(Dispatchers.IO) {
                    ensureActive()
                    require(sha(source) { jobContext.ensureActive() } == before) { "Šaltinis pasikeitė" }
                    if (options.format in setOf(MediaExportFormat.MP4,MediaExportFormat.M4A,MediaExportFormat.MOV)) Mp4AudioTags.write(resultFile, options.tags, cover)
                    else if (options.format == MediaExportFormat.WAV) WavAudioTags.write(resultFile, options.tags, cover)
                    if(useNative) NativeMediaExport.tags(context,resultFile,options,cover,requireNotNull(workspace))
                    require(resultFile.length() in 1..EditLimits.MAX_FILE_BYTES) { "Išvesties failas per didelis" }
                    RandomAccessFile(resultFile, "rw").use { it.fd.sync() }
                }
                val duration = if (options.format == MediaExportFormat.GIF) options.durationMs else withContext(Dispatchers.IO) {
                    if(useNative)NativeMediaExport.validate(context,resultFile,info,options,requireNotNull(workspace)) else validateOutput(resultFile, info, options)
                }
                withContext(Dispatchers.IO) { workspace?.close();workspace=null }
                onProgress(100)
                ensureActive() // Cancellation at the final callback must not orphan a completed private file.
                MediaExportFile(resultFile, MediaEditNames.outputName(name, options.format), options.format, duration, source, before)
            }
            currentCoroutineContext().ensureActive()
            succeeded=true
            return exported
        } finally {
            try { withContext(NonCancellable + Dispatchers.IO) { workspace?.close(); if (!succeeded) output?.delete() } }
            finally { MediaExportStorage.admission.release() }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    private suspend fun transform(uri: Uri, info: MediaEditInfo, options: MediaEditOptions, output: File, progress: (Int?) -> Unit) = withContext(Dispatchers.Main.immediate) {
        if (options.format.video) {
            val (width, height) = options.videoSize(info)
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            require(width in 2..8192 && height in 2..8192 && (width.toLong() * height + info.width.toLong() * info.height) * 12 <= available - available / 4) { "Šiai raiškai nepakanka atminties" }
            val supported = withContext(Dispatchers.IO) {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                    codec.isEncoder && codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, true) } &&
                        runCatching { codec.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities?.isSizeSupported(width, height) == true }.getOrDefault(false)
                }
            }
            require(supported) { "Įrenginys nepalaiko šios raiškos" }
        }
        val gain = ChannelMixingAudioProcessor().apply {
            val inputs = if (options.channels == null) 1..8 else {
                val actual = requireNotNull(info.audioChannels) { "Audio channel information is unavailable" }
                actual..actual
            }
            for (channels in inputs) {
                val outputs = options.channels ?: channels
                val matrix = if (outputs == channels || channels <= 2) ChannelMixingMatrix.createForConstantGain(channels, outputs)
                    else ChannelMixingMatrix.createForConstantPower(channels, outputs)
                putChannelMixingMatrix(matrix.scaleBy(options.volume))
            }
        }
        val pitch = SonicAudioProcessor().apply { setPitch(options.pitch); options.sampleRate?.let(::setOutputSampleRateHz) }
        val processors = buildList {
            if (options.pitch != 1f || options.sampleRate != null) add(pitch)
            if (options.volume != 1f || options.channels != null) add(gain)
            if (!options.reverseAudio && (options.fadeInMs > 0 || options.fadeOutMs > 0 || options.echoDelayMs > 0 || options.noiseGate)) add(MediaAudioEnvelopeProcessor(options))
        }
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(options.startMs).setEndPositionMs(options.endMs).build()).build()
        val edited = EditedMediaItem.Builder(item).setRemoveVideo(!options.format.video).setRemoveAudio(!info.hasAudio)
            .setEffects(Effects(processors, effects(info, options)))
            .setSpeed(SpeedParameters(constantSpeed(options.speed), true))
            .apply { options.frameRate?.let(::setFrameRate) }.build()
        val encoder = DefaultEncoderFactory.Builder(context).setEnableFallback(false)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(options.audioBitrate).build())
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().apply { options.videoBitrate?.let(::setBitrate) }.build()).build()
        val finished = CompletableDeferred<ExportResult>()
        val muxerFactory = object : Muxer.Factory {
            override fun create(path: String): Muxer {
                require(File(path).canonicalFile == output.canonicalFile)
                val sink = BoundedMuxerOutput(output)
                return try {
                    if (options.format == MediaExportFormat.WAV) {
                        val wav = WavMuxer(sink)
                        object : Muxer by wav {
                            // MP4 decoder/container metadata cannot be written by WavMuxer.
                            // User-editable WAV tags and cover are written by WavAudioTags
                            // after successful PCM muxing, not through this container callback.
                            override fun addMetadataEntry(metadata: androidx.media3.common.Metadata.Entry) = Unit
                        }
                    }
                    else {
                        val mp4 = Mp4Muxer.Builder(sink).setSampleCopyingEnabled(false).setSampleBatchingEnabled(false).setAttemptStreamableOutputEnabled(false).build()
                        object : Muxer by mp4 {
                            // Container/decoder bookkeeping is not an audio tag. The editor explicitly
                            // states which metadata is copied and writes its editable tags afterwards.
                            override fun addMetadataEntry(metadata: androidx.media3.common.Metadata.Entry) {
                                if (androidx.media3.muxer.MuxerUtil.isMetadataSupported(metadata)) mp4.addMetadataEntry(metadata)
                            }
                        }
                    }
                } catch (error: Throwable) { sink.close(); throw error }
            }
            override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
                if (trackType == C.TRACK_TYPE_VIDEO && options.format == MediaExportFormat.MP4) ImmutableList.of(MimeTypes.VIDEO_H264)
                else if (trackType == C.TRACK_TYPE_AUDIO) ImmutableList.of(if (options.format == MediaExportFormat.WAV) MimeTypes.AUDIO_RAW else MimeTypes.AUDIO_AAC)
                else ImmutableList.of()
        }
        val bitmapLoader = object : androidx.media3.common.util.BitmapLoader {
            override fun supportsMimeType(mimeType: String) = false
            override fun decodeBitmap(data: ByteArray): com.google.common.util.concurrent.ListenableFuture<Bitmap> = com.google.common.util.concurrent.Futures.immediateFailedFuture(IllegalStateException("AF_MEDIA_IMAGE_INPUT_DENIED"))
            override fun loadBitmap(uri: Uri): com.google.common.util.concurrent.ListenableFuture<Bitmap> = com.google.common.util.concurrent.Futures.immediateFailedFuture(IllegalStateException("AF_MEDIA_IMAGE_INPUT_DENIED"))
        }
        val assetLoader = DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(),
            androidx.media3.common.util.Clock.DEFAULT, androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(localMediaSource(context, uri)), bitmapLoader)
        val transformer = Transformer.Builder(context).setUsePlatformDiagnostics(false).setAssetLoaderFactory(assetLoader)
            .setVideoFrameProcessorFactory(mediaFrameProcessor(context))
            .setAudioMimeType(if (options.format == MediaExportFormat.WAV) MimeTypes.AUDIO_RAW else MimeTypes.AUDIO_AAC)
            .apply { if (options.format.video) setVideoMimeType(MimeTypes.VIDEO_H264) }.setPortraitEncodingEnabled(true)
            .setEncoderFactory(PcmEncoderFactory(encoder, options.format == MediaExportFormat.WAV))
            .setMuxerFactory(muxerFactory).setMaxDelayBetweenMuxerSamplesMs(30_000)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { finished.complete(result) }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    finished.completeExceptionally(IllegalStateException("Medijos konvertuoti nepavyko", exception))
                }
                override fun onFallbackApplied(composition: Composition, original: TransformationRequest, fallback: TransformationRequest) {
                    finished.completeExceptionally(IllegalStateException("Įrenginys nepalaiko pasirinktų nustatymų"))
                }
            }).build()
        try {
            transformer.start(edited, output.path)
            val holder = ProgressHolder()
            while (!finished.isCompleted) {
                ensureActive()
                progress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress.coerceAtMost(99) else null)
                delay(250)
            }
            finished.await()
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { transformer.cancel() } }
    }

    private fun validateOutput(output: File, input: MediaEditInfo, options: MediaEditOptions): Long {
        val reader = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            reader.setDataSource(output.path)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0 && abs(duration - options.durationMs) <= 350) { "Išvesties trukmė neatitinka kirpimo" }
            extractor.setDataSource(output.path)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
            require(types.any { it.startsWith(if (options.format.video) "video/" else "audio/") }) { "Išvesties takelio nėra" }
            if (!options.format.video) require(types.none { it.startsWith("video/") })
            if (options.format.video && input.hasAudio) require(types.any { it.startsWith("audio/") }) { "Išvesties garso takelio nėra" }
            if (options.format.video && (!options.crop.full || options.height != null)) {
                val expected = options.videoSize(input)
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                require(width == expected.first && height == expected.second) { "Išvesties raiška neatitinka pasirinkimo" }
            }
            return duration
        } finally { extractor.release(); reader.release() }
    }

    private suspend fun gif(uri: Uri, info: MediaEditInfo, options: MediaEditOptions, file: File, progress: suspend (Int) -> Unit) {
        val (rawWidth, rawHeight) = options.videoSize(info)
        val scale = minOf(1f, 480f / maxOf(rawWidth, rawHeight))
        val width = (rawWidth * scale).roundToInt().coerceIn(1, 480)
        val height = (rawHeight * scale).roundToInt().coerceIn(1, 480)
        val fps = options.frameRate ?: 10
        val frames = ((options.durationMs * fps + 999) / 1000).toInt().coerceIn(1, 600)
        val lastFrameUs = lastVideoFrameTimeUs(uri, options.endMs * 1000 - 1)
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        require(info.width.toLong() * info.height * 8 + width.toLong() * height * 12 <= available - available / 4) { "GIF kūrimui nepakanka atminties" }
        val frameEffects = buildList {
            val crop = options.crop
            if (!crop.full) add(Crop(crop.x * 2 - 1, (crop.x + crop.width) * 2 - 1, 1 - (crop.y + crop.height) * 2, 1 - crop.y * 2))
            add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
        }
        val extractor = withContext(Dispatchers.Main.immediate) {
            FrameExtractor.Builder(context, MediaItem.fromUri(uri)).setEffects(frameEffects)
                .setGlObjectsProvider(mediaGlObjects(context))
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(localMediaSource(context, uri))).build()
        }
        var retained: Bitmap? = null
        try {
            FileOutputStream(file).use { stream ->
                val writer = GifSequenceWriter(stream, width, height)
                val pixels = IntArray(width * height)
                var previousTimeMs = -1L
                repeat(frames) { index ->
                    currentCoroutineContext().ensureActive()
                    // Preserve the selected duration even when audio ends after video.
                    // Reuse the real last frame only for that explicit audio tail.
                    val sourceIndex = if(options.reverseVideo) frames-1-index else index
                    val timeUs = (options.startMs * 1000 + sourceIndex * 1_000_000.0 / fps * options.speed).toLong().coerceAtMost(lastFrameUs)
                    val timeMs = timeUs / 1000
                    if (previousTimeMs != timeMs) {
                        val decoded = gifFrame(extractor, timeMs)
                        require(decoded.width == width && decoded.height == height) { "Išvesties raiška neatitinka pasirinkimo" }
                        if (decoded !== retained) retained?.recycle()
                        retained = decoded
                        previousTimeMs = timeMs
                    }
                    requireNotNull(retained).getPixels(pixels, 0, width, 0, 0, width, height)
                    val job = currentCoroutineContext()
                    val beforeCs = (index * options.durationMs / frames / 10.0).roundToInt()
                    val afterCs = ((index + 1) * options.durationMs / frames / 10.0).roundToInt()
                    writer.frame(pixels, afterCs - beforeCs) { job.ensureActive() }
                    if (index % maxOf(1, fps / 4) == 0) progress((index * 100 / frames).coerceAtMost(99))
                }
                writer.close(); stream.fd.sync()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { extractor.close() }
            retained?.recycle()
        }
    }

    private suspend fun gifFrame(extractor: FrameExtractor, timeMs: Long): Bitmap = withContext(Dispatchers.Main.immediate) {
        val future = extractor.getFrame(timeMs)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { future.cancel(true) }
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get().bitmap) }
                    catch (error: Exception) { continuation.resumeWithException(IllegalStateException("Vaizdo kadro gauti nepavyko", error)) }
                }
            }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }
    }

    private suspend fun lastVideoFrameTimeUs(uri: Uri, endUs: Long): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Faile nėra vaizdo takelio")
            extractor.selectTrack(track)
            extractor.seekTo(endUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var last = -1L
            while (extractor.sampleTime in 0..endUs) {
                currentCoroutineContext().ensureActive()
                last = maxOf(last, extractor.sampleTime)
                if (!extractor.advance()) break
            }
            require(last >= 0) { "Vaizdo kadro gauti nepavyko" }
            return last
        } finally { extractor.release() }
    }

    private fun sha(file: File, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024); while (true) { checkActive(); val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun requireVideoMemory(width: Int, height: Int) {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    require(width.toLong() * height * 12 <= available - available / 4) { "Šiai raiškai nepakanka atminties" }
}

internal fun constantSpeed(value: Float) = object : SpeedProvider {
    override fun getSpeed(timeUs: Long) = value
    override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
}

internal fun effects(info: MediaEditInfo, options: MediaEditOptions): List<Effect> = buildList {
    if (!options.format.video || options.format == MediaExportFormat.GIF) return@buildList
    val crop = options.crop
    if (!crop.full) add(Crop(crop.x * 2 - 1, (crop.x + crop.width) * 2 - 1, 1 - (crop.y + crop.height) * 2, 1 - crop.y * 2))
    val size = options.videoSize(info)
    if (options.height != null || !crop.full) add(Presentation.createForWidthAndHeight(size.first, size.second, Presentation.LAYOUT_SCALE_TO_FIT))
}

/** Bounds every muxer write, including seeks, rather than inspecting the file only after export. */
internal class BoundedMuxerOutput(file: File) : SeekableMuxerOutput {
    private val owner = RandomAccessFile(file, "rw")
    private val channel = owner.channel
    override fun write(source: ByteBuffer): Int {
        require(channel.position() + source.remaining() <= EditLimits.MAX_FILE_BYTES) { "Išvesties failas per didelis" }
        return channel.write(source)
    }
    override fun getPosition() = channel.position()
    override fun setPosition(position: Long) { require(position in 0..EditLimits.MAX_FILE_BYTES); channel.position(position) }
    override fun getSize() = channel.size()
    override fun truncate(size: Long) { require(size in 0..EditLimits.MAX_FILE_BYTES); channel.truncate(size) }
    override fun isOpen() = channel.isOpen
    override fun close() { try { channel.force(true) } finally { owner.close() } }
}
