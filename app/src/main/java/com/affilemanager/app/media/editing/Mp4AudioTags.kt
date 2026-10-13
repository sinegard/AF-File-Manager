package com.affilemanager.app.media.editing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Adds iTunes-compatible tags to our own exported MP4/M4A, never to a user-provided original. */
internal object Mp4AudioTags {
    fun write(file: File, tags: MediaTags, cover: ByteArray?) {
        tags.validate(); require(cover == null || cover.size <= 2 * 1024 * 1024)
        val items = tags.values().map { (key, value) -> atom(key, atom("data", data(1, value.toByteArray(Charsets.UTF_8)))) }.toMutableList()
        if (cover != null) items += atom("covr", atom("data", data(13, cover)))
        if (items.isEmpty()) return
        val handler = atom("hdlr", ByteArray(8) + "mdirappl".toByteArray(Charsets.US_ASCII) + ByteArray(9))
        val metadata = atom("udta", atom("meta", ByteArray(4) + handler + atom("ilst", join(items))))
        require(metadata.size <= 3 * 1024 * 1024)
        RandomAccessFile(file, "rw").use { target ->
            require(target.length() + metadata.size <= com.affilemanager.app.editing.EditLimits.MAX_FILE_BYTES) { "Išvesties failas per didelis" }
            var position = 0L; var moov = -1L; var moovSize = 0L; var count = 0
            while (position + 8 <= target.length()) {
                require(++count <= 4096)
                target.seek(position)
                val shortSize = target.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(target::readFully).toString(Charsets.ISO_8859_1)
                val size = if (shortSize == 1L) target.readLong() else shortSize
                require(size >= (if (shortSize == 1L) 16 else 8) && size <= target.length() - position) { "Netinkamas išvesties failas" }
                if (type == "moov") { require(moov < 0 && shortSize != 1L); moov = position; moovSize = size }
                position += size
            }
            // Appended metadata cannot shift any encoded sample offsets. Require our exporter to
            // put moov last; reject a different layout rather than corrupt its offset tables.
            require(moov >= 0 && moov + moovSize == target.length()) { "Šiam failui metaduomenų įrašyti nepavyko" }
            require(moovSize + metadata.size <= Int.MAX_VALUE)
            target.seek(target.length()); target.write(metadata)
            target.seek(moov); target.writeInt((moovSize + metadata.size).toInt()); target.fd.sync()
        }
    }
    internal fun atom(type: String, body: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.ISO_8859_1); require(name.size == 4)
        return ByteArrayOutputStream(body.size + 8).also { bytes -> DataOutputStream(bytes).use { it.writeInt(body.size + 8); it.write(name); it.write(body) } }.toByteArray()
    }
    private fun data(kind: Int, bytes: ByteArray) = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.writeInt(kind); it.writeInt(0); it.write(bytes) } }.toByteArray()
    private fun join(items: List<ByteArray>) = ByteArrayOutputStream().also { out -> items.forEach(out::write) }.toByteArray()
}
