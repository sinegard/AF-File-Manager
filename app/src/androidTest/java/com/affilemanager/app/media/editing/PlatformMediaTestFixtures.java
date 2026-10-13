package com.affilemanager.app.media.editing;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.*;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only fixture; independent of the shipping APK's shrunk Kotlin runtime. */
public final class PlatformMediaTestFixtures {
    private static final class Sample {
        final byte[] bytes; final long time; final int flags;
        Sample(byte[] bytes, long time, int flags) { this.bytes = bytes; this.time = time; this.flags = flags; }
    }
    private static final class Track {
        final MediaFormat format; final List<Sample> samples;
        Track(MediaFormat format, List<Sample> samples) { this.format = format; this.samples = samples; }
    }
    private static final class IndexedSample {
        final int track; final Sample sample;
        IndexedSample(int track, Sample sample) { this.track = track; this.sample = sample; }
    }
    public static void video(File file) throws Exception {
        Track video = encodeVideo(), sound = encodeAudio();
        MediaMuxer muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int v = muxer.addTrack(video.format), a = muxer.addTrack(sound.format);
            List<IndexedSample> all = new ArrayList<>();
            for (Sample sample : video.samples) all.add(new IndexedSample(v, sample));
            for (Sample sample : sound.samples) all.add(new IndexedSample(a, sample));
            all.sort(Comparator.comparingLong(item -> item.sample.time));
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (IndexedSample item : all) {
                Sample sample = item.sample;
                info.set(0, sample.bytes.length, sample.time, sample.flags);
                muxer.writeSampleData(item.track, ByteBuffer.wrap(sample.bytes), info);
            }
            muxer.stop();
        } finally { muxer.release(); }
        if (file.length() <= 1000) throw new AssertionError("Native video fixture is empty");
    }
    private static final class Drain {
        final MediaCodec codec; final List<Sample> samples = new ArrayList<>();
        final long deadline = SystemClock.elapsedRealtime() + 30000;
        MediaFormat format; boolean ended;
        Drain(MediaCodec codec) { this.codec = codec; }
        void poll(long wait) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Fixture codec deadline");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (;;) {
                int index = codec.dequeueOutputBuffer(info, wait);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.getOutputFormat();
                else if (index < 0) return;
                else {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null) throw new AssertionError("Fixture codec buffer absent");
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size]; buffer.get(bytes);
                        samples.add(new Sample(bytes, info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) ended = true;
                    codec.releaseOutputBuffer(index, false);
                }
            }
        }
        Track result() {
            while (!ended) poll(10000);
            if (format == null) throw new AssertionError("Fixture codec format absent");
            return new Track(format, samples);
        }
    }
    private static Track encodeAudio() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        try {
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            Drain drain = new Drain(codec);
            ByteBuffer pcm = ByteBuffer.allocate(3000 * 48 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int frame = 0; frame < 3000 * 48; frame++) {
                short value = (short) (Math.sin(frame * Math.PI * 2 * 440 / 48000) * 6000);
                pcm.putShort(value); pcm.putShort(value);
            }
            byte[] samples = pcm.array();
            for (int offset = 0; offset < samples.length;) {
                drain.poll(0);
                int index = codec.dequeueInputBuffer(10000);
                if (index < 0) continue;
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new AssertionError("Audio fixture input absent");
                buffer.clear();
                int count = Math.min(Math.min(buffer.remaining() / 4 * 4, samples.length - offset), 4096);
                buffer.put(samples, offset, count);
                codec.queueInputBuffer(index, 0, count, offset / 4 * 1000000L / 48000, 0); offset += count;
            }
            for (;;) {
                drain.poll(0); int index = codec.dequeueInputBuffer(10000);
                if (index >= 0) { codec.queueInputBuffer(index, 0, 0, 3000000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break; }
            }
            return drain.result();
        } finally { try { codec.stop(); } catch (IllegalStateException ignored) { } codec.release(); }
    }
    private static Track encodeVideo() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        Surface input = null; Thread reader = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 320, 240);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000); format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface(); codec.start();
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "EGL initialization");
            EGLConfig[] configs = new EGLConfig[1];
            require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE},
                0, configs, 0, 1, new int[1], 0), "EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
            require(EGL14.eglMakeCurrent(display, surface, surface, context), "EGL current");
            Drain drain = new Drain(codec);
            AtomicReference<Track> encoded = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            reader = new Thread(() -> {
                try { encoded.set(drain.result()); } catch (Throwable error) { failure.set(error); }
            }, "af-platform-fixture-drain");
            reader.start();
            for (int frame = 0; frame < 30; frame++) {
                if (failure.get() != null) throw new AssertionError("Native fixture drain failed", failure.get());
                GLES20.glViewport(0, 0, 320, 240); GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glClearColor(1f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(160, 0, 160, 240);
                GLES20.glClearColor(0f, 1f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                require(EGLExt.eglPresentationTimeANDROID(display, surface, frame * 100000000L), "EGL presentation");
                require(EGL14.eglSwapBuffers(display, surface), "EGL frame submission");
            }
            codec.signalEndOfInputStream(); reader.join(35000);
            require(!reader.isAlive(), "Native fixture drain deadline");
            if (failure.get() != null) throw new AssertionError("Native fixture drain failed", failure.get());
            if (encoded.get() == null) throw new AssertionError("Native fixture output absent");
            return encoded.get();
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            if (input != null) input.release();
            try { codec.stop(); } catch (IllegalStateException ignored) { }
            if (reader != null && reader.isAlive()) { reader.interrupt(); reader.join(2000); }
            codec.release();
            if (reader != null && reader.isAlive()) throw new AssertionError("Native fixture worker leaked");
        }
    }
    private static void require(boolean condition, String action) { if (!condition) throw new AssertionError(action); }
    private PlatformMediaTestFixtures() { }
}
