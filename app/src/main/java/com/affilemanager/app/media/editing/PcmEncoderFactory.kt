@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.media.MediaCodec
import android.media.metrics.LogSessionId
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media3's audio graph already outputs PCM; WAV needs no lossy encode/decode round trip. */
internal class PcmEncoderFactory(private val delegate: Codec.EncoderFactory, private val pcm: Boolean) : Codec.EncoderFactory {
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        if (pcm) PcmCodec(format) else delegate.createForAudioEncoding(format, logSessionId)
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec = delegate.createForVideoEncoding(format, logSessionId)
    override fun audioNeedsEncoding() = true
    override fun videoNeedsEncoding() = true
}

internal class PcmCodec(requested: Format) : Codec {
    private val format = requested.buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
    private val buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())
    private val info = MediaCodec.BufferInfo()
    private var ready = false
    private var end = false
    override fun getConfigurationFormat(): Format = format
    override fun getName() = "AF_PCM_PASSTHROUGH"
    override fun getInputSurface(): Surface = error("PCM has no surface")
    override fun maybeDequeueInputBuffer(input: DecoderInputBuffer): Boolean {
        if (ready || end) return false
        buffer.clear(); input.data = buffer; return true
    }
    override fun queueInputBuffer(input: DecoderInputBuffer) {
        check(!ready && input.data === buffer)
        end = input.isEndOfStream
        info.set(buffer.position(), buffer.remaining(), input.timeUs, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        ready = buffer.hasRemaining()
    }
    override fun signalEndOfInputStream() { end = true }
    override fun getInputFormat(): Format = format
    override fun getOutputFormat(): Format = format
    override fun getOutputBuffer(): ByteBuffer? = if (ready) buffer else null
    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (ready) info else null
    override fun releaseOutputBuffer(render: Boolean) { ready = false }
    override fun releaseOutputBuffer(renderTimeUs: Long) { ready = false }
    override fun isEnded() = end && !ready
    override fun release() { ready = false; end = true }
}
