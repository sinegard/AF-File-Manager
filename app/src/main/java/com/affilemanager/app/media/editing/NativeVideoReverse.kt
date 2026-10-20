@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import com.affilemanager.app.editing.EditLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** Only a bounded frame chunk is decoded. Encoded samples retain the reversed VFR timeline. */
internal object NativeVideoReverse {
    private const val MAX_SAMPLES = 216001
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
    private const val FRAME_MEMORY = 32L * 1024 * 1024
    suspend fun export(context: Context, source: File, output: File,
        workspace: MediaWorkspace, bitrate: Int?, progress: (Int?) -> Unit) {
        val job = currentCoroutineContext()
        val sourceExtractor = MediaExtractor()
        val times = LongArray(MAX_SAMPLES)
        var count = 0
        val videoFormat: MediaFormat
        try {
            sourceExtractor.setDataSource(source.path)
            val track = (0 until sourceExtractor.trackCount).first { sourceExtractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true }
            videoFormat = sourceExtractor.getTrackFormat(track)
            sourceExtractor.selectTrack(track)
            while (sourceExtractor.sampleTime >= 0) {
                job.ensureActive(); require(count < MAX_SAMPLES) { "AF_MEDIA_TIMELINE_LIMIT" }
                times[count++] = sourceExtractor.sampleTime
                sourceExtractor.advance()
            }
        } finally { sourceExtractor.release() }
        require(count > 0)
        java.util.Arrays.sort(times, 0, count)
        for (n in 1 until count) require(times[n] > times[n-1]) { "Netinkamas išvesties failas" }
        val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)
        require(duration > times[count-1]) { "Netinkamas išvesties failas" }
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val perChunk = (FRAME_MEMORY / (width.toLong() * height * 2)).toInt().coerceIn(1, 256)
        require(width.toLong()*height*2 <= FRAME_MEMORY) { "Šiai raiškai nepakanka atminties" }
        // The API26 platform muxer ignores the empty EOS sample's last-frame
        // duration and duplicates the preceding VFR duration. Use the already
        // shipped Media3 writer with an explicit EOS duration on every API.
        val sink = BoundedMuxerOutput(output)
        val muxer = try {
            Mp4Muxer.Builder(sink).setSampleCopyingEnabled(false).setSampleBatchingEnabled(false)
                .setAttemptStreamableOutputEnabled(false)
                .setLastSampleDurationBehavior(Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
                .build()
        } catch(error:Throwable) { sink.close(); throw error }
        var written = 0
        var outputPts = 0L
        val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        try {
            var muxVideo = -1
            var last = count
            while (last > 0) {
                job.ensureActive()
                val first = (last-perChunk).coerceAtLeast(0)
                val raw = workspace.file("reverse.h264")
                val chunk = workspace.file("reverse-chunk.mp4")
                val end = if (last == count) duration else times[last]
                val args = NativeClipInput.arguments(source,times[first],end) + listOf("-map", "0:v:0", "-an",
                    "-vf", "format=yuv420p,reverse,setpts=PTS-STARTPTS", "-frames:v", "${last-first}",
                    "-c:v", "h264_mediacodec", "-ndk_codec", "1", "-bf", "0", "-b:v", "${bitrate ?: 4000000}",
                    "-fps_mode", "passthrough", "-f", "h264", raw.path)
                NativeMediaTools(context, workspace).ffmpeg(args, "video-reverse")
                // The elementary encoder avoids MediaCodec's global-header flush stall.
                // This intermediary rate is NOT the final timeline: original reversed
                // frame durations are assigned explicitly when writing every packet below.
                NativeMediaTools(context,workspace).ffmpeg(listOf("-framerate","60","-f","h264","-i",raw.path,
                    "-map","0:v:0","-c:v","copy","-f","mp4",chunk.path),"video-reverse-packets")
                require(raw.delete())
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(chunk.path)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true }
                    val actual = extractor.getTrackFormat(track)
                    require(actual.getInteger(MediaFormat.KEY_WIDTH) == width && actual.getInteger(MediaFormat.KEY_HEIGHT) == height) { "Išvesties raiška neatitinka pasirinkimo" }
                    if (muxVideo < 0) muxVideo = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(actual))
                    extractor.selectTrack(track)
                    var index = last-1
                    while (extractor.sampleTime >= 0) {
                        job.ensureActive(); require(index >= first) { "AF_MEDIA_FRAME_COUNT" }
                        buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                        require(size in 1..MAX_SAMPLE_BYTES)
                        val flags=if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)C.BUFFER_FLAG_KEY_FRAME else 0
                        buffer.position(0);buffer.limit(size)
                        muxer.writeSampleData(muxVideo, buffer, BufferInfo(outputPts,size,flags))
                        val next = if (index+1 == count) duration else times[index+1]
                        outputPts += next-times[index]
                        index--; written++; extractor.advance()
                        require(output.length() <= EditLimits.MAX_FILE_BYTES) { "Išvesties failas per didelis" }
                    }
                    require(index == first-1) { "AF_MEDIA_FRAME_COUNT: expected ${last-first}, got ${last-1-index}" }
                } finally { extractor.release(); require(chunk.delete()) }
                progress(written * 90 / count)
                last = first
            }
            // An empty EOS sample supplies the duration of the last VFR frame.
            muxer.writeSampleData(muxVideo, ByteBuffer.allocate(0), BufferInfo(outputPts,0,C.BUFFER_FLAG_END_OF_STREAM))
            require(written == count)
        } finally { muxer.close() }
    }
}
