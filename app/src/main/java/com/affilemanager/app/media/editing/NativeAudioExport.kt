package com.affilemanager.app.media.editing

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal object NativeAudioExport {
    private const val PCM_CHUNK = 64L * 1024 * 1024
    fun filters(options: MediaEditOptions, rate: Int, includeEffects: Boolean): List<String> = buildList {
        add("asetpts=PTS-STARTPTS")
        if (options.pitch != 1f) add("asetrate=${(rate * options.pitch).roundToInt()}")
        add("aresample=${options.sampleRate ?: rate}")
        var tempo = options.speed.toDouble() / options.pitch
        while (tempo < .5) { add("atempo=0.5"); tempo /= .5 }
        while (tempo > 2.0) { add("atempo=2"); tempo /= 2 }
        if (kotlin.math.abs(tempo - 1.0) > .000001) add("atempo=${number(tempo)}")
        if (options.volume != 1f) add("volume=${number(options.volume.toDouble())}")
        if (includeEffects) addAll(effects(options))
        add("atrim=duration=${number(options.durationMs / 1000.0)}")
        add("asetpts=PTS-STARTPTS")
    }
    private fun effects(options: MediaEditOptions): List<String> = buildList {
        // A fixed floor must not adapt down to persistent hiss and then pass it unchanged.
        if (options.noiseReduction) add("afftdn=nr=20:nf=-25:tn=0")
        // Preserve the existing gate/fade/echo processor's semantics where it is used;
        // native exports implement the same explicit effects on the output timeline.
        if (options.noiseGate) add("agate=threshold=0.02:ratio=20:attack=5:release=50:range=0.001")
        if (options.echoDelayMs > 0) add("aecho=1:1:${options.echoDelayMs}:0.3")
        if (options.fadeInMs > 0) add("afade=t=in:st=0:d=${number(options.fadeInMs / 1000.0)}")
        if (options.fadeOutMs > 0) add("afade=t=out:st=${number((options.durationMs-options.fadeOutMs)/1000.0)}:d=${number(options.fadeOutMs/1000.0)}")
    }
    suspend fun export(context: Context, source: File, info: MediaEditInfo, options: MediaEditOptions,
        output: File, workspace: MediaWorkspace, progress: (Int?) -> Unit) {
        val tools = NativeMediaTools(context, workspace)
        val actual = NativeMediaProbe.read(context, source, workspace)
        val rate = info.audioSampleRate ?: actual.audio?.rate ?: error("Garso kanalų informacija nepasiekiama")
        val targetRate = options.sampleRate ?: rate
        val channels = options.channels ?: info.audioChannels ?: actual.audio?.channels ?: error("Garso kanalų informacija nepasiekiama")
        require(channels in 1..8)
        val encoding = when (options.format) {
            MediaExportFormat.WAV -> listOf("-c:a", "pcm_s16le", "-f", "wav")
            MediaExportFormat.M4A -> listOf("-c:a", "aac", "-b:a", "${options.audioBitrate}", "-f", "mp4")
            MediaExportFormat.MP3 -> listOf("-c:a", "libmp3lame", "-b:a", "${options.audioBitrate}", "-id3v2_version", "0", "-write_id3v1", "0", "-f", "mp3")
            MediaExportFormat.FLAC -> listOf("-c:a", "flac", "-f", "flac")
            MediaExportFormat.WMA -> listOf("-c:a", "wmav2", "-b:a", "${options.audioBitrate}", "-f", "asf")
            else -> error("Netinkamas išvesties failas")
        }
        val tags = if (options.format in setOf(MediaExportFormat.FLAC, MediaExportFormat.WMA)) metadata(options.tags) else emptyList()
        val outputOptions = listOf("-vn", "-ac", "$channels", "-ar", "$targetRate", "-map_metadata", "-1") + encoding + tags + output.canonicalPath
        val trim = NativeClipInput.arguments(source,options.startMs*1000,options.endMs*1000) + listOf("-map", "0:a:0")
        if (!options.reverseAudio) {
            tools.ffmpeg(trim + listOf("-af", filters(options,rate,true).joinToString(",")) + outputOptions, "audio", progress = {
                progress((it * 90 / (options.durationMs * 1000)).toInt().coerceIn(0, 90))
            })
            return
        }
        val bytes = options.durationMs * targetRate.toLong() * channels * 2 / 1000
        require(bytes <= MediaWorkspace.MAX_BYTES - 32L * 1024 * 1024) { "AF_MEDIA_WORKSPACE_LIMIT" }
        val frameSize = channels * 2
        val segmentSeconds = (PCM_CHUNK / frameSize).toDouble() / targetRate
        tools.ffmpeg(trim + listOf("-af", filters(options,rate,false).joinToString(","), "-vn", "-ac", "$channels", "-ar", "$targetRate",
            "-c:a", "pcm_s16le", "-f", "segment", "-segment_time", number(segmentSeconds), "-segment_format", "s16le",
            "-reset_timestamps", "1", File(workspace.directory, "pcm-%03d.pcm").path), "audio-reverse-decode", progress = {
            progress((it * 40 / (options.durationMs * 1000)).toInt().coerceIn(0, 40))
        })
        val chunks = (0 until 16).map { workspace.file("pcm-${it.toString().padStart(3,'0')}.pcm") }.takeWhile { it.isFile }
        require(chunks.isNotEmpty() && chunks.all { it.length() > 0 && it.length() % frameSize == 0L && it.length() <= PCM_CHUNK + 65536 }) { "Netinkamas išvesties failas" }
        val job = currentCoroutineContext()
        for (chunk in chunks) PcmFrameReverse.file(chunk, frameSize) { job.ensureActive() }
        val nativeEffects = effects(options) + "atrim=duration=${number(options.durationMs/1000.0)}"
        tools.ffmpeg(listOf("-f", "s16le", "-ar", "$targetRate", "-ac", "$channels", "-i", "pipe:0", "-af", nativeEffects.joinToString(",")) + outputOptions,
            "audio-reverse-encode", { progress(40 + (it * 50 / (options.durationMs*1000)).toInt().coerceIn(0,50)) }, input = { stream ->
                val buffer = ByteArray(64 * 1024)
                for (chunk in chunks.asReversed()) {
                    chunk.inputStream().use { input ->
                        while (true) { job.ensureActive(); val size=input.read(buffer); if(size<0)break;stream.write(buffer,0,size) }
                    }
                    require(chunk.delete()) { "Laikinos redagavimo kopijos pašalinti nepavyko" }
                }
            })
    }
    fun metadata(tags: MediaTags): List<String> = buildList {
        val values = listOf("title" to tags.title, "artist" to tags.artist, "album" to tags.album, "album_artist" to tags.albumArtist,
            "composer" to tags.composer, "publisher" to tags.publisher, "date" to tags.date, "genre" to tags.genre, "lyrics" to tags.lyrics, "comment" to tags.comment)
        for ((key,value) in values) if (value.isNotEmpty()) { add("-metadata"); add("$key=$value") }
    }
    fun number(value: Double): String { require(value.isFinite()); return String.format(Locale.ROOT,"%.6f",value) }
}

/** Input trim must run BEFORE buffered/reverse filters; output -t trims the wrong end. */
internal object NativeClipInput {
    fun arguments(source: File, startUs: Long, endUs: Long): List<String> {
        require(startUs>=0 && endUs>startUs)
        return listOf("-ss",NativeAudioExport.number(startUs/1000000.0),"-t",NativeAudioExport.number((endUs-startUs)/1000000.0),"-i",source.canonicalPath)
    }
}
