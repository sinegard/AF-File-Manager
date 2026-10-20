package com.affilemanager.app.media.editing

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.abs

/** Additional containers/effects use the same admitted export and Save As owner as Media3. */
internal object NativeMediaExport {
    fun needed(source: File, options: MediaEditOptions): Boolean = options.format in setOf(
        MediaExportFormat.MKV, MediaExportFormat.MOV, MediaExportFormat.MP3, MediaExportFormat.FLAC, MediaExportFormat.WMA) ||
        options.noiseReduction || options.reverseVideo || options.copyTracks ||
        (options.reverseAudio && options.format != MediaExportFormat.WAV) || source.extension.equals("wma", true) || NativeMediaProbe.isAsf(source)

    suspend fun export(context: Context, source: File, uri: Uri, info: MediaEditInfo,
        options: MediaEditOptions, output: File, cover: ByteArray?, workspace: MediaWorkspace,
        transform: suspend (Uri, MediaEditInfo, MediaEditOptions, File, (Int?) -> Unit) -> Unit,
        progress: (Int?) -> Unit) {
        if (!options.format.video) {
            NativeAudioExport.export(context,source,info,options,output,workspace,progress)
            return
        }
        val tools = NativeMediaTools(context,workspace)
        if (options.copyTracks) {
            val original=NativeMediaProbe.read(context,source,workspace)
            remux(tools,source,output,options,cover,workspace)
            val copied=NativeMediaProbe.read(context,output,workspace)
            val expected=original.tracks.groupingBy {it.type to it.codec}.eachCount()
            val actual=copied.tracks.groupingBy {it.type to it.codec}.eachCount()
            require(expected.all {(key,count)->(actual[key]?:0)>=count} && copied.tracks.size==original.tracks.size+if(cover!=null && options.format==MediaExportFormat.MKV)1 else 0) { "Išvesties takelio nėra" }
            return
        }
        val canonical = workspace.file("canonical.mp4")
        val nativeAudio = info.hasAudio && (options.reverseAudio || options.noiseReduction)
        val normalized = options.copy(format=MediaExportFormat.MP4, reverseAudio=false, reverseVideo=false, noiseReduction=false,
            fadeInMs=if(nativeAudio)0 else options.fadeInMs, fadeOutMs=if(nativeAudio)0 else options.fadeOutMs,
            echoDelayMs=if(nativeAudio)0 else options.echoDelayMs, noiseGate=!nativeAudio && options.noiseGate, copyTracks=false,
            volume=if(nativeAudio)1f else options.volume,pitch=if(nativeAudio)1f else options.pitch,
            sampleRate=if(nativeAudio)null else options.sampleRate,channels=if(nativeAudio)null else options.channels)
        transform(uri,if(nativeAudio)info.copy(hasAudio=false,audioSampleRate=null,audioChannels=null)else info,normalized,canonical) { progress(it?.div(2)) }
        val audio = if (nativeAudio) workspace.file("audio.m4a").also {
            NativeAudioExport.export(context,source,info,options.copy(format=MediaExportFormat.M4A,
                crop=MediaCrop(),height=null,frameRate=null,
                videoBitrate=null,reverseVideo=false,copyTracks=false),it,workspace) { progress(50+(it?:0)/5) }
        } else canonical.takeIf { info.hasAudio }
        val prepared = if(options.reverseVideo) workspace.file("reversed.mp4").also {
            NativeVideoReverse.export(context,canonical,it,workspace,options.videoBitrate) {
                val base=if(nativeAudio)70 else 50
                val span=if(nativeAudio)25 else 45
                progress(base+(it?:0)*span/100)
            }
        } else canonical
        remux(tools,prepared,output,options,cover,workspace,audio.takeIf {nativeAudio || options.reverseVideo})
    }

    private suspend fun remux(tools: NativeMediaTools, input: File, output: File, options: MediaEditOptions,
        cover: ByteArray?, workspace: MediaWorkspace, audio: File?=null) {
        val attachment = if(options.format==MediaExportFormat.MKV && cover!=null) workspace.file("cover.jpg").also { it.writeBytes(cover) } else null
        val mapping=if(options.copyTracks)listOf("-map","0")else listOf("-map","0:v:0","-map",if(audio!=null)"1:a:0?" else "0:a:0?")
        val args = listOf("-i",input.path) + (if(audio!=null)listOf("-i",audio.path)else emptyList()) +
            mapping + listOf("-c","copy","-map_metadata","-1") +
            NativeAudioExport.metadata(options.tags) + (if(attachment!=null)listOf("-attach",attachment.path,
                "-metadata:s:t","mimetype=image/jpeg","-metadata:s:t","filename=cover.jpg")else emptyList()) +
            listOf("-f",when(options.format){MediaExportFormat.MKV->"matroska";MediaExportFormat.MOV->"mov";else->"mp4"},output.path)
        tools.ffmpeg(args,"container")
    }

    suspend fun tags(context: Context, file: File, options: MediaEditOptions, cover: ByteArray?, workspace: MediaWorkspace) {
        val job=currentCoroutineContext()
        val temporary=workspace.file("tagged.tmp")
        when(options.format) {
            MediaExportFormat.MP3 -> NativeAudioTags.mp3(file,options.tags,cover,temporary){job.ensureActive()}
            MediaExportFormat.FLAC -> NativeAudioTags.flacCover(file,cover,temporary){job.ensureActive()}
            MediaExportFormat.WMA -> NativeAudioTags.asfCover(file,cover,temporary){job.ensureActive()}
            else -> Unit
        }
    }

    suspend fun validate(context: Context, output: File, input: MediaEditInfo, options: MediaEditOptions, workspace: MediaWorkspace): Long {
        val actual=NativeMediaProbe.read(context,output,workspace,options.durationMs+350)
        require(abs(actual.durationMs-options.durationMs)<=350) { "Išvesties trukmė neatitinka kirpimo" }
        require(if(options.format.video)actual.video!=null else actual.audio!=null && actual.video==null) { "Išvesties takelio nėra" }
        if(options.format.video) {
            if(input.hasAudio)require(actual.audio!=null){"Išvesties garso takelio nėra"}
            val expected=if(options.copyTracks)input.width to input.height else options.videoSize(input)
            require(actual.video?.width==expected.first && actual.video?.height==expected.second){"Išvesties raiška neatitinka pasirinkimo"}
        }
        val expectedContainer=when(options.format){
            MediaExportFormat.MKV->"matroska";MediaExportFormat.MOV,MediaExportFormat.MP4,MediaExportFormat.M4A->"mov"
            MediaExportFormat.MP3->"mp3";MediaExportFormat.WMA->"asf";MediaExportFormat.FLAC->"flac";MediaExportFormat.WAV->"wav";else->"gif"
        }
        require(actual.container.split(',').contains(expectedContainer)){"Netinkamas išvesties failas"}
        if(options.channels!=null && actual.audio!=null)require(actual.audio?.channels==options.channels){"Invalid audio channel count"}
        if(options.sampleRate!=null && actual.audio!=null)require(actual.audio?.rate==options.sampleRate){"Invalid audio sample rate"}
        return actual.durationMs
    }
}
