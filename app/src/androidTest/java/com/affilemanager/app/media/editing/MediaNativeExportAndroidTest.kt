package com.affilemanager.app.media.editing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** Calls the app export entry point, then independently decodes each actual output. */
class MediaNativeExportAndroidTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun root()=File(context.cacheDir,"native-editor-${UUID.randomUUID()}").apply {check(mkdir())}
    private fun cover():ByteArray {
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        return try {java.io.ByteArrayOutputStream().use {check(image.compress(Bitmap.CompressFormat.JPEG,90,it));it.toByteArray()}}finally{image.recycle()}
    }
    @Test fun fftNoiseReductionChangesTheActualAfExportButRetainsTheTone()=runBlocking<Unit> {
        val root=root();val source=root.resolve("noise.wav").also{MediaTestFixtures.wav(it)}
        val random=java.util.Random(253)
        val samples=ShortArray(144000){index->(random.nextInt(3001)-1500+if(index>=96000)(kotlin.math.sin(index*Math.PI*2*440/48000)*6000).toInt()else 0).toShort()}
        java.io.RandomAccessFile(source,"rw").use{out->out.seek(44);samples.forEach {value->repeat(2){out.write(value.toInt() and 255);out.write(value.toInt() ushr 8 and 255)}}}
        val before=digest(source)
        try {
            val uri=Uri.fromFile(source);val info=MediaExportEngine.info(context,uri,source.length())
            val result=MediaExportEngine(context).export(source,uri,info,MediaEditOptions(MediaExportFormat.WAV,0,3000,noiseReduction=true),"denoised")
            try {MediaWorkspace.create(context).use {work->
                val pcm=work.file("denoised.pcm")
                NativeMediaTools(context,work).ffmpeg(listOf("-i",result.file.path,"-vn","-c:a","pcm_s16le","-f","s16le",pcm.path),"test-denoise-decode")
                val bytes=ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val actual=ShortArray(bytes.remaining()/2){bytes.short}
                fun level(values:ShortArray,from:Int,to:Int,channels:Int):Double {var energy=0.0;for(n in from until to){val value=values[n*channels].toDouble();energy+=value*value};return sqrt(energy/(to-from))}
                val originalNoise=level(samples,48000,72000,1);val reducedNoise=level(actual,48000,72000,2)
                assertTrue("FFT did not reduce actual noise: $originalNoise -> $reducedNoise",reducedNoise<originalNoise*.8)
                assertTrue("FFT removed the actual tone",level(actual,112000,132000,2)>1800)
                println("AF_NATIVE_FFT_SIGNAL $originalNoise -> $reducedNoise")
            }}finally{check(result.file.delete())}
            assertEquals(before,digest(source))
        }finally{root.deleteRecursively()}
    }
    @Test fun everyAudioFormatIsReallyEncodedTaggedAndDecodedThroughAf()=runBlocking<Unit> {
        val root=root();val source=root.resolve("original.wav").also {MediaTestFixtures.wav(it)};val before=digest(source)
        val tags=MediaTags("Title 音楽","Artist AF","Album AF","Album artist AF","Composer AF","Publisher AF","2026","Genre AF","line one\nline two","Comment AF")
        try {
            val uri=Uri.fromFile(source);val info=MediaExportEngine.info(context,uri,source.length(),source)
            for(format in MediaExportFormat.entries.filterNot {it.video}) {
                val result=try{MediaExportEngine(context).export(source,uri,info,MediaEditOptions(format,250,2250,tags=tags,sampleRate=44100,channels=2),"encoded",cover())}
                    catch(e:NativeMediaFailure){throw AssertionError("$format: ${e.diagnostic}",e)}
                try {
                    MediaWorkspace.create(context).use {work->
                        val actual=NativeMediaProbe.read(context,result.file,work)
                        assertEquals(format.name,44100,actual.audio?.rate);assertEquals(2,actual.audio?.channels)
                        assertTrue("$format duration ${actual.durationMs}",abs(actual.durationMs-2000)<=150)
                        if(format!=MediaExportFormat.WAV)assertEquals("$format title",tags.title,actual.editableTags().title)
                        if(format in setOf(MediaExportFormat.MP3,MediaExportFormat.FLAC,MediaExportFormat.WMA)) {
                            assertEquals("$format metadata",tags,actual.editableTags())
                            assertTrue("$format missing decoded cover",actual.tracks.any {it.attachedPicture && it.width==32 && it.height==32})
                        }
                        val decoded=work.file("decoded.pcm")
                        NativeMediaTools(context,work).ffmpeg(listOf("-i",result.file.path,"-map","0:a:0","-vn","-c:a","pcm_s16le","-f","s16le",decoded.path),"test-decode")
                        assertTrue("$format real audio",decoded.length()>44100*4)
                        val floats=decoded.readBytes().let {bytes->ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)}
                        var energy=0.0;var count=0;while(floats.remaining()>=2){val value=floats.short.toDouble();energy+=value*value;count++}
                        assertTrue("$format audio is silent",sqrt(energy/count)>1000)
                    }
                    assertEquals(before,digest(source));println("AF_NATIVE_AUDIO_$format OK")
                }finally{check(result.file.delete())}
            }
            assertEquals(0,File(context.cacheDir,"media-work").list()?.size?:0)
        }finally{root.deleteRecursively()}
    }
    @Test fun videoContainersHaveRealCropTrimSpeedAudioAndReverse()=runBlocking<Unit> {
        val root=root();val source=root.resolve("video.mp4").also {MediaTestFixtures.video(it)};val before=digest(source)
        try {
            val uri=Uri.fromFile(source);val info=MediaExportEngine.info(context,uri,source.length(),source)
            for(format in listOf(MediaExportFormat.MP4,MediaExportFormat.MKV,MediaExportFormat.MOV)) {
                val options=MediaEditOptions(format,500,2500,speed=2f,volume=.5f,crop=MediaCrop(.5f,0f,.5f,1f),height=240,frameRate=10,
                    videoBitrate=500000,reverseAudio=true,reverseVideo=true,noiseReduction=true)
                val result=try{MediaExportEngine(context).export(source,uri,info,options,"reversed")}
                    catch(e:NativeMediaFailure){throw AssertionError("$format: ${e.diagnostic}",e)}
                try {
                    MediaWorkspace.create(context).use {work->
                        val actual=NativeMediaProbe.read(context,result.file,work)
                        assertEquals(160,actual.video?.width);assertEquals(240,actual.video?.height);assertNotNull(actual.audio)
                        assertTrue(abs(actual.durationMs-1000)<=150)
                        val reader=MediaMetadataRetriever()
                        try {
                            reader.setDataSource(result.file.path)
                            val frame=requireNotNull(reader.getFrameAtTime(200000,MediaMetadataRetriever.OPTION_CLOSEST))
                            try{assertEquals(160,frame.width);assertEquals(240,frame.height);assertTrue(Color.green(frame.getPixel(80,120))>150)}finally{frame.recycle()}
                        }finally{reader.release()}
                    }
                    assertEquals(before,digest(source));println("AF_NATIVE_VIDEO_$format OK")
                }finally{check(result.file.delete())}
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun reversedAudioAndSlowerSpeedAreAppliedToEveryNewAudioFormat()=runBlocking<Unit> {
        val root=root();val source=root.resolve("source.wav").also {MediaTestFixtures.wav(it)};val uri=Uri.fromFile(source)
        try {
            val info=MediaExportEngine.info(context,uri,source.length())
            for(format in listOf(MediaExportFormat.MP3,MediaExportFormat.FLAC,MediaExportFormat.WMA,MediaExportFormat.M4A)) {
                val result=MediaExportEngine(context).export(source,uri,info,MediaEditOptions(format,500,1500,speed=.5f,reverseAudio=true,fadeInMs=250,fadeOutMs=250,echoDelayMs=100,noiseGate=true,noiseReduction=true),"reverse")
                try{assertTrue("$format duration ${result.durationMs}",abs(result.durationMs-2000)<200)}finally{check(result.file.delete())}
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun trackCopyDoesNotChangeEncodedVideoAndWmaCanBeEditedAgain()=runBlocking<Unit> {
        val root=root();val video=root.resolve("source.mp4").also {MediaTestFixtures.video(it)}
        try {
            val uri=Uri.fromFile(video);val info=MediaExportEngine.info(context,uri,video.length())
            for(format in listOf(MediaExportFormat.MKV,MediaExportFormat.MOV,MediaExportFormat.MP4)) {
                val result=MediaExportEngine(context).export(video,uri,info,MediaEditOptions(format,0,info.durationMs,copyTracks=true),"copy")
                try {assertEquals(videoPackets(video),videoPackets(result.file))}finally{check(result.file.delete())}
            }
            val wma=MediaExportEngine(context).export(video,uri,info,MediaEditOptions(MediaExportFormat.WMA,0,2000),"source")
            try {
                val wmaInfo=MediaExportEngine.info(context,Uri.fromFile(wma.file),wma.file.length(),wma.file)
                assertTrue(wmaInfo.hasAudio && !wmaInfo.hasVideo)
                val edited=MediaExportEngine(context).export(wma.file,Uri.fromFile(wma.file),wmaInfo,MediaEditOptions(MediaExportFormat.MP3,250,1500),"edited")
                try{assertTrue(edited.file.length()>1000)}finally{check(edited.file.delete())}
            }finally{check(wma.file.delete())}
        }finally{root.deleteRecursively()}
    }
    @Test fun trackCopyDoesNotDiscardAnAdditionalAudioTrack()=runBlocking<Unit> {
        val root=root();val original=root.resolve("original.mp4").also{MediaTestFixtures.video(it)}
        try {
            val source=root.resolve("two-audio.mkv")
            MediaWorkspace.create(context).use {NativeMediaTools(context,it).ffmpeg(listOf("-i",original.path,"-map","0:v:0","-map","0:a:0","-map","0:a:0","-c","copy","-f","matroska",source.path),"test-multi-track")}
            val info=MediaExportEngine.info(context,Uri.fromFile(source),source.length(),source)
            val copied=MediaExportEngine(context).export(source,Uri.fromFile(source),info,MediaEditOptions(MediaExportFormat.MOV,0,info.durationMs,copyTracks=true),"all-tracks")
            try{MediaWorkspace.create(context).use {val actual=NativeMediaProbe.read(context,copied.file,it);assertEquals(2,actual.tracks.count{track->track.type=="audio"})}}
            finally{check(copied.file.delete())}
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationKillsNativeWorkerCleansItsFilesAndAllowsRetry()=runBlocking<Unit> {
        val root=root();val source=root.resolve("source.wav").also {MediaTestFixtures.wav(it,15000)};val before=digest(source)
        try {
            val uri=Uri.fromFile(source);val info=MediaExportEngine.info(context,uri,source.length())
            val exporting=File(context.cacheDir,"media-exports").list()?.toSet().orEmpty()
            val child=async {MediaExportEngine(context).export(source,uri,info,MediaEditOptions(MediaExportFormat.MP3,0,15000,noiseReduction=true),"cancel"){cancel("test cancellation")}}
            try{child.await();fail("Native cancellation ignored")}catch(_:CancellationException){}
            assertEquals(before,digest(source));assertEquals(exporting,File(context.cacheDir,"media-exports").list()?.toSet().orEmpty())
            assertEquals(0,File(context.cacheDir,"media-work").list()?.size?:0)
            val retry=MediaExportEngine(context).export(source,uri,info,MediaEditOptions(MediaExportFormat.FLAC,0,500),"retry")
            check(retry.file.delete())
        }finally{root.deleteRecursively()}
    }
    private fun videoPackets(file:File):List<String> {
        val extractor=android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path);val track=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/")};extractor.selectTrack(track)
            val buffer=ByteBuffer.allocate(1024*1024);val result=mutableListOf<String>()
            while(extractor.sampleTime>=0){buffer.clear();val count=extractor.readSampleData(buffer,0);val bytes=ByteArray(count);buffer.position(0);buffer.get(bytes);result+=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};extractor.advance()}
            return result
        }finally{extractor.release()}
    }
}
