package com.affilemanager.app.media.editing

import com.affilemanager.app.editing.EditDestinationRules
import com.affilemanager.app.editing.EditLimits
import kotlin.math.roundToInt

enum class MediaExportFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"), MKV("mkv", "video/x-matroska"), MOV("mov", "video/quicktime"),
    M4A("m4a", "audio/mp4"), MP3("mp3", "audio/mpeg"), WAV("wav", "audio/wav"),
    FLAC("flac", "audio/flac"), WMA("wma", "audio/x-ms-wma"), GIF("gif", "image/gif");
    val video: Boolean get() = this in setOf(MP4, MKV, MOV, GIF)
    val losslessAudio: Boolean get() = this == WAV || this == FLAC
}

data class MediaCrop(val x: Float = 0f, val y: Float = 0f, val width: Float = 1f, val height: Float = 1f) {
    init {
        require(listOf(x, y, width, height).all(Float::isFinite))
        require(x >= 0 && y >= 0 && width >= .05f && height >= .05f && x + width <= 1.00001f && y + height <= 1.00001f)
    }
    val full: Boolean get() = this == MediaCrop()
    fun move(dx: Float, dy: Float) = copy(x = (x + dx).coerceIn(0f, 1f - width), y = (y + dy).coerceIn(0f, 1f - height))
    fun resize(dx: Float, dy: Float) = copy(width = (width + dx).coerceIn(.05f, 1f - x), height = (height + dy).coerceIn(.05f, 1f - y))
}

data class MediaTags(
    val title: String = "", val artist: String = "", val album: String = "", val albumArtist: String = "",
    val composer: String = "", val publisher: String = "", val date: String = "", val genre: String = "",
    val lyrics: String = "", val comment: String = "",
) {
    fun validate() {
        listOf(title, artist, album, albumArtist, composer, publisher, date, genre, comment).forEach {
            require(it.toByteArray(Charsets.UTF_8).size <= 4096 && '\u0000' !in it) { "Metaduomenys per ilgi" }
        }
        require(lyrics.toByteArray(Charsets.UTF_8).size <= 32768 && '\u0000' !in lyrics) { "Metaduomenys per ilgi" }
    }
    fun values(): Map<String, String> = linkedMapOf("©nam" to title, "©ART" to artist, "©alb" to album,
        "aART" to albumArtist, "©wrt" to composer, "©pub" to publisher, "©day" to date, "©gen" to genre,
        "©lyr" to lyrics, "©cmt" to comment).filterValues(String::isNotEmpty)
}

data class MediaEditInfo(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean, val sourceBytes: Long,
    val audioSampleRate: Int? = null, val audioChannels: Int? = null) {
    init {
        require(durationMs in 1..3_600_000) { "Medijos trukmė nepasiekiama arba viršija 1 valandą" }
        require(width in 0..8192 && height in 0..8192) { "Netinkama raiška" }
        require(sourceBytes in 1..EditLimits.MAX_FILE_BYTES) { "Failas tuščias arba viršija 256 MiB" }
    }
    val hasVideo: Boolean get() = width > 0 && height > 0
}

