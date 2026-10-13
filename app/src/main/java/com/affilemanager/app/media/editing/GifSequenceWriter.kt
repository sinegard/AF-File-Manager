package com.affilemanager.app.media.editing

import java.io.OutputStream

/** A bounded GIF89a stream. Frames use a fixed 3:3:2 palette and explicit, lossless LZW literals. */
internal class GifSequenceWriter(private val output: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
    companion object { const val MAX_BYTES = 64L * 1024 * 1024 }
    private var frames = 0
    private var bytes = 0L
    private var closed = false
    init {
        require(width in 1..480 && height in 1..480)
        put("GIF89a".toByteArray(Charsets.US_ASCII)); word(width); word(height); put(0xf7); put(0); put(0)
        repeat(256) { value -> put((value shr 5) * 255 / 7); put(((value shr 2) and 7) * 255 / 7); put((value and 3) * 255 / 3) }
        put(byteArrayOf(0x21, 0xff.toByte(), 11)); put("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); put(byteArrayOf(3, 1, 0, 0, 0))
    }
    fun frame(pixels: IntArray, delayCentiseconds: Int, checkActive: () -> Unit) {
        check(!closed); require(pixels.size == width * height && ++frames <= 600)
        put(byteArrayOf(0x21, 0xf9.toByte(), 4, 0)); word(delayCentiseconds.coerceIn(1, 65535)); put(0); put(0)
        put(0x2c); word(0); word(0); word(width); word(height); put(0); put(8)
        val block = ByteArray(255)
        var used = 0; var accumulator = 0; var bits = 0
        fun octet(value: Int) { block[used++] = value.toByte(); if (used == 255) { put(used); put(block); used = 0 } }
        fun code(value: Int) {
            accumulator = accumulator or (value shl bits); bits += 9
            while (bits >= 8) { octet(accumulator and 255); accumulator = accumulator ushr 8; bits -= 8 }
        }
        code(256)
        pixels.forEachIndexed { index, argb ->
            if (index % 200 == 0) { checkActive(); if (index > 0) code(256) }
            code(((argb ushr 16 and 255) shr 5 shl 5) or ((argb ushr 8 and 255) shr 5 shl 2) or ((argb and 255) shr 6))
        }
        code(257); if (bits > 0) octet(accumulator and 255)
        if (used > 0) { put(used); put(block.copyOf(used)) }
        put(0)
    }
    override fun close() { if (!closed) { require(frames > 0); put(0x3b); output.flush(); closed = true } }
    private fun word(value: Int) { put(value and 255); put(value shr 8 and 255) }
    private fun put(value: Int) { require(++bytes <= MAX_BYTES) { "GIF failas per didelis" }; output.write(value) }
    private fun put(value: ByteArray) { bytes += value.size; require(bytes <= MAX_BYTES) { "GIF failas per didelis" }; output.write(value) }
}
