package com.affilemanager.app.media.editing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

internal object WavAudioTags {
    /** Standard ID3v2.4-in-RIFF chunk; readers without tag support still see unchanged PCM. */
    fun write(file: File, tags: MediaTags, cover: ByteArray?) {
        val data = encode(tags, cover)
        if (data.isEmpty()) return
        RandomAccessFile(file, "rw").use { out ->
            val header = ByteArray(12).also(out::readFully)
            require(header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE")
            out.seek(out.length()); out.write("id3 ".toByteArray(Charsets.US_ASCII)); little(out, data.size); out.write(data)
            if (data.size % 2 != 0) out.write(0)
            require(out.length() <= 256L * 1024 * 1024)
            out.seek(4); little(out, (out.length() - 8).toInt()); out.fd.sync()
        }
    }
    internal fun encode(tags: MediaTags, cover: ByteArray?): ByteArray {
        tags.validate(); require(cover == null || cover.size <= 2 * 1024 * 1024)
        val frames = ByteArrayOutputStream()
        val ids = listOf("TIT2", "TPE1", "TALB", "TPE2", "TCOM", "TPUB", "TDRC", "TCON", "USLT", "COMM")
        val values = listOf(tags.title, tags.artist, tags.album, tags.albumArtist, tags.composer, tags.publisher, tags.date, tags.genre, tags.lyrics, tags.comment)
        fun frame(id: String, bytes: ByteArray) { frames.write(id.toByteArray(Charsets.US_ASCII)); frames.write(synchsafe(bytes.size)); frames.write(byteArrayOf(0, 0)); frames.write(bytes) }
        ids.zip(values).filter { it.second.isNotEmpty() }.forEach { (id, value) ->
            val prefix = if (id in setOf("USLT", "COMM")) byteArrayOf(3, 'u'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(), 0) else byteArrayOf(3)
            frame(id, prefix + value.toByteArray(Charsets.UTF_8))
        }
        cover?.let { frame("APIC", byteArrayOf(3) + "image/jpeg\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0) + it) }
        if (frames.size() == 0) return ByteArray(0)
        return "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + synchsafe(frames.size()) + frames.toByteArray()
    }
    private fun synchsafe(value: Int) = byteArrayOf((value shr 21 and 127).toByte(), (value shr 14 and 127).toByte(), (value shr 7 and 127).toByte(), (value and 127).toByte())
    private fun little(out: RandomAccessFile, value: Int) { repeat(4) { out.write(value ushr (it * 8) and 255) } }
}
