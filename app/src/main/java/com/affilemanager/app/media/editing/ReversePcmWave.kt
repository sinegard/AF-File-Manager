@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import java.io.File
import java.io.RandomAccessFile

/** Reverse complete interleaved frames of an owned export; channels never swap. */
internal object ReversePcmWave {
    fun apply(file: File, checkpoint: () -> Unit, options: MediaEditOptions? = null) = RandomAccessFile(file, "rw").use { wave ->
        fun text() = ByteArray(4).also(wave::readFully).toString(Charsets.US_ASCII)
        fun little() = Integer.reverseBytes(wave.readInt())
        require(text() == "RIFF"); little(); require(text() == "WAVE")
        var frameSize = 0
        var sampleRate = 0
        var channels = 0
        while (wave.filePointer + 8 <= wave.length()) {
            checkpoint()
            val id = text(); val size = little().toLong() and 0xffffffffL; val start = wave.filePointer
            require(start + size <= wave.length())
            if (id == "fmt ") {
                require(size >= 16 && java.lang.Short.reverseBytes(wave.readShort()).toInt() == 1)
                channels = java.lang.Short.reverseBytes(wave.readShort()).toInt()
                sampleRate = little(); wave.skipBytes(4)
                frameSize = java.lang.Short.reverseBytes(wave.readShort()).toInt()
                require(channels in 1..8 && frameSize == channels * 2 && java.lang.Short.reverseBytes(wave.readShort()).toInt() == 16)
            } else if (id == "data") {
                require(frameSize > 0 && size % frameSize == 0L)
                val leftBuffer = ByteArray(64 * 1024 / frameSize * frameSize)
                val rightBuffer = ByteArray(leftBuffer.size)
                var left = start; var right = start + size
                while (right - left >= frameSize * 2L) {
                    checkpoint()
                    val bytes = minOf(leftBuffer.size.toLong(), (right - left) / (2 * frameSize) * frameSize).toInt()
                    wave.seek(left); wave.readFully(leftBuffer, 0, bytes)
                    wave.seek(right - bytes); wave.readFully(rightBuffer, 0, bytes)
                    reverseFrames(leftBuffer, bytes, frameSize); reverseFrames(rightBuffer, bytes, frameSize)
                    wave.seek(left); wave.write(rightBuffer, 0, bytes)
                    wave.seek(right - bytes); wave.write(leftBuffer, 0, bytes)
                    left += bytes; right -= bytes
                }
                if (options != null && (options.fadeInMs > 0 || options.fadeOutMs > 0 || options.echoDelayMs > 0 || options.noiseGate)) {
                    val processor = MediaAudioEnvelopeProcessor(options.copy(startMs = 0, endMs = options.durationMs, speed = 1f))
                    try {
                        processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(sampleRate, channels, androidx.media3.common.C.ENCODING_PCM_16BIT))
                        processor.flush()
                        val input = java.nio.ByteBuffer.allocateDirect(leftBuffer.size).order(java.nio.ByteOrder.nativeOrder())
                        var position = start
                        while (position < start + size) {
                            checkpoint()
                            val bytes = minOf(leftBuffer.size.toLong(), start + size - position).toInt()
                            wave.seek(position); wave.readFully(leftBuffer, 0, bytes)
                            input.clear(); input.put(leftBuffer, 0, bytes); input.flip()
                            processor.queueInput(input)
                            val output = processor.output
                            require(output.remaining() == bytes)
                            wave.seek(position)
                            while (output.hasRemaining()) { checkpoint(); wave.channel.write(output) }
                            position += bytes
                        }
                        processor.queueEndOfStream()
                        check(processor.isEnded)
                    } finally { processor.reset() }
                }
                wave.fd.sync()
                return@use
            }
            wave.seek(start + size + size % 2)
        }
        error("Missing PCM data")
    }
    private fun reverseFrames(bytes: ByteArray, length: Int, frameSize: Int) {
        var left = 0; var right = length - frameSize
        while (left < right) {
            for (channelByte in 0 until frameSize) {
                val sample = bytes[left + channelByte]; bytes[left + channelByte] = bytes[right + channelByte]; bytes[right + channelByte] = sample
            }
            left += frameSize; right -= frameSize
        }
    }
}
