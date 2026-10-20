package com.affilemanager.app.media.editing

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.editing.EditExistingPolicy
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class MediaEditWorkflowAndroidTest {
    @Test fun convertedWmaCanBeEditedAgainInTheSameAfWorkingSession()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root=File(app.cacheDir,"media-reedit-${UUID.randomUUID()}").apply{check(mkdir())}
        val original=root.resolve("original.wav").also{MediaTestFixtures.wav(it)}
        val before=MessageDigest.getInstance("SHA-256").digest(original.readBytes())
        val owner=ViewModelStore()
        val model=withContext(Dispatchers.Main){MainViewModel(app).also{owner.put("media",it)}}
        var pending:MediaExportFile?=null
        try {
            withContext(Dispatchers.Main){model.open(FileEntry(original.path,original.name,EntryKind.AUDIO,original.length(),original.lastModified(),false,true,true))}
            withTimeout(10000){model.preview.filter{it!=null}.first()}
            withContext(Dispatchers.Main){model.prepareFileEdit()}
            val session=requireNotNull(withTimeout(10000){model.fileEditState.filter{it.session!=null && !it.preparing}.first()}.session)
            val working=session.workingFile
            var info=MediaExportEngine.readInfo(app,Uri.fromFile(working),working)
            pending=MediaExportEngine(app).export(working,Uri.fromFile(working),info,MediaEditOptions(MediaExportFormat.WMA,0,2000),"converted")
            val wma=requireNotNull(pending)
            withContext(Dispatchers.Main){assertTrue(model.acceptMediaExport(wma))}
            withTimeout(10000){model.fileEditState.filter{!it.modifying && it.session?.displayName=="converted.wma"}.first()}
            withTimeout(10000){while(wma.file.exists())delay(20)};pending=null
            assertEquals("wav",working.extension) // AF keeps the private session path, not the display suffix.
            info=MediaExportEngine.readInfo(app,Uri.fromFile(working),working)
            val options=MediaEditOptions(MediaExportFormat.M4A,0,1000)
            assertTrue("AF must detect the actual WMA data after a format change",NativeMediaExport.needed(working,options))
            pending=MediaExportEngine(app).export(working,Uri.fromFile(working),info,options,"edited-again")
            val m4a=requireNotNull(pending)
            withContext(Dispatchers.Main){assertTrue(model.acceptMediaExport(m4a))}
            withTimeout(10000){model.fileEditState.filter{!it.modifying && it.session?.displayName=="edited-again.m4a"}.first()}
            withTimeout(10000){while(m4a.file.exists())delay(20)};pending=null
            withContext(Dispatchers.Main){model.saveFileEditAsLocal(root.path,"edited-again.m4a")}
            withTimeout(10000){model.fileEditState.filter{!it.saving && !it.hasUnsavedChanges}.first()}
            val saved=root.resolve("edited-again.m4a")
            val extractor=android.media.MediaExtractor()
            try{extractor.setDataSource(saved.path);assertEquals("audio/mp4a-latm",extractor.getTrackFormat(0).getString("mime"))}finally{extractor.release()}
            withContext(Dispatchers.Main){model.closePreview()}
            withTimeout(10000){while(working.exists())delay(20)}
            assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
            println("AF_WAV_WMA_M4A_SAME_SESSION_SAVEAS_CLOSE OK")
        }finally{pending?.file?.delete();withContext(Dispatchers.Main){owner.clear()};root.deleteRecursively()}
    }
    @Test fun privateExportHandoffSharedSaveAsConflictKeepBothAndCloseHaveNoLeftovers() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "media-workflow-${UUID.randomUUID()}").apply { check(mkdir()) }
        val original = root.resolve("original.wav").also { MediaTestFixtures.wav(it) }
        val before = MessageDigest.getInstance("SHA-256").digest(original.readBytes())
        val owner = ViewModelStore()
        val model = withContext(Dispatchers.Main) { MainViewModel(app).also { owner.put("media", it) } }
        var pending: MediaExportFile? = null
        try {
            withContext(Dispatchers.Main) { model.open(FileEntry(original.path, original.name, EntryKind.AUDIO, original.length(), original.lastModified(), false, true, true)) }
            withTimeout(10_000) { model.preview.filter { it != null }.first() }
            withContext(Dispatchers.Main) { model.prepareFileEdit() }
            val session = requireNotNull(withTimeout(10_000) { model.fileEditState.filter { it.session != null && !it.preparing }.first() }.session)
            val uri = Uri.fromFile(session.workingFile)
            val info = MediaExportEngine.info(app, uri, session.workingFile.length())
            pending = MediaExportEngine(app).export(session.workingFile, uri, info, MediaEditOptions(MediaExportFormat.M4A, 0, 1500), "prepared.2026")
            val result = requireNotNull(pending)
            withContext(Dispatchers.Main) { assertTrue(model.acceptMediaExport(result)) }
            val imported = withTimeout(10_000) { model.fileEditState.filter { it.exportOnly && !it.modifying && it.session?.workingRevision?.sha256 != session.workingRevision.sha256 }.first() }
            assertEquals("audio/mp4", imported.session!!.mimeType)
            assertEquals("prepared.2026.m4a", imported.session!!.displayName)
            withTimeout(10_000) { while (result.file.exists()) delay(20) }
            pending = null
            withContext(Dispatchers.Main) { model.saveFileEdit(false) }
            assertNotNull(model.fileEditState.value.error)
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
            val occupied = root.resolve("prepared.2026.m4a").apply { writeText("must remain") }
            withContext(Dispatchers.Main) { model.saveFileEditAsLocal(root.path, occupied.name) }
            withTimeout(10_000) { model.fileEditState.filter { it.saveAsConflict != null && !it.saving }.first() }
            assertEquals("must remain", occupied.readText())
            withContext(Dispatchers.Main) { model.resolveFileEditSaveAsConflict(EditExistingPolicy.KEEP_BOTH) }
            val saved = withTimeout(10_000) { model.fileEditState.filter { it.saveAsConflict == null && !it.saving && !it.hasUnsavedChanges }.first() }
            val copies = root.listFiles()!!.filter { it.extension == "m4a" && it != occupied }
            assertEquals(1, copies.size)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(saved.session!!.workingFile.readBytes()), MessageDigest.getInstance("SHA-256").digest(copies.single().readBytes()))
            assertEquals("must remain", occupied.readText())
            withContext(Dispatchers.Main) { model.closePreview() }
            withTimeout(10_000) { while (session.workingFile.exists()) delay(20) }
            assertNull(model.preview.value)
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
        } finally { pending?.file?.delete(); withContext(Dispatchers.Main) { owner.clear() }; root.deleteRecursively() }
    }
}
