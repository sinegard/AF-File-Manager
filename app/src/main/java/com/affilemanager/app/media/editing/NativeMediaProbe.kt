package com.affilemanager.app.media.editing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

internal data class NativeTrack(val type: String, val codec: String, val width: Int, val height: Int,
    val channels: Int?, val rate: Int?, val attachedPicture: Boolean)
internal data class NativeMediaInfo(val durationMs: Long, val container: String, val tracks: List<NativeTrack>, val tags: Map<String, String>) {
    val video get() = tracks.firstOrNull { it.type == "video" && !it.attachedPicture }
    val audio get() = tracks.firstOrNull { it.type == "audio" }
    fun mediaInfo(bytes: Long) = MediaEditInfo(durationMs, video?.width ?: 0, video?.height ?: 0, audio != null, bytes, audio?.rate, audio?.channels)
    fun editableTags(): MediaTags {
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> tags.entries.firstOrNull { it.key.equals(key, true) }?.value }.orEmpty()
        val lyrics=text("lyrics","WM/Lyrics").ifEmpty {tags.entries.firstOrNull {it.key.startsWith("lyrics-",true)}?.value.orEmpty()}
        return MediaTags(text("title"), text("artist", "Author"), text("album", "WM/AlbumTitle"),
            text("album_artist", "albumartist", "WM/AlbumArtist"), text("composer", "WM/Composer"), text("publisher", "WM/Publisher"),
            text("date", "year", "WM/Year"), text("genre", "WM/Genre"), lyrics, text("comment", "description"))
    }
}

internal object NativeMediaProbe {
    private val asfHeader=byteArrayOf(0x30,0x26,0xb2.toByte(),0x75,0x8e.toByte(),0x66,0xcf.toByte(),0x11,0xa6.toByte(),0xd9.toByte(),0,0xaa.toByte(),0,0x62,0xce.toByte(),0x6c)
    /** Routing only: the native parser still validates the complete container. */
    fun isAsf(file:File):Boolean {
        if(!file.isFile || file.length()<asfHeader.size)return false
        val prefix=ByteArray(asfHeader.size)
        file.inputStream().use {input->
            var offset=0
            while(offset<prefix.size){val count=input.read(prefix,offset,prefix.size-offset);if(count<0)return false;offset+=count}
        }
        return prefix.contentEquals(asfHeader)
    }
    suspend fun read(context: Context, file: File, workspace: MediaWorkspace, maxDurationMs: Long = 3_600_000): NativeMediaInfo {
        require(file.isFile && file.length() in 1..com.affilemanager.app.editing.EditLimits.MAX_FILE_BYTES)
        val json = NativeMediaTools(context, workspace).probe(listOf("-threads", "2", "-analyzeduration", "5000000", "-probesize", "8388608",
            "-show_entries", "stream=codec_type,codec_name,width,height,channels,sample_rate:stream_disposition=attached_pic:format=duration,format_name:format_tags",
            "-of", "json", file.canonicalPath))
        val root = JSONObject(json)
        val format = root.getJSONObject("format")
        val seconds = format.optString("duration").toDoubleOrNull()
        require(maxDurationMs in 1..14_400_350)
        require(seconds != null && seconds.isFinite() && seconds > 0 && seconds*1000 <= maxDurationMs+500) { "Medijos trukmė nepasiekiama arba viršija 1 valandą" }
        val streams = root.getJSONArray("streams")
        require(streams.length() in 1..16) { "Netinkamas išvesties failas" }
        val tracks = (0 until streams.length()).map { index ->
            val stream = streams.getJSONObject(index)
            NativeTrack(stream.optString("codec_type"), stream.optString("codec_name"), stream.optInt("width"), stream.optInt("height"),
                stream.optInt("channels").takeIf { it in 1..8 }, stream.optString("sample_rate").toIntOrNull()?.takeIf { it in 8000..192000 },
                stream.optJSONObject("disposition")?.optInt("attached_pic") == 1)
        }
        val tagsObject = format.optJSONObject("tags") ?: JSONObject()
        require(tagsObject.length() <= 128) { "Metaduomenys per ilgi" }
        val tags = tagsObject.keys().asSequence().associateWith { key -> tagsObject.optString(key).also {
            require(key.length <= 256 && it.toByteArray(Charsets.UTF_8).size <= 32768 && '\u0000' !in it) { "Metaduomenys per ilgi" }
        } }
        return NativeMediaInfo((seconds * 1000).toLong(), format.optString("format_name"), tracks, tags)
    }
    fun fallback(context: Context, file: File): NativeMediaInfo = runBlocking(Dispatchers.IO) {
        MediaWorkspace.create(context,1024L*1024).use { read(context, file, it) }
    }
}
