package com.affilemanager.app.ui.preview

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.affilemanager.app.media.editing.*
import com.affilemanager.app.ui.localization.LText
import kotlinx.coroutines.*

/** WMA is decoded privately for the same editor preview; export always reads the original. */
@Composable
internal fun MediaEditorSourcePreview(source: PreviewSource.Working, uri: Uri, info: MediaEditInfo,
    options: MediaEditOptions, cropping: Boolean, crop: MediaCrop, onCrop: (MediaCrop)->Unit) {
    if(!source.name.endsWith(".wma",true)) {
        MediaEditPreview(uri,info,options,cropping,crop,onCrop,Modifier.fillMaxWidth());return
    }
    val context=LocalContext.current
    val preview by produceState<Result<Uri>?>(null,source.key) {
        var workspace: MediaWorkspace?=null
        try {
            val converted=withContext(Dispatchers.IO) {
                val work=MediaWorkspace.create(context);workspace=work
                val file=work.file("preview.m4a")
                NativeAudioExport.export(context,source.localFile,info,MediaEditOptions(MediaExportFormat.M4A,0,info.durationMs),file,work){}
                Uri.fromFile(file)
            }
            value=Result.success(converted)
            awaitCancellation()
        } catch(error:Throwable) {if(error is CancellationException)throw error;value=Result.failure(error)}
        finally {withContext(NonCancellable+Dispatchers.IO){workspace?.close()}}
    }
    val state=preview
    if(state==null)CircularProgressIndicator()
    else if(state.isFailure)LText("Failo peržiūros sukurti nepavyko")
    else MediaEditPreview(state.getOrThrow(),info,options,false,crop,onCrop,Modifier.fillMaxWidth())
}
