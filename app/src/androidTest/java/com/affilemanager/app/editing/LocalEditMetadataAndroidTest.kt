package com.affilemanager.app.editing

import android.system.Os
import android.system.OsConstants
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.advanced.PrivilegedFileMetadata
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual app-UID filesystem evidence, not host-Windows permission simulation. */
class LocalEditMetadataAndroidTest {
    @Test fun ordinarySavePreservesTheOriginalCustomAccessMode() = fixture { app, root ->
        for (mode in listOf(0x1a0,0x1c0,0x1a4)) {
            val source=File(root,"mode-$mode.txt").apply { writeText("original") }
            Os.chmod(source.path,mode)
            val before=Os.stat(source.path)
            val label=Os.getxattr(source.path,"security.selinux")
            val session=edit(app,source)
            assertTrue(app.graph.editSessions.saveLocal(session,false) is EditSaveResult.Saved)
            val after=Os.stat(source.path)
            record(app,"local-save-mode",before.st_mode,after.st_mode,source)
            assertEquals("edited in AF",source.readText())
            assertEquals(before.st_uid,after.st_uid); assertEquals(before.st_gid,after.st_gid)
            assertEquals(before.st_mode,after.st_mode)
            assertArrayEquals(label,Os.getxattr(source.path,"security.selinux"))
            app.graph.editSessions.discard(session)
        }
    }

    @Test fun saveAsReplacePreservesTheDestinationNotTheSourceAccessMode() = fixture { app, root ->
        val source=File(root,"source.txt").apply { writeText("original") }
        val target=File(root,"destination.txt").apply { writeText("previous destination") }
        Os.chmod(source.path,0x180); Os.chmod(target.path,0x1c0)
        val before=Os.stat(target.path)
        val label=Os.getxattr(target.path,"security.selinux")
        val session=edit(app,source)
        assertTrue(app.graph.editSessions.saveLocalAs(session,root.path,target.name,EditExistingPolicy.REPLACE) is EditSaveAsResult.Saved)
        val after=Os.stat(target.path)
        record(app,"local-saveas-mode",before.st_mode,after.st_mode,target)
        assertEquals("original",source.readText()); assertEquals("edited in AF",target.readText())
        assertEquals(before.st_uid,after.st_uid); assertEquals(before.st_gid,after.st_gid)
        assertEquals(before.st_mode,after.st_mode)
        assertArrayEquals(label,Os.getxattr(target.path,"security.selinux"))
        app.graph.editSessions.discard(session)
    }

    @Test fun sharedStorageOrdinarySaveStillPublishesTheFullFile() {
        val app=ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        fixture(File(checkNotNull(app.getExternalFilesDir("validation")),"local-shared-${System.nanoTime()}").canonicalFile) { _,root ->
            val source=File(root,"document.txt").apply { writeText("original") }
            val before=Os.stat(source.path)
            val session=edit(app,source)
            assertTrue(app.graph.editSessions.saveLocal(session,false) is EditSaveResult.Saved)
            val after=Os.stat(source.path)
            assertEquals("edited in AF",source.readText())
            assertEquals(before.st_uid,after.st_uid); assertEquals(before.st_gid,after.st_gid)
            assertEquals(before.st_mode,after.st_mode)
            app.graph.editSessions.discard(session)
        }
    }

    @Test fun finalSymbolicLinksAreNotReplacedOrRelabelled() = fixture { app,root ->
        val target=File(root,"original.txt").apply { writeText("original") }
        Os.chmod(target.path,0x1a0)
        val link=File(root,"link.txt").also { Os.symlink(target.path,it.path) }
        val before=Os.stat(target.path)
        val session=edit(app,link)
        assertThrows(IllegalStateException::class.java) { app.graph.editSessions.saveLocal(session,false) }
        assertTrue(OsConstants.S_ISLNK(Os.lstat(link.path).st_mode))
        assertEquals("original",target.readText())
        assertEquals(before.st_ino,Os.stat(target.path).st_ino)
        assertEquals(before.st_mode,Os.stat(target.path).st_mode)
        assertEquals("edited in AF",session.workingFile.readText())
        app.graph.editSessions.discard(session)
    }

    @Test fun ordinaryParentAliasesRemainUsable() = fixture { app,root ->
        val directory=File(root,"real").apply { check(mkdir()) }
        val alias=File(root,"alias").also { Os.symlink(directory.path,it.path) }
        val original=File(directory,"document.txt").apply { writeText("original") }
        Os.chmod(original.path,0x1a0)
        val session=edit(app,File(alias,original.name))
        assertTrue(app.graph.editSessions.saveLocal(session,false) is EditSaveResult.Saved)
        assertEquals("edited in AF",original.readText())
        assertEquals(0x1a0,Os.stat(original.path).st_mode and 0x1ff)
        assertTrue(alias.delete())
        app.graph.editSessions.discard(session)
    }

