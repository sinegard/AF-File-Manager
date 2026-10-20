@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

class MediaAudioEffectsAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun process(options: MediaEditOptions, channels: Int, samples: ShortArray): ShortArray {
        val processor = MediaAudioEnvelopeProcessor(options)
        try {
            processor.configure(AudioProcessor.AudioFormat(8000, channels, C.ENCODING_PCM_16BIT)); processor.flush()
            val result = ShortArray(samples.size)
            var position = 0
            while (position < samples.size) {
                val count = minOf(400 * channels, samples.size - position)
                val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
                for (index in position until position + count) buffer.putShort(samples[index])
                buffer.flip(); processor.queueInput(buffer)
                val output = processor.output.order(ByteOrder.nativeOrder())
                assertEquals(count * 2, output.remaining())
                repeat(count) { result[position + it] = output.short }
                position += count
            }
            processor.queueEndOfStream(); assertTrue(processor.isEnded)
            return result
        } finally { processor.reset() }
    }
    @Test fun streamingFadeEchoAndNoiseGateChangePcmWithoutMixingChannels() {
        val base = MediaEditOptions(MediaExportFormat.WAV, 0, 1000)
        val fade = process(base.copy(fadeInMs = 100, fadeOutMs = 100), 1, ShortArray(8000) { 10000 })
        assertEquals(0, fade.first().toInt()); assertTrue(abs(fade.last().toInt()) < 20)
        assertEquals(5000, fade[400].toInt()); assertEquals(10000, fade[4000].toInt())
        val impulse = ShortArray(16000).apply { this[0] = 10000; this[1] = 20000 }
        val echo = process(base.copy(echoDelayMs = 10), 2, impulse)
        assertEquals(3000, echo[160].toInt()); assertEquals(6000, echo[161].toInt())
        assertEquals(0, echo[320].toInt()) // Feed-forward echo, not an infinite feedback loop.
        val gate = process(base.copy(noiseGate = true), 1, ShortArray(8000) { 200 })
        assertTrue(gate.all { abs(it.toInt()) <= 1 })
        val loud = process(base.copy(noiseGate = true), 1, ShortArray(8000) { 10000 })
        assertTrue(loud[4000] > 9900)
    }
    private fun pcm(file: File): ShortArray = RandomAccessFile(file, "r").use { input ->
        input.seek(12)
        while (input.filePointer + 8 <= input.length()) {
            val id = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(input.readInt()); val start = input.filePointer
            require(size >= 0 && start + size <= input.length())
            if (id == "data") return@use ShortArray(size / 2) { java.lang.Short.reverseBytes(input.readShort()) }
            input.seek(start + size + size % 2)
        }
        error("Missing PCM data")
    }
    @Test fun realExportsReversePcmAndApplyFadesAtTheCorrectEndAfterSpeedChange() = runBlocking<Unit> {
        val root = File(context.cacheDir, "effects-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("original.wav").also { MediaTestFixtures.wav(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        val before = digest.digest(source.readBytes())
        try {
            val uri = Uri.fromFile(source); val info = MediaExportEngine.info(context, uri, source.length())
            suspend fun export(options: MediaEditOptions) = MediaExportEngine(context).export(source, uri, info, options, "effects")
            val plain = export(MediaEditOptions(MediaExportFormat.WAV, 0, 2000))
            val reversed = export(MediaEditOptions(MediaExportFormat.WAV, 0, 2000, reverseAudio = true))
            try {
                val expected = pcm(plain.file); val actual = pcm(reversed.file)
                assertEquals(expected.size, actual.size)
                val channels = requireNotNull(MediaExportEngine.info(context, Uri.fromFile(plain.file), plain.file.length()).audioChannels)
                repeat(expected.size / channels) { frame -> repeat(channels) { channel ->
                    assertEquals(expected[(expected.size / channels - 1 - frame) * channels + channel], actual[frame * channels + channel])
                } }
            } finally { plain.file.delete(); reversed.file.delete() }
            for (reverse in listOf(false, true)) {
                val faded = export(MediaEditOptions(MediaExportFormat.WAV, 0, 2000, speed = 2f,
                    fadeInMs = 200, fadeOutMs = 200, reverseAudio = reverse))
                try {
                    val samples = pcm(faded.file)
                    assertTrue(abs(faded.durationMs - 1000) <= 100)
                    fun level(from: Int, count: Int) = samples.drop(from).take(count).sumOf { abs(it.toInt()).toLong() } / count.toDouble()
                    val edge = minOf(480, samples.size / 10)
                    val middle = level(samples.size / 2, edge)
                    assertTrue("Fade-in missing after reverse=$reverse", level(0, edge) < middle * .15)
                    assertTrue("Fade-out missing after reverse=$reverse", level(samples.size - edge, edge) < middle * .15)
                } finally { faded.file.delete() }
            }
            assertArrayEquals(before, digest.digest(source.readBytes()))
        } finally { root.deleteRecursively() }
    }
}
