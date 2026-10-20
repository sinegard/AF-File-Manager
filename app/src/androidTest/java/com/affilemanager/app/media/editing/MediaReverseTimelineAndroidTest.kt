package com.affilemanager.app.media.editing

import android.content.Context
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

class MediaReverseTimelineAndroidTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun lastVfrDurationIsPreservedByBothTheWriterAndContainerRemux()=runBlocking<Unit> {
        val root=File(context.cacheDir,"media-vfr-duration-${UUID.randomUUID()}").apply{check(mkdir())}
        val source=root.resolve("source.mp4").also{MediaTestFixtures.video(it,false,listOf(0L,40000L,120000L,240000L))}
        try {
            MediaWorkspace.create(context).use {work->
                val direct=work.file("direct.mp4");val remux=work.file("remux.mp4")
                NativeVideoReverse.export(context,source,direct,work,null){}
                NativeMediaTools(context,work).ffmpeg(listOf("-i",direct.path,"-map","0:v:0","-c","copy","-f","mp4",remux.path),"test-duration-remux")
                val sourceUs=videoDuration(source);val directUs=videoDuration(direct);val remuxUs=videoDuration(remux)
                println("AF_VFR_DURATION_STAGES source=$sourceUs writer=$directUs remux=$remuxUs")
                if(abs(sourceUs-directUs)>1000 || abs(sourceUs-remuxUs)>1000) {
                    val evidence=File(context.getExternalFilesDir("validation"),"vfr-duration-${UUID.randomUUID()}").apply{check(mkdir())}
                    source.copyTo(File(evidence,"source.mp4"));direct.copyTo(File(evidence,"writer.mp4"));remux.copyTo(File(evidence,"remux.mp4"))
                }
                assertTrue("Writer changed last duration",abs(sourceUs-directUs)<=1000)
                assertTrue("Remux changed last duration",abs(sourceUs-remuxUs)<=1000)
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun gifReverseReallyChangesTheVisibleFrameOrder()=runBlocking<Unit> {
        val root=File(context.cacheDir,"media-gif-reverse-${UUID.randomUUID()}").apply{check(mkdir())}
        val source=root.resolve("colors.mp4").also{MediaTestFixtures.video(it,false,listOf(0L,100000L,200000L))}
        try {
            val info=MediaExportEngine.info(context,Uri.fromFile(source),source.length())
            val result=MediaExportEngine(context).export(source,Uri.fromFile(source),info,MediaEditOptions(MediaExportFormat.GIF,0,info.durationMs,reverseVideo=true,height=240,frameRate=10),"reverse")
            try {
                val movie=requireNotNull(android.graphics.Movie.decodeFile(result.file.path))
                val image=android.graphics.Bitmap.createBitmap(movie.width(),movie.height(),android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    movie.setTime(0);movie.draw(android.graphics.Canvas(image),0f,0f);assertTrue(Color.red(image.getPixel(image.width/2,image.height/2))>200)
                    movie.setTime(movie.duration()-1);movie.draw(android.graphics.Canvas(image),0f,0f);assertTrue(Color.blue(image.getPixel(image.width/2,image.height/2))>200)
                }finally{image.recycle()}
            }finally{check(result.file.delete())}
        }finally{root.deleteRecursively()}
    }
    @Test fun reverseVideoKeepsAllVfrFramesDurationsAndOrderAcrossChunks()=runBlocking<Unit> {
        val root=File(context.cacheDir,"media-vfr-${UUID.randomUUID()}").apply{check(mkdir())}
        val times=buildList {var time=0L;repeat(240){add(time);time+=listOf(40000L,80000L,120000L)[it%3]}}
        val source=root.resolve("vfr.mp4").also{MediaTestFixtures.video(it,false,times)}
        try {
            val original=timeline(source);assertEquals(240,original.size)
            val info=MediaExportEngine.info(context,Uri.fromFile(source),source.length())
            val result=MediaExportEngine(context).export(source,Uri.fromFile(source),info,MediaEditOptions(MediaExportFormat.MP4,0,info.durationMs,reverseVideo=true),"reverse")
            try {
                val actual=timeline(result.file);assertEquals(240,actual.size)
                val duration=videoDuration(source)
                val lengths=original.mapIndexed {index,time->(original.getOrNull(index+1)?:duration)-time}.asReversed()
                var expected=0L
                actual.forEachIndexed {index,time->assertTrue("VFR frame $index expected $expected got $time",abs(time-expected)<=1000);expected+=lengths[index]}
                println("AF_VFR_DURATION source=$duration output=${videoDuration(result.file)} expected=$expected last=${actual.last()}")
                assertTrue("Last VFR frame duration changed",abs(videoDuration(result.file)-expected)<=1000)
                // API26's thumbnail retriever also returns null for the exact last
                // frame of our untouched fixture. Decode ALL frames instead of
                // weakening the final-frame assertion or trusting a thumbnail.
                val decoded=decodeFrameMarkers(result.file)
                assertEquals(240,decoded.size)
                decoded.forEachIndexed { index, frame ->
                    assertTrue("Decoded frame timestamp $index",abs(actual[index]-frame.pts)<=1000)
                    assertEquals("Decoded frame order at $index",239-index,frame.marker)
                }
                assertTrue("Last original frame must be first",Color.red(decoded.first().color)>200 && Color.blue(decoded.first().color)<50)
                assertTrue("First original frame must be last",Color.blue(decoded.last().color)>200 && Color.red(decoded.last().color)<50)
                val reader=MediaMetadataRetriever()
                try {
                    reader.setDataSource(result.file.path)
                    val first=requireNotNull(reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertTrue("Last original frame must be first",Color.red(first.getPixel(160,120))>200 && Color.blue(first.getPixel(160,120))<50)
                    }finally{first.recycle()}
                }finally{reader.release()}
                println("AF_VFR_ALL_240_FRAMES_DECODED_IN_REVERSE_ORDER OK")
                println("AF_NATIVE_VFR_240_FRAMES_TWO_CHUNKS OK")
            }finally{check(result.file.delete())}
        }finally{root.deleteRecursively()}
    }
    @Test fun losslessReverseReversesFramesNotStereoChannelsInTheRealExport()=runBlocking<Unit> {
        val root=File(context.cacheDir,"media-pcm-reverse-${UUID.randomUUID()}").apply{check(mkdir())}
        val source=root.resolve("ramp.wav").also{MediaTestFixtures.wav(it,1000)}
        val pcm=ByteBuffer.allocate(48000*4).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(48000){putShort((it%30000).toShort());putShort((-it%30000).toShort())}}.array()
        java.io.RandomAccessFile(source,"rw").use{it.seek(44);it.write(pcm)}
        try {
            val info=MediaExportEngine.info(context,Uri.fromFile(source),source.length())
            val result=MediaExportEngine(context).export(source,Uri.fromFile(source),info,MediaEditOptions(MediaExportFormat.FLAC,0,1000,reverseAudio=true),"reverse")
            try {
                MediaWorkspace.create(context).use {work->
                    val decoded=work.file("decoded.pcm")
                    NativeMediaTools(context,work).ffmpeg(listOf("-i",result.file.path,"-vn","-c:a","pcm_s16le","-f","s16le",decoded.path),"test-reverse-decode")
                    val actual=decoded.readBytes();assertEquals(pcm.size,actual.size)
                    for(frame in 0 until 48000)for(channelByte in 0..3)assertEquals(pcm[(47999-frame)*4+channelByte],actual[frame*4+channelByte])
                }
            }finally{check(result.file.delete())}
        }finally{root.deleteRecursively()}
    }
    private fun timeline(file:File):List<Long> {
        val extractor=MediaExtractor()
        try{extractor.setDataSource(file.path);val track=(0 until extractor.trackCount).first{extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/")};extractor.selectTrack(track)
            val values=mutableListOf<Long>();while(extractor.sampleTime>=0){values+=extractor.sampleTime;extractor.advance()};return values.sorted()
        }finally{extractor.release()}
    }
    private fun videoDuration(file:File):Long {
        val extractor=MediaExtractor()
        try{extractor.setDataSource(file.path);return (0 until extractor.trackCount).map(extractor::getTrackFormat).first{it.getString("mime")!!.startsWith("video/")}.getLong("durationUs")}finally{extractor.release()}
    }
    private data class DecodedFrame(val pts:Long,val marker:Int,val color:Int)
    private fun decodeFrameMarkers(file:File):List<DecodedFrame> {
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")}
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also {decoder=it}
            codec.configure(format,null,null,0);codec.start()
            val info=MediaCodec.BufferInfo();val frames=ArrayList<DecodedFrame>(240)
            var inputEnded=false;var outputEnded=false
            val deadline=android.os.SystemClock.elapsedRealtime()+30000
            while(!outputEnded) {
                check(android.os.SystemClock.elapsedRealtime()<deadline){"Real VFR decoder timed out"}
                if(!inputEnded) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val data=requireNotNull(codec.getInputBuffer(index));data.clear()
                        val count=extractor.readSampleData(data,0)
                        if(count<0) {codec.queueInputBuffer(index,0,0,format.getLong(MediaFormat.KEY_DURATION),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else {codec.queueInputBuffer(index,0,count,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index>=0) {
                    try {
                        if(info.size>0) {
                            check(frames.size<240){"Extra decoded VFR frame"}
                            requireNotNull(codec.getOutputImage(index)).use {image->
                                val crop=image.cropRect
                                fun component(plane:Int,x:Int,y:Int):Int {
                                    val p=image.planes[plane];val bytes=p.buffer
                                    return bytes.get(bytes.position()+y*p.rowStride+x*p.pixelStride).toInt() and 255
                                }
                                var marker=0
                                repeat(8){bit->if(component(0,crop.left+bit*40+20,crop.top+220)>127)marker=marker or (1 shl bit)}
                                val x=crop.left+160;val y=crop.top+120
                                val luminance=component(0,x,y)-16;val u=component(1,x/2,y/2)-128;val v=component(2,x/2,y/2)-128
                                fun channel(value:Int)=(value shr 8).coerceIn(0,255)
                                val color=Color.rgb(channel(298*luminance+409*v+128),channel(298*luminance-100*u-208*v+128),channel(298*luminance+516*u+128))
                                frames+=DecodedFrame(info.presentationTimeUs,marker,color)
                            }
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    }finally{codec.releaseOutputBuffer(index,false)}
                }
            }
            return frames
        }finally {decoder?.let {runCatching{it.stop()};it.release()};extractor.release()}
    }
}
