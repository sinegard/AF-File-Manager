@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Streaming PCM only. No whole-track buffers or new codec dependency. */
internal class MediaAudioEnvelopeProcessor(private val options: MediaEditOptions) : BaseAudioProcessor() {
    private var frame = 0L
    private var echo = FloatArray(0)
    private var echoIndex = 0
    private var gate = 0f
    private val samples = FloatArray(8)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) || input.channelCount !in 1..8 || input.sampleRate !in 8000..192000) {
            throw AudioProcessor.UnhandledAudioFormatException(input)
        }
        return input
    }
    override fun onFlush() {
        frame = 0; echoIndex = 0; gate = 0f
        // EditedMediaItem's speed stage runs before these PCM effects.
        val delayFrames = (inputAudioFormat.sampleRate * options.echoDelayMs.toDouble() / 1000).roundToLong()
        val count = delayFrames * inputAudioFormat.channelCount
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        require(count <= MAX_ECHO_BYTES / 4 && count * 4 <= available - available / 4) { "Audio effect memory limit reached" }
        echo = FloatArray(count.toInt())
    }
    override fun onReset() { echo = FloatArray(0); frame = 0; echoIndex = 0 }
    override fun queueInput(input: ByteBuffer) {
        val output = replaceOutputBuffer(input.remaining())
        val channels = inputAudioFormat.channelCount
        val floating = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = channels * if (floating) 4 else 2
        require(input.remaining() % bytesPerFrame == 0)
        val sampleRate = inputAudioFormat.sampleRate
        val durationMs = options.durationMs
        val fadeIn = options.fadeInMs
        val fadeOut = options.fadeOutMs
        val attack = 1f - exp(-1.0 / (sampleRate * .005)).toFloat()
        val release = 1f - exp(-1.0 / (sampleRate * .05)).toFloat()
        while (input.hasRemaining()) {
            var level = 0f
            for (channel in 0 until channels) {
                val sample = if (floating) input.float else input.short.toFloat() / 32768f
                samples[channel] = sample
                level = maxOf(level, abs(sample))
            }
            if (options.noiseGate) {
                val target = if (level >= .02f) 1f else 0f
                gate += (target - gate) * if (target > gate) attack else release
            }
            val elapsedMs = frame * 1000.0 / sampleRate
            var gain = 1f
            if (fadeIn > 0) gain = minOf(gain, (elapsedMs / fadeIn).toFloat().coerceIn(0f, 1f))
            if (fadeOut > 0) gain = minOf(gain, ((durationMs - elapsedMs) / fadeOut).toFloat().coerceIn(0f, 1f))
            if (options.noiseGate) gain *= gate
            for (channel in 0 until channels) {
                var sample = samples[channel]
                if (echo.isNotEmpty()) {
                    val delayed = echo[echoIndex]
                    echo[echoIndex] = sample
                    echoIndex = (echoIndex + 1) % echo.size
                    sample += delayed * .3f
                }
                sample = (sample * gain).coerceIn(-1f, 1f)
                if (floating) output.putFloat(sample) else output.putShort((sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            frame++
        }
        output.flip()
    }
    companion object { private const val MAX_ECHO_BYTES = 4L * 1024 * 1024 }
}
