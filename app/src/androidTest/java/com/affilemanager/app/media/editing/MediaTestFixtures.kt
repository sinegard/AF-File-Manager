package com.affilemanager.app.media.editing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** Real platform-encoded, private disposable fixtures; no web downloads or user media. */
internal object MediaTestFixtures {
    private data class Sample(val bytes: ByteArray, val time: Long, val flags: Int)
    private data class Track(val format: MediaFormat, val samples: List<Sample>)
    fun wav(file: File, durationMs: Int = 3000) {
        val pcm = pcm(durationMs)
        file.outputStream().use { out ->
            fun little(value: Int, count: Int = 4) { repeat(count) { out.write(value ushr (it * 8) and 255) } }
            out.write("RIFF".toByteArray()); little(36 + pcm.size); out.write("WAVEfmt ".toByteArray()); little(16)
            little(1, 2); little(2, 2); little(48000); little(48000 * 4); little(4, 2); little(16, 2)
            out.write("data".toByteArray()); little(pcm.size); out.write(pcm)
        }
    }
    fun video(file: File, audio: Boolean = true) {
        val video = encodeVideo()
        val sound = if (audio) encodeAudio() else null
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val v = muxer.addTrack(video.format); val a = sound?.let { muxer.addTrack(it.format) }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val all = video.samples.map { v to it } + sound?.samples.orEmpty().map { requireNotNull(a) to it }
            all.sortedBy { it.second.time }.forEach { (track, sample) ->
                info.set(0, sample.bytes.size, sample.time, sample.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
            }
            muxer.stop()
        } finally { muxer.release() }
        check(file.length() > 1000)
    }
    private fun pcm(durationMs: Int): ByteArray {
        val result = ByteBuffer.allocate(durationMs * 48 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(durationMs * 48) { index -> val value = (sin(index * Math.PI * 2 * 440 / 48000) * 6000).toInt().toShort(); result.putShort(value); result.putShort(value) }
        return result.array()
    }
    private class Drain(private val codec: MediaCodec) {
        var format: MediaFormat? = null
        val samples = mutableListOf<Sample>()
        var ended = false
        val deadline = SystemClock.elapsedRealtime() + 30_000
        fun poll(wait: Long = 0) {
            check(SystemClock.elapsedRealtime() < deadline) { "Fixture codec deadline" }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, wait)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                    index < 0 -> return
                    else -> {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            samples += Sample(ByteArray(info.size).also(buffer::get), info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        fun result(): Track { while (!ended) poll(10_000); return Track(requireNotNull(format), samples) }
    }
    private fun encodeAudio(): Track {
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply { setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 128000) }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val drain = Drain(codec); val samples = pcm(3000); var offset = 0
            while (offset < samples.size) {
                drain.poll()
                val index = codec.dequeueInputBuffer(10_000)
                if (index < 0) continue
                val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                val count = minOf(buffer.remaining() / 4 * 4, samples.size - offset, 4096)
                buffer.put(samples, offset, count)
                codec.queueInputBuffer(index, 0, count, offset / 4 * 1_000_000L / 48000, 0); offset += count
            }
            var eos = false
            while (!eos) {
                drain.poll(); val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) { codec.queueInputBuffer(index, 0, 0, 3_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
            }
            return drain.result()
        } finally { runCatching { codec.stop() }; codec.release() }
    }
    private fun encodeVideo(): Track {
        val codec = MediaCodec.createEncoderByType("video/avc")
        var display = EGL14.EGL_NO_DISPLAY; var eglContext = EGL14.EGL_NO_CONTEXT; var eglSurface = EGL14.EGL_NO_SURFACE
        var input: android.view.Surface? = null
        try {
            codec.configure(MediaFormat.createVideoFormat("video/avc", 320, 240).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 500000); setInteger(MediaFormat.KEY_FRAME_RATE, 10); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface(); codec.start()
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
            val drain = Drain(codec)
            // Surface submission can block once the encoder's output queue fills.
            // Drain independently so fixture creation cannot deadlock in eglSwapBuffers.
            val encoded = AtomicReference<Track>()
            val failure = AtomicReference<Throwable>()
            val reader = Thread({
                try { encoded.set(drain.result()) } catch (error: Throwable) { failure.set(error) }
            }, "af-fixture-video-drain").apply { start() }
            repeat(30) { frame ->
                failure.get()?.let { throw it }
                GLES20.glViewport(0, 0, 320, 240); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(1f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(160, 0, 160, 240)
                GLES20.glClearColor(0f, 1f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, frame * 100_000_000L); check(EGL14.eglSwapBuffers(display, eglSurface))
            }
            codec.signalEndOfInputStream()
            reader.join(35_000)
            check(!reader.isAlive) { "Fixture drain deadline" }
            failure.get()?.let { throw it }
            return requireNotNull(encoded.get())
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            input?.release(); runCatching { codec.stop() }; codec.release()
        }
    }
}
