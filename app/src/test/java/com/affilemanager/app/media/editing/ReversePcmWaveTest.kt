package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class ReversePcmWaveTest {
    @get:Rule val folder = TemporaryFolder()
    private fun wave(frames: Int, channels: Int): ByteArray {
        val bytes = ByteBuffer.allocate(44 + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(48000).putInt(48000 * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(frames * channels * 2)
        repeat(frames) { frame -> repeat(channels) { channel -> bytes.putShort((frame * 3 + channel).toShort()) } }
        return bytes.array()
    }
    @Test fun oddAndEvenFrameCountsReverseWithoutSwappingChannelsOrChangingHeaders() {
        for (frames in listOf(1, 2, 3, 50000, 50001)) for (channels in listOf(1, 2)) {
            val original = wave(frames, channels)
            val file = folder.newFile("$frames-$channels.wav").apply { writeBytes(original) }
            ReversePcmWave.apply(file, {})
            val result = file.readBytes()
            assertArrayEquals(original.copyOfRange(0, 44), result.copyOfRange(0, 44))
            val actual = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { frame -> repeat(channels) { channel ->
                assertEquals(((frames - 1 - frame) * 3 + channel).toShort(), actual.getShort(44 + (frame * channels + channel) * 2))
            } }
        }
    }
    @Test fun cancellationBeforeWritesAndInvalidPcmDoNotChangeTheOwnedFile() {
        val before = wave(100, 2)
        val file = folder.newFile().apply { writeBytes(before) }
        assertThrows(CancellationException::class.java) { ReversePcmWave.apply(file, { throw CancellationException() }) }
        assertArrayEquals(before, file.readBytes())
        val invalid = before.clone().apply { this[34] = 24 }
        file.writeBytes(invalid)
        assertThrows(IllegalArgumentException::class.java) { ReversePcmWave.apply(file, {}) }
        assertArrayEquals(invalid, file.readBytes())
    }
}
