package com.affilemanager.app.media.editing

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.editing.EditExistingPolicy
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.transfer.LanWebDavServer
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

class MediaRemoteWorkflowAndroidTest {
    @Test fun remoteOpenEditExportConflictSaveBackAndCloseUseTheRealAfWorkflow()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root=File(app.cacheDir,"media-webdav-${UUID.randomUUID()}").apply {check(mkdir())}
        val original=root.resolve("original.wav").also {MediaTestFixtures.wav(it)}
        val before=MessageDigest.getInstance("SHA-256").digest(original.readBytes())
        val occupied=root.resolve("converted.flac").apply {writeText("keep this existing file")}
        val server=LanWebDavServer(root,InetAddress.getByName("127.0.0.1"))
        val endpoint=server.start()
        val profile=NetworkProfile(UUID.randomUUID().toString(),"private media fixture",NetworkProtocol.WEBDAV,"127.0.0.1",endpoint.port,requireNotNull(endpoint.username),"/",webDavUseTls=false)
        val owner=ViewModelStore()
        val model=withContext(Dispatchers.Main){MainViewModel(app).also{owner.put("media-remote",it)}}
        var pending:MediaExportFile?=null
        try {
            app.graph.networkProfiles.save(profile,endpoint.code.toCharArray()).getOrThrow()
            withContext(Dispatchers.Main){model.connectNetwork(profile)}
            val connected=withTimeout(15000){model.networkState.filter {it.connectedProfile?.id==profile.id && !it.loading && it.entries.any {entry->entry.name==original.name}}.first()}
            withContext(Dispatchers.Main){model.openRemoteEntry(connected.entries.first {it.name==original.name})}
            withTimeout(15000){model.preview.filter{it!=null}.first()}
            withContext(Dispatchers.Main){model.prepareFileEdit()}
            val editing=withTimeout(15000){model.fileEditState.filter{it.session!=null && !it.preparing}.first()}
            val session=requireNotNull(editing.session);val file=session.workingFile
            assertTrue(file.canonicalPath.startsWith(app.cacheDir.canonicalPath+File.separator))
            val info=MediaExportEngine.info(app,Uri.fromFile(file),file.length(),file)
            pending=MediaExportEngine(app).export(file,Uri.fromFile(file),info,MediaEditOptions(MediaExportFormat.FLAC,250,1750,reverseAudio=true),"converted")
            val result=requireNotNull(pending)
            withContext(Dispatchers.Main){assertTrue(model.acceptMediaExport(result))}
            withTimeout(15000){model.fileEditState.filter{it.exportOnly && !it.modifying && it.session?.displayName=="converted.flac"}.first()}
            val expected=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            withTimeout(10000){while(result.file.exists())delay(20)};pending=null
            withContext(Dispatchers.Main){model.saveFileEditAsRemote("/",occupied.name)}
            withTimeout(15000){model.fileEditState.filter{it.saveAsConflict!=null && !it.saving}.first()}
            assertEquals("keep this existing file",occupied.readText())
            withContext(Dispatchers.Main){model.resolveFileEditSaveAsConflict(EditExistingPolicy.KEEP_BOTH)}
            withTimeout(15000){model.fileEditState.filter{it.saveAsConflict==null && !it.saving && !it.hasUnsavedChanges}.first()}
            val exported=root.listFiles()!!.filter{it.extension=="flac" && it!=occupied}.single()
            assertArrayEquals(expected,MessageDigest.getInstance("SHA-256").digest(exported.readBytes()))
            MediaWorkspace.create(app).use {assertNotNull(NativeMediaProbe.read(app,exported,it).audio)}
            withContext(Dispatchers.Main){model.closePreview()}
            withTimeout(10000){while(file.exists())delay(20)}
            assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
            assertEquals("keep this existing file",occupied.readText())
            println("AF_NATIVE_REMOTE_OPEN_EDIT_KEEP_BOTH_SAVE_CLOSE OK")
        }finally{
            pending?.file?.delete();withContext(Dispatchers.Main){owner.clear()}
            server.close();app.graph.networkProfiles.remove(profile.id).getOrThrow();root.deleteRecursively()
        }
    }
}
