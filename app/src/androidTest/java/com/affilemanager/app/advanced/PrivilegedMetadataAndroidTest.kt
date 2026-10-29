package com.affilemanager.app.advanced

import android.content.Context
import android.os.Binder
import android.system.Os
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking

/** Public Binder contract controls, not a substitute for an actual UID0/Shell run. */
class PrivilegedMetadataAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun privateCreationAndExactMetadataRoundTripKeepNormalAppAccess() = fixture { root, service ->
        val original = File(root,"original.txt").apply { writeText("original") }
        Os.chmod(original.path,0x1a0)
        val security = PrivilegedFileMetadata.decode(service.readFileMetadata(original.path))
        val copy = File(root,"copy.txt")
        service.createPrivateEntry(copy.path,false)
        assertEquals(0x180,Os.stat(copy.path).st_mode and 0x1ff)
        copy.writeText("draft")
        service.applyFileMetadata(copy.path,security.encode())
        assertTrue(security.sameSecurity(PrivilegedFileMetadata.decode(service.readFileMetadata(copy.path))))
        assertEquals("draft",copy.readText())
        assertEquals("original",original.readText())
        val directory = File(root,"folder")
        service.createPrivateEntry(directory.path,true)
        assertEquals(0x1c0,Os.stat(directory.path).st_mode and 0x1ff)
        assertArrayEquals(Os.getxattr(root.path,"security.selinux"),Os.getxattr(directory.path,"security.selinux"))
    }

    @Test fun occupiedAndSymbolicNamesCannotBeTruncatedOrRelabelled() = fixture { root, service ->
        val original = File(root,"original.txt").apply { writeText("do not touch") }
        val before = service.readFileMetadata(original.path)
        val link = File(root,"link.txt")
        Files.createSymbolicLink(link.toPath(),original.toPath())
        assertTrue(runCatching { service.createPrivateEntry(original.path,false) }.isFailure)
        assertTrue(runCatching { service.createPrivateEntry(link.path,false) }.isFailure)
        assertTrue(runCatching { service.applyFileMetadata(link.path,before) }.isFailure)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(before,service.readFileMetadata(original.path))
        assertEquals("do not touch",original.readText())
    }

    @Test fun invalidLabelsAndPayloadsFailWithoutChangingTheOriginal() = fixture { root, service ->
        val original = File(root,"original.txt").apply { writeText("original") }
        val before = service.readFileMetadata(original.path)
        val copy = File(root,"draft.txt").apply { writeText("draft") }
        assertTrue(runCatching { service.applyFileMetadata(copy.path,before.copyOf().also { it[6]="not-hex" }) }.isFailure)
        val invalidLabel = PrivilegedFileMetadata.decode(before).copy(selinuxHex="6e6f742d612d76616c69642d73656c696e75782d6c6162656c00")
        assertTrue(runCatching { service.applyFileMetadata(copy.path,invalidLabel.encode()) }.isFailure)
        assertArrayEquals(before,service.readFileMetadata(original.path))
        assertEquals("original",original.readText())
        assertEquals("draft",copy.readText())
    }

    @Test fun anOldBinderCannotClaimMetadataSupport() {
        val service = IPrivilegedFileService.Stub.asInterface(object : Binder() {
            override fun queryLocalInterface(descriptor: String): android.os.IInterface? = null
        })
        assertNotEquals(PrivilegedFileMetadata.VERSION, runCatching { service.fileMetadataVersion }.getOrDefault(-1))
    }

    @Test fun writeHandleAdmissionAbortAndChunkBoundsAreEnforced() = fixture { root, service ->
        val handles = (0 until 8).map { service.openPrivateWrite(File(root,"handle-$it.txt").path) }
        try {
            val rejected = File(root,"ninth.txt")
            assertTrue(runCatching { service.openPrivateWrite(rejected.path) }.isFailure)
            assertFalse(rejected.exists())
            assertTrue(runCatching { service.writePrivateFile(handles[0],ByteArray(65_537),65_537) }.isFailure)
            assertTrue(runCatching { service.writePrivateFile(handles[0],ByteArray(1),2) }.isFailure)
            val bytes = ByteArray(65_536) { (it % 251).toByte() }
            assertEquals(bytes.size,service.writePrivateFile(handles[0],bytes,bytes.size))
            service.finishPrivateWrite(handles[0])
            assertArrayEquals(bytes,File(root,"handle-0.txt").readBytes())
            assertTrue(runCatching { service.finishPrivateWrite(handles[0]) }.isFailure)
            assertTrue(runCatching { service.writePrivateFile(handles[0],bytes,1) }.isFailure)
            service.abortPrivateWrite(handles[0])
            val replacement = service.openPrivateWrite(File(root,"replacement.txt").path)
            service.abortPrivateWrite(replacement)
            service.abortPrivateWrite(replacement)
        } finally { handles.forEach { service.abortPrivateWrite(it) } }
    }

    @Test fun specialSourcesFailWithoutOpeningOrDeletingTheNodeInEveryCopyDirection() = fixture { root, _ ->
        val app = compose.activity.application as AFFileManagerApplication
        val fifo = File(root,"selected-pipe")
        Os.mkfifo(fifo.path,0x180)
        val destination = File(root,"destination").apply { check(mkdir()) }
        for (direction in 0..2) {
            val result = runCatching { runBlocking {
                when(direction) {
                    0 -> app.graph.privilegedFiles.copyWithin(listOf(fifo.path),destination.path,false,ConflictPolicy.KEEP_BOTH,OperationContext.background())
                    1 -> app.graph.privilegedFiles.copyFromLocal(listOf(fifo.path),destination.path,false,ConflictPolicy.KEEP_BOTH,OperationContext.background())
                    else -> app.graph.privilegedFiles.copyToLocal(listOf(fifo.path),destination,false,ConflictPolicy.KEEP_BOTH,OperationContext.background())
                }
            } }
            assertTrue("A special source was copied as a regular byte file",result.isFailure)
            assertTrue(android.system.OsConstants.S_ISFIFO(Os.lstat(fifo.path).st_mode))
            assertTrue(destination.list()!!.isEmpty())
        }
    }

    @Test fun acknowledgedCopiesPublishCompleteTinyAndBoundarySizedFiles() = fixture { root, _ ->
        val app = compose.activity.application as AFFileManagerApplication
        val source = File(root,"source").apply { check(mkdir()) }
        val sizes = listOf(0,1,8,65_535,65_536,65_537,262_161)
        sizes.forEach { size -> File(source,"size-$size.bin").writeBytes(ByteArray(size) { (it % 251).toByte() }) }
        val external = File(app.getExternalFilesDir("validation"),"metadata-write-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val local = File(root,"destination").apply { check(mkdir()) }
        var accepted = false
        try {
            for (destination in listOf(local,external)) {
                val result = runBlocking { app.graph.privilegedFiles.copyFromLocal(listOf(source.path),destination.path,false,
                    ConflictPolicy.KEEP_BOTH,OperationContext.background()) }
                assertEquals(1,result.copiedRoots)
                for (size in sizes) {
                    val original = File(source,"size-$size.bin")
                    val copy = File(destination,"source/size-$size.bin")
                    assertArrayEquals(original.readBytes(),copy.readBytes())
                }
            }
            accepted=true
        } finally {
            if (accepted) check(external.deleteRecursively())
            else android.util.Log.e("AFRootAudit","Retained failed acknowledged-write external fixture: $external")
        }
    }

    private fun fixture(block: (File,IPrivilegedFileService)->Unit) {
        assumeTrue("Actual UID0 fixture, not a local unprivileged policy-lookup substitute",
            InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val root = File(context.cacheDir,"metadata-contract-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected || app.graph.advancedAccess.state.value.error != null }
            assertEquals(AdvancedAccessBackend.SHIZUKU_ROOT,app.graph.advancedAccess.state.value.activeBackend)
            assertEquals(0,app.graph.advancedAccess.state.value.serviceUid)
            val service = app.graph.advancedAccess.privilegedTerminalServiceOrThrow()
            assertEquals(PrivilegedFileMetadata.VERSION,service.fileMetadataVersion)
            block(root,service)
            accepted=true
        } finally {
            try {
                if (accepted) {
                    Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
                } else android.util.Log.e("AFRootAudit","Retained failed metadata contract fixture: $root")
            } finally { compose.runOnUiThread { vm.setAdvancedAccessMode(previous) } }
        }
    }
}