data class MediaEditOptions(
    val format: MediaExportFormat, val startMs: Long, val endMs: Long,
    val speed: Float = 1f, val pitch: Float = 1f, val volume: Float = 1f,
    val crop: MediaCrop = MediaCrop(), val height: Int? = null, val frameRate: Int? = null,
    val videoBitrate: Int? = null, val audioBitrate: Int = 192_000, val tags: MediaTags = MediaTags(),
    val sampleRate: Int? = null, val channels: Int? = null,
    val fadeInMs: Int = 0, val fadeOutMs: Int = 0, val echoDelayMs: Int = 0, val noiseGate: Boolean = false, val reverseAudio: Boolean = false,
    val noiseReduction: Boolean = false, val reverseVideo: Boolean = false, val copyTracks: Boolean = false,
) {
    val durationMs: Long get() = ((endMs - startMs) / speed).toLong()
    fun validate(info: MediaEditInfo) {
        require(startMs >= 0 && endMs <= info.durationMs && endMs - startMs >= 100) { "Netinkamas kirpimo intervalas" }
        require(speed.isFinite() && speed in .25f..4f && pitch.isFinite() && pitch in .5f..2f && volume.isFinite() && volume in 0f..2f) { "Netinkami atkūrimo nustatymai" }
        require(!format.video || info.hasVideo) { "Faile nėra vaizdo takelio" }
        require(format.video || info.hasAudio) { "Faile nėra garso takelio" }
        require(height == null || height in setOf(240, 360, 480, 720, 1080, 1440, 2160)) { "Netinkama raiška" }
        require(frameRate == null || frameRate in 1..60) { "Netinkama kadrų sparta" }
        require(videoBitrate == null || videoBitrate in 128_000..80_000_000) { "Netinkama bitų sparta" }
        require(audioBitrate in 32_000..320_000) { "Netinkama bitų sparta" }
        require(sampleRate == null || sampleRate in setOf(8000, 11025, 16000, 22050, 32000, 44100, 48000)) { "Invalid audio sample rate" }
        require(channels == null || channels in 1..2) { "Invalid audio channel count" }
        require(fadeInMs in 0..5000 && fadeOutMs in 0..5000 && fadeInMs.toLong() + fadeOutMs <= durationMs) { "Fade durations exceed the selected clip" }
        require(echoDelayMs in 0..500) { "Invalid audio effect settings" }
        require(!reverseAudio || (info.hasAudio && format != MediaExportFormat.GIF)) { "Faile nėra garso takelio" }
        require(!reverseVideo || format.video) { "Faile nėra vaizdo takelio" }
        if (copyTracks) {
            require(format.video && format != MediaExportFormat.GIF && startMs == 0L && endMs == info.durationMs &&
                speed == 1f && pitch == 1f && volume == 1f && crop.full && height == null && frameRate == null &&
                videoBitrate == null && sampleRate == null && channels == null && fadeInMs == 0 && fadeOutMs == 0 &&
                echoDelayMs == 0 && !noiseGate && !noiseReduction && !reverseAudio && !reverseVideo) { "AF_MEDIA_COPY_SETTINGS" }
        }
        if (format == MediaExportFormat.GIF) {
            require(durationMs <= 60_000 && (frameRate ?: 10) <= 15 && durationMs * (frameRate ?: 10) / 1000 <= 600) { "GIF leidžiama iki 60 sekundžių ir 600 kadrų" }
            require(height == null || height <= 480) { "GIF raiška negali viršyti 480" }
        }
        tags.validate()
    }
    fun videoSize(info: MediaEditInfo): Pair<Int, Int> {
        require(info.hasVideo)
        val croppedWidth = info.width * crop.width
        val croppedHeight = info.height * crop.height
        val targetHeight = height?.toFloat() ?: croppedHeight
        fun even(value: Float) = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
        return even(croppedWidth * targetHeight / croppedHeight) to even(targetHeight)
    }
    fun estimatedBytes(info: MediaEditInfo): Long? {
        if (format == MediaExportFormat.GIF || format == MediaExportFormat.FLAC) return null
        if (copyTracks) return info.sourceBytes
        if (format == MediaExportFormat.WAV) return durationMs * (sampleRate ?: info.audioSampleRate ?: return null).toLong() *
            (channels ?: info.audioChannels ?: return null) * 2 / 1000 + 131_072
        val bitrate = if (!format.video) audioBitrate.toLong()
            else (videoBitrate ?: return null).toLong() + if (info.hasAudio) audioBitrate else 0
        return (durationMs / 1000.0 * bitrate / 8 * 1.02).toLong()
    }
}

object MediaEditNames {
    fun outputName(raw: String, format: MediaExportFormat): String {
        val safe = EditDestinationRules.validateFileName(raw)
        val extension = safe.substringAfterLast('.', "")
        // The editor shows the output extension separately. A dot in a base name
        // is not an extension; replace only a recognized media output suffix.
        val stem = if (MediaExportFormat.entries.any { it.extension.equals(extension, ignoreCase = true) })
            safe.substringBeforeLast('.') else safe
        return EditDestinationRules.validateFileName("$stem.${format.extension}")
    }
}