    @Test fun exclusiveStageRejectsAnOccupiedNameAndDoesNotDeleteAReplacement() = fixture { _,root ->
        val name=File(root,"stage.tmp").apply { writeText("unselected existing file") }
        assertThrows(IllegalStateException::class.java) { AndroidLocalEditFiles.openStage(name) }
        assertEquals("unselected existing file",name.readText())
        assertTrue(name.delete())
        val stage=AndroidLocalEditFiles.openStage(name)
        val fd=(stage.output as java.io.FileOutputStream).fd
        val moved=File(root,"moved-owned-stage")
        try {
            stage.output.write("owned bytes".toByteArray())
            assertTrue(name.renameTo(moved))
            name.writeText("unselected replacement")
            val prepared=stage.prepare(null)
            assertThrows(IllegalStateException::class.java) { stage.requireNamed(prepared) }
        } finally { stage.close() }
        assertFalse(fd.valid())
        assertEquals("unselected replacement",name.readText())
        assertEquals("owned bytes",moved.readText())
    }

    @Test fun foreignOwnerIsRefusedWithoutRootAndTheDraftIsKept() = fixture { app,root ->
        org.junit.Assume.assumeTrue("Requires an actual authorized UID0 setup",
            InstrumentationRegistry.getArguments().getString("afShizukuRoot")=="true")
        val access=app.graph.advancedAccess
        val previous=access.state.value.selectedMode
        val test=InstrumentationRegistry.getInstrumentation()
        val source=File(root,"foreign.txt").apply { writeText("original") }
        try {
            test.runOnMainSync { access.setMode(AdvancedAccessMode.SHIZUKU); access.requestShizukuAccess() }
            runBlocking { withTimeout(20_000) { access.state.first { it.connected || it.error!=null } } }
            assertEquals(0,access.state.value.serviceUid)
            val service=access.fileMetadataServiceOrThrow()
            val original=PrivilegedFileMetadata.decode(service.readFileMetadata(source.path))
            service.applyFileMetadata(source.path,original.copy(uid=0,gid=0,mode=(original.mode and 0x1ff.inv()) or 0x1b6).encode())
            val before=Os.stat(source.path)
            val label=Os.getxattr(source.path,"security.selinux")
            test.runOnMainSync { access.setMode(AdvancedAccessMode.OFF) }
            val session=edit(app,source)
            repeat(12) {
                val error=assertThrows(IllegalStateException::class.java) { app.graph.editSessions.saveLocal(session,false) }
                assertEquals(LOCAL_EDIT_ACCESS_ERROR,error.message)
                assertEquals("original",source.readText())
                val after=Os.stat(source.path)
                assertEquals(before.st_ino,after.st_ino); assertEquals(before.st_uid,after.st_uid)
                assertEquals(before.st_gid,after.st_gid); assertEquals(before.st_mode,after.st_mode)
                assertArrayEquals(label,Os.getxattr(source.path,"security.selinux"))
                assertEquals(setOf(source.name),root.list()!!.toSet())
            }
            assertEquals(AdvancedAccessMode.OFF,access.state.value.selectedMode)
            assertFalse(access.state.value.connected)
            assertEquals("edited in AF",session.workingFile.readText())
            app.graph.editSessions.discard(session)
        } finally { test.runOnMainSync { access.setMode(previous) } }
    }

    private fun edit(app: AFFileManagerApplication,source: File): EditSession {
        val store=app.graph.editSessions
        val session=store.prepareFromFile(source.path,source.name,"text/plain",source,
            EditOrigin.Local(source.path,true),source.lastModified(),true)
        return store.stageText(session,"edited in AF")
    }

    private fun record(app: AFFileManagerApplication,name: String,before: Int,after: Int,file: File) {
        File(uiAuditDirectory(app),"$name.json").writeText(org.json.JSONObject().put("path",file.path)
            .put("beforeMode",before).put("afterMode",after).put("text",file.readText()).toString())
    }

    private fun fixture(block: (AFFileManagerApplication,File)->Unit) {
        val app=ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        fixture(File(app.cacheDir,"local-metadata-${System.nanoTime()}").canonicalFile,block)
    }

    private fun fixture(root: File,block: (AFFileManagerApplication,File)->Unit) {
        val app=ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(root.mkdir())
        var accepted=false
        try { block(app,root); accepted=true }
        finally {
            if(accepted)check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit",root.path)
        }
    }
}
