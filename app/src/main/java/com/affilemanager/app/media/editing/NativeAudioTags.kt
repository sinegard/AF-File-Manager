package com.affilemanager.app.media.editing

import com.affilemanager.app.editing.EditLimits
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Container-specific metadata on exclusively owned exports; encoded media is copied verbatim. */
internal object NativeAudioTags {
    fun mp3(file: File, tags: MediaTags, cover: ByteArray?, temporary: File, checkpoint: () -> Unit) {
        val id3 = WavAudioTags.encode(tags, cover)
        if (id3.isEmpty()) return
        RandomAccessFile(file, "r").use { input ->
            val magic = ByteArray(3).also(input::readFully)
            require(magic.toString(Charsets.US_ASCII) != "ID3") { "Netinkamas išvesties failas" }
        }
        prepend(file, temporary, id3, 0, checkpoint)
    }
    fun flacCover(file: File, cover: ByteArray?, temporary: File, checkpoint: () -> Unit) {
        if (cover == null) return
        val (width, height) = jpegSize(cover)
        val picture = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(3); it.writeInt(10); it.writeBytes("image/jpeg"); it.writeInt(0)
            it.writeInt(width); it.writeInt(height); it.writeInt(24); it.writeInt(0); it.writeInt(cover.size); it.write(cover)
        } }.toByteArray()
        val prefix = RandomAccessFile(file, "r").use { input ->
            require(input.readInt() == 0x664c6143)
            var last = false; var count = 0; var lastOffset = 0
            while (!last) {
                checkpoint(); require(++count <= 128)
                lastOffset = input.filePointer.toInt()
                val type = input.readUnsignedByte(); last = type and 128 != 0
                val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                require(length <= 4 * 1024 * 1024 && input.filePointer + length <= input.length() && input.filePointer + length <= 8 * 1024 * 1024)
                input.seek(input.filePointer + length)
            }
            val end = input.filePointer.toInt()
            input.seek(0)
            val metadata = ByteArray(end).also(input::readFully)
            metadata[lastOffset] = (metadata[lastOffset].toInt() and 127).toByte()
            metadata + byteArrayOf(0x86.toByte(), (picture.size ushr 16).toByte(), (picture.size ushr 8).toByte(), picture.size.toByte()) + picture
        }
        val originalHeaderLength = prefix.size - picture.size - 4
        prepend(file, temporary, prefix, originalHeaderLength.toLong(), checkpoint)
    }
    fun asfCover(file: File, cover: ByteArray?, temporary: File, checkpoint: () -> Unit) {
        if (cover == null) return
        jpegSize(cover)
        val headerGuid = hex("3026b2758e66cf11a6d900aa0062ce6c")
        val extensionGuid = hex("b503bf5f2ea9cf118ee300c00c205365")
        val filePropertiesGuid = hex("a1dcab8c47a9cf118ee400c00c205365")
        val libraryGuid = hex("941c23449894d149a1411d134e457054")
        val picture = ByteArrayOutputStream().also {
            it.write(3); little(it, cover.size, 4); it.write("image/jpeg\u0000\u0000".toByteArray(Charsets.UTF_16LE)); it.write(cover)
        }.toByteArray()
        val name = "WM/Picture\u0000".toByteArray(Charsets.UTF_16LE)
        val body = ByteArrayOutputStream().also {
            little(it, 1, 2); little(it, 0, 2); little(it, 0, 2); little(it, name.size, 2)
            little(it, 1, 2); little(it, picture.size, 4); it.write(name); it.write(picture)
        }.toByteArray()
        val extra = libraryGuid + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(body.size + 24L).array() + body
        val prefix = RandomAccessFile(file, "r").use { input ->
            require(ByteArray(16).also(input::readFully).contentEquals(headerGuid))
            val headerLength = java.lang.Long.reverseBytes(input.readLong())
            require(headerLength in 30..4L * 1024 * 1024 && headerLength <= input.length())
            input.seek(0); val original = ByteArray(headerLength.toInt()).also(input::readFully)
            var offset = 30; var extension = -1; var properties = -1; var count = 0
            while (offset < original.size) {
                checkpoint(); require(++count <= 128 && offset + 24 <= original.size)
                val size = ByteBuffer.wrap(original, offset + 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
                require(size in 24..(original.size - offset).toLong())
                val guid = original.copyOfRange(offset, offset + 16)
                if (guid.contentEquals(extensionGuid)) { require(extension == -1); extension = offset }
                if (guid.contentEquals(filePropertiesGuid)) properties = offset
                offset += size.toInt()
            }
            require(extension >= 0 && properties >= 0 && extension + 46 <= original.size)
            val extensionSize = ByteBuffer.wrap(original, extension + 16, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
            require(extensionSize >= 46)
            val at = extension + extensionSize
            val updated = original.copyOfRange(0, at) + extra + original.copyOfRange(at, original.size)
            val fields = ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN)
            fields.putLong(16, headerLength + extra.size)
            fields.putLong(extension + 16, extensionSize.toLong() + extra.size)
            fields.putInt(extension + 42, extensionSize - 46 + extra.size)
            val adjustedProperties = if (properties >= at) properties + extra.size else properties
            fields.putLong(adjustedProperties + 40, file.length() + extra.size)
            updated
        }
        prepend(file, temporary, prefix, prefix.size.toLong() - extra.size, checkpoint)
    }
    private fun prepend(file: File, temporary: File, prefix: ByteArray, skipped: Long, checkpoint: () -> Unit) {
        require(!temporary.exists() && file.length() - skipped + prefix.size <= EditLimits.MAX_FILE_BYTES) { "Išvesties failas per didelis" }
        try {
            RandomAccessFile(file, "r").use { input ->
                input.seek(skipped)
                java.io.FileOutputStream(temporary).use { output ->
                    output.write(prefix); val buffer = ByteArray(64 * 1024)
                    while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    internal fun jpegSize(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size in 4..2 * 1024 * 1024 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Paveikslėlio atidaryti nepavyko" }
        var position = 2
        while (position + 4 <= bytes.size) {
            require(bytes[position++] == 0xff.toByte())
            while (position < bytes.size && bytes[position] == 0xff.toByte()) position++
            require(position < bytes.size)
            val marker = bytes[position++].toInt() and 255
            require(marker !in setOf(0xda, 0xd9))
            require(position + 2 <= bytes.size)
            val length = ((bytes[position].toInt() and 255) shl 8) or (bytes[position + 1].toInt() and 255)
            require(length >= 2 && position + length <= bytes.size)
            if (marker in setOf(0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf)) {
                require(length >= 8)
                val height = ((bytes[position + 3].toInt() and 255) shl 8) or (bytes[position + 4].toInt() and 255)
                val width = ((bytes[position + 5].toInt() and 255) shl 8) or (bytes[position + 6].toInt() and 255)
                require(width in 1..1024 && height in 1..1024)
                return width to height
            }
            position += length
        }
        throw IllegalArgumentException("Paveikslėlio atidaryti nepavyko")
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun little(out: ByteArrayOutputStream, value: Int, bytes: Int) { repeat(bytes) { out.write(value ushr (it * 8) and 255) } }
}
