@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.media3.extractor.VorbisUtil
import androidx.media3.extractor.metadata.flac.PictureFrame
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded Ogg header fallback. The platform remains the first reader for all media. */
internal object EmbeddedAudioArtwork {
    private const val MAX_HEADER_BYTES = 2 * 1024 * 1024
    private const val MAX_PAGES = 512
    private const val MAX_MILLIS = 1500L
    private const val PICTURE_KEY = "METADATA_BLOCK_PICTURE="

    fun read(file: File): ByteArray? = FileInputStream(file).use(::readHeaders)
    fun read(context: Context, uri: Uri): ByteArray? {
        require(uri.scheme == "content")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(::readHeaders)
    }

    private fun readHeaders(stream: InputStream): ByteArray? {
        return try {
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            if (MAX_HEADER_BYTES * 8L > available - available / 4) return null
            val started = SystemClock.elapsedRealtime()
            var consumed = 0
            fun read(count: Int): ByteArray {
                require(count in 0..MAX_HEADER_BYTES && consumed <= MAX_HEADER_BYTES - count)
                check(!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() - started <= MAX_MILLIS)
                val bytes = ByteArray(count)
                var position = 0
                while (position < count) {
                    val size = stream.read(bytes, position, count - position)
                    if (size <= 0) throw EOFException()
                    position += size
                }
                consumed += count
                return bytes
            }
            var serial: Int? = null
            var packetNumber = 0
            var opus = false
            val packet = ByteArrayOutputStream()
            for (page in 0 until MAX_PAGES) {
                val header = read(27)
                if (!header.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) || header[4].toInt() != 0) break
                val pageSerial = ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (serial == null) serial = pageSerial
                if (pageSerial != serial) break
                val lacing = read(header[26].toInt() and 255)
                val body = read(lacing.sumOf { it.toInt() and 255 })
                var offset = 0
                for (segment in lacing) {
                    val count = segment.toInt() and 255
                    if (packet.size() > MAX_HEADER_BYTES - count) return null
                    packet.write(body, offset, count); offset += count
                    if (count == 255) continue
                    val complete = packet.toByteArray(); packet.reset()
                    if (packetNumber++ == 0) {
                        opus = complete.startsWith("OpusHead".toByteArray())
                        if (!opus && !complete.startsWith(byteArrayOf(1) + "vorbis".toByteArray())) return null
                    } else {
                        val signature = if (opus) "OpusTags".toByteArray() else byteArrayOf(3) + "vorbis".toByteArray()
                        return if (complete.startsWith(signature)) picture(complete, signature.size) else null
                    }
                }
            }
            null
        } catch (_: EOFException) { null } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
    }

    private fun picture(packet: ByteArray, offset: Int): ByteArray? {
        val input = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply { position(offset) }
        fun length(): Int {
            if (input.remaining() < 4) throw IllegalArgumentException()
            return input.int.also { require(it >= 0 && it <= input.remaining()) }
        }
        val vendorLength = length(); input.position(input.position() + vendorLength)
        if (input.remaining() < 4) return null
        val count = input.int
        if (count !in 0..1024) return null
        repeat(count) {
            val size = length()
            val position = input.position()
            if (size > PICTURE_KEY.length && PICTURE_KEY.indices.all { i ->
                input.get(position + i).toInt().toChar().uppercaseChar() == PICTURE_KEY[i]
            }) {
                val comment = ByteArray(size).also(input::get).toString(Charsets.UTF_8)
                val decoded = runCatching { Base64.decode(comment.substring(PICTURE_KEY.length), Base64.DEFAULT) }.getOrNull()
                if (decoded != null && validPicture(decoded)) {
                    val metadata = VorbisUtil.parseVorbisComments(listOf(comment))
                    if (metadata != null) for (index in 0 until metadata.length()) {
                        val frame = metadata[index] as? PictureFrame ?: continue
                        if (frame.pictureData.isNotEmpty()) return frame.pictureData
                    }
                }
            } else input.position(position + size)
        }
        return null
    }

    /** Validate declared lengths before the library allocates its picture array. */
    private fun validPicture(bytes: ByteArray): Boolean {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.remaining() < 12) return false
        input.int // Picture type.
        repeat(2) {
            if (input.remaining() < 4) return false
            val size = input.int
            if (size < 0 || size > input.remaining()) return false
            input.position(input.position() + size)
        }
        if (input.remaining() < 20) return false
        repeat(4) { input.int } // Width, height, depth, palette count; actual decoder checks pixels.
        val size = input.int
        return size in 1..input.remaining()
    }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
