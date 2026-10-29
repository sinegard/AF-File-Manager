package com.affilemanager.app

import android.os.FileObserver
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.editing.*
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PrivilegedEditRecoveryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun aFailedPrivilegedPublicationNeverDiscardsItsOriginalBackup() = exercise(true)
    @Test fun ordinarySaveAndConflictKeepTheirExpectedContents() = exercise(false)

    private fun exercise(injectFault: Boolean) = runBlocking {
        assumeTrue("Actual Shizuku-root stage only", InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20000) { app.graph.advancedAccess.state.value.connected || app.graph.advancedAccess.state.value.error != null }
            assertEquals(AdvancedAccessBackend.SHIZUKU_ROOT, app.graph.advancedAccess.state.value.activeBackend)
            assertEquals(0, app.graph.advancedAccess.state.value.serviceUid)
            if (!injectFault) {
                val root = File(app.cacheDir,"root-edit-normal-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
                val target = File(root,"document.txt").apply { writeText("original") }
                android.system.Os.chmod(target.path, 0x1a0) // 0640, deliberately not the new-file default.
                val originalStat = android.system.Os.stat(target.path)
                val originalLabel = android.system.Os.getxattr(target.path,"security.selinux")
                val working = File(root,"working.txt").apply { writeText("edited") }
                val before = revision(target)
                var session = EditSession(UUID.randomUUID().toString(),target.path,target.name,"text/plain",working,
                    EditOrigin.Privileged(target.path,true),before,revision(working),before,true)
                var accepted = false
                try {
                    val saved = app.graph.privilegedFiles.saveOrigin(session,false)
                    assertTrue(saved is EditSaveResult.Saved)
                    assertEquals("edited",target.readText())
                    assertSecurityPreserved(target, originalStat, originalLabel)
                    working.writeText("second draft")
                    session = session.copy(originRevision=(saved as EditSaveResult.Saved).revision,workingRevision=revision(working))
                    target.writeText("independent change")
                    assertTrue(app.graph.privilegedFiles.saveOrigin(session,false) is EditSaveResult.Conflict)
                    assertEquals("independent change",target.readText())
                    assertEquals(setOf("document.txt","working.txt"),root.list()!!.toSet())
                    assertTrue(app.graph.privilegedFiles.saveOrigin(session,true) is EditSaveResult.Saved)
                    assertEquals("second draft",target.readText())
                    assertSecurityPreserved(target, originalStat, originalLabel)
                    assertEquals(setOf("document.txt","working.txt"),root.list()!!.toSet())
                    accepted = true
                } finally {
                    if (accepted) removeOwned(root)
                    else android.util.Log.e("AFRootAudit","Retained failed ordinary-save fixture: $root")
                }
                return@runBlocking
            }
            var arranged = false
            repeat(8) {
                if (arranged) return@repeat
                val root = File(app.cacheDir,"root-edit-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
                val target = File(root,"document.txt").apply { writeText("original before editing\n".repeat(256)) }
                val original = target.readText()
                val working = File(root,"working.txt").apply { writeText("edited content\n".repeat(256)) }
                val before = revision(target)
                val draft = revision(working)
                val session = EditSession(UUID.randomUUID().toString(),target.path,target.name,"text/plain",working,
                    EditOrigin.Privileged(target.path,true),before,draft,before,true)
                val injected = AtomicBoolean(false)
                val callbackDone = CountDownLatch(1)
                val callbackError = AtomicReference<Throwable?>()
                val observer = object : FileObserver(root.path,CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path?.startsWith(".${target.name}.af-backup-") != true || event and CLOSE_WRITE == 0 || injected.get()) return
                        try {
                            // The original now stays named until atomic replacement. Inject
                            // the competing directory after its recovery copy was fully written.
                            check(target.delete())
                            if (target.mkdir()) {
                                File(target,"concurrent.txt").writeText("independent concurrent data")
                                injected.set(true)
                            }
                        } catch (error: Throwable) { callbackError.set(error) }
                        finally { callbackDone.countDown() }
                    }
                }
                var accepted = false
                try {
                    observer.startWatching()
                    val result = runCatching { app.graph.privilegedFiles.saveOrigin(session,false) }
                    check(callbackDone.await(5,TimeUnit.SECONDS)) { "Native recovery-copy close event was not observed" }
                    callbackError.get()?.let { throw AssertionError("Owned fault injection failed",it) }
                    if (injected.get()) {
                        arranged = true
                        assertTrue("The concurrent directory must prevent publication",result.isFailure)
                        assertEquals("independent concurrent data",File(target,"concurrent.txt").readText())
                        val backups = root.listFiles()!!.filter { it.name.startsWith(".${target.name}.af-backup-") }
                        assertTrue("Failed save discarded the only original backup",backups.any { it.isFile && it.readText() == original })
                    } else {
                        assertTrue(result.getOrThrow() is EditSaveResult.Saved)
                        assertEquals(working.readText(),target.readText())
                    }
                    accepted = true
                } finally {
                    observer.stopWatching()
                    if (accepted) removeOwned(root)
                    else android.util.Log.e("AFRootAudit","Retained failed root edit recovery fixture: $root")
                }
            }
            assumeTrue("No native concurrent-publication window arranged; not acceptance",arranged)
        } finally { compose.runOnUiThread { vm.setAdvancedAccessMode(previous) } }
    }

    private fun revision(file: File) = FileRevision(file.length(),file.lastModified(),
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)})
    private fun assertSecurityPreserved(file: File, original: android.system.StructStat, label: ByteArray) {
        val after = android.system.Os.stat(file.path)
        assertEquals(original.st_uid, after.st_uid)
        assertEquals(original.st_gid, after.st_gid)
        assertEquals(original.st_mode, after.st_mode)
        assertArrayEquals(label, android.system.Os.getxattr(file.path,"security.selinux"))
    }
    private fun removeOwned(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeOwned)
        check(file.delete() || !file.exists())
    }
}
