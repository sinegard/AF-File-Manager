package com.affilemanager.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Real authorized UID-0 provider; fixtures remain inside this test app's own directories. */
class PrivilegedMoveSafetyAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localToPrivilegedMoveKeepsAnUncopiedLateChild() { repeat(3) { movedSourceSafety(0, false) } }
    @Test fun privilegedToLocalMoveKeepsAnUncopiedLateChild() = movedSourceSafety(1, false)
    @Test fun privilegedFallbackMoveKeepsAnUncopiedLateChild() = movedSourceSafety(2, false)
    @Test fun localToPrivilegedMoveKeepsItsSourceIfThePublishedCopyChanges() = movedSourceSafety(0, true)
    @Test fun privilegedToLocalMoveKeepsItsSourceIfThePublishedCopyChanges() = movedSourceSafety(1, true)
    @Test fun privilegedFallbackMoveKeepsItsSourceIfThePublishedCopyChanges() = movedSourceSafety(2, true)

    @Test fun ordinaryLocalToPrivilegedMoveCompletesWithMatchingContent() = ordinaryMove(0)
    @Test fun ordinaryPrivilegedToLocalMoveCompletesWithMatchingContent() = ordinaryMove(1)
    @Test fun ordinaryPrivilegedFallbackMoveCompletesWithMatchingContent() = ordinaryMove(2)

    @Test fun localToPrivilegedCopyHonorsCancellationBeforePublishing() = interruptedCopy(0, false)
    @Test fun privilegedToLocalCopyHonorsCancellationBeforePublishing() = interruptedCopy(1, false)
    @Test fun privilegedFallbackCopyHonorsCancellationBeforePublishing() = interruptedCopy(2, false)
    @Test fun localToPrivilegedCopyCanPauseAndContinue() = interruptedCopy(0, true)
    @Test fun privilegedToLocalCopyCanPauseAndContinue() = interruptedCopy(1, true)
    @Test fun privilegedFallbackCopyCanPauseAndContinue() = interruptedCopy(2, true)

    private fun interruptedCopy(direction: Int, pause: Boolean) = withRoot { app -> kotlinx.coroutines.supervisorScope {
        val local = File(app.cacheDir, "root-interrupt-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val external = File(app.getExternalFilesDir(null), "root-interrupt-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(if (direction == 1) external else local, "selected.bin")
        val destination = File(if (direction == 1) local else external, "destination").apply { check(mkdir()) }
        source.outputStream().buffered().use { stream ->
            val block = ByteArray(65536) { (it % 251).toByte() }
            repeat(128) { stream.write(block) }
        }
        val original = hash(source)
        val paused = MutableStateFlow(false)
        var snapshot = OperationSnapshot("root-interrupt", "Owned root interruption", OperationStatus.RUNNING)
        var requested = false
        val pausedSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        var job: kotlinx.coroutines.Deferred<com.affilemanager.app.advanced.PrivilegedTransferResult>? = null
        val operation = OperationContext("root-interrupt", paused) { transform ->
            snapshot = transform(snapshot)
            if (!requested && snapshot.completedBytes > 0) {
                requested = true
                if (pause) paused.value = true else requireNotNull(job).cancel(CancellationException("Owned cancellation fixture"))
            }
            if (snapshot.status == OperationStatus.PAUSED) pausedSeen.set(true)
        }
        var accepted = false
        try {
            val transfer = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                when (direction) {
                    0 -> app.graph.privilegedFiles.copyFromLocal(listOf(source.path), destination.path, false, ConflictPolicy.KEEP_BOTH, operation)
                    1 -> app.graph.privilegedFiles.copyToLocal(listOf(source.path), destination, false, ConflictPolicy.KEEP_BOTH, operation)
                    else -> app.graph.privilegedFiles.copyWithin(listOf(source.path), destination.path, false, ConflictPolicy.KEEP_BOTH, operation)
                }
            }
            job = transfer
            transfer.start()
            if (pause) {
                withTimeout(10000) { while (!pausedSeen.get() && !transfer.isCompleted) delay(10) }
                assertTrue("Pause was ignored during an actual privileged copy", pausedSeen.get())
                delay(150)
                assertTrue("The paused operation completed", transfer.isActive)
                assertFalse("A paused copy was published", File(destination, source.name).exists())
                paused.value = false
                assertEquals(1, transfer.await().copiedRoots)
                assertEquals(original, hash(File(destination, source.name)))
                assertEquals(listOf(source.name), destination.list()!!.toList())
            } else {
                val result = runCatching { transfer.await() }
                assertTrue("The actual progress callback never requested cancellation", requested)
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertFalse("A cancelled privileged copy was still published", File(destination, source.name).exists())
                assertTrue("A cancelled copy retained its owned partial", destination.list()!!.isEmpty())
            }
            assertEquals(original, hash(source))
            accepted = true
        } finally {
            paused.value = false
            job?.let { it.cancel(); runCatching { it.join() } }
            if (accepted) { removeOwned(local); removeOwned(external) }
            else android.util.Log.e("AFRootAudit", "Retained interrupted-copy fixtures: $local ; $external")
        }
    } }

    private fun hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ordinaryMove(direction: Int) = withRoot { app ->
        val local = File(app.cacheDir, "root-normal-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val external = File(app.getExternalFilesDir(null), "root-normal-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(if (direction == 1) external else local, "selected").apply { check(mkdir()) }
        val destination = File(if (direction == 1) local else external, "destination").apply { check(mkdir()) }
        val expected = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        File(source, "verified.bin").writeBytes(expected)
        File(source, "empty").apply { check(mkdir()) }
        var accepted = false
        try {
            val operation = OperationContext.background()
            val result = when (direction) {
                0 -> app.graph.privilegedFiles.copyFromLocal(listOf(source.path), destination.path, true, ConflictPolicy.KEEP_BOTH, operation)
                1 -> app.graph.privilegedFiles.copyToLocal(listOf(source.path), destination, true, ConflictPolicy.KEEP_BOTH, operation)
                else -> app.graph.privilegedFiles.copyWithin(listOf(source.path), destination.path, true, ConflictPolicy.KEEP_BOTH, operation)
            }
            assertEquals(1, result.copiedRoots)
            assertEquals(0, result.skippedRoots)
            assertFalse(source.exists())
            assertArrayEquals(expected, File(destination, "selected/verified.bin").readBytes())
            assertTrue(File(destination, "selected/empty").isDirectory)
            accepted = true
        } finally {
            if (accepted) { removeOwned(local); removeOwned(external) }
            else android.util.Log.e("AFRootAudit", "Retained ordinary-move failure fixtures: $local ; $external")
        }
    }

    private fun movedSourceSafety(direction: Int, corruptTarget: Boolean) = withRoot { app ->
        val local = File(app.cacheDir, "root-move-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val external = File(app.getExternalFilesDir(null), "root-move-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(if (direction == 1) external else local, "selected").apply { check(mkdir()) }
        val destination = File(if (direction == 1) local else external, "destination").apply { check(mkdir()) }
        val input = File(source, "verified.txt").apply { writeText("original") }
        val output = File(destination, "selected/verified.txt")
        val late = File(source, "late.txt")
        val paused = MutableStateFlow(false)
        var snapshot = OperationSnapshot("owned-root-move", "Owned root move", OperationStatus.RUNNING)
        var armed = false
        var injected = false
        val operation = OperationContext("owned-root-move", paused) { transform ->
            snapshot = transform(snapshot)
            if (!armed && snapshot.message == "SHA-256") { armed = true; paused.value = true }
            if (armed && !injected && snapshot.status == OperationStatus.PAUSED) {
                check(output.isFile) { "The injection must occur after actual publication" }
                if (corruptTarget) output.writeText("tampered") else late.writeText("new, never copied")
                injected = true
                paused.value = false
            }
        }
        var accepted = false
        try {
            val result = runCatching {
                when (direction) {
                    0 -> app.graph.privilegedFiles.copyFromLocal(listOf(source.path), destination.path, true, ConflictPolicy.KEEP_BOTH, operation)
                    1 -> app.graph.privilegedFiles.copyToLocal(listOf(source.path), destination, true, ConflictPolicy.KEEP_BOTH, operation)
                    else -> app.graph.privilegedFiles.copyWithin(listOf(source.path), destination.path, true, ConflictPolicy.KEEP_BOTH, operation)
                }
            }
            if (!injected && result.isFailure) throw AssertionError("Move failed before the published-copy injection", result.exceptionOrNull())
            assertTrue("The real published-copy checkpoint was not reached", injected)
            if (corruptTarget) {
                assertTrue("The verified original was deleted after its destination changed", input.isFile)
                assertEquals("original", input.readText())
                assertEquals("tampered", output.readText())
            } else {
                assertTrue("An unverified late child was deleted by a privileged move", late.isFile)
                assertEquals("new, never copied", late.readText())
                assertEquals("original", output.readText())
            }
            assertTrue("A changed move must report its incomplete source cleanup", result.isFailure)
            accepted = true
        } finally {
            paused.value = false
            if (accepted) { removeOwned(local); removeOwned(external) }
            else android.util.Log.e("AFRootAudit", "Retained owned failed fixtures: $local ; $external")
        }
    }

    @Test fun renameCannotReplaceABrokenDestinationLink() = withRoot { app ->
        val root = File(app.cacheDir, "root-rename-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(root, "source.txt").apply { writeText("original") }
        val absent = File(root, "unselected.txt")
        val link = File(root, "target.txt")
        Files.createSymbolicLink(link.toPath(), absent.toPath())
        try {
            assertTrue(app.graph.privilegedFiles.rename(source.path, link.name).isFailure)
            assertEquals("original", source.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertFalse(absent.exists())
        } finally { removeOwned(root) }
    }

    @Test fun copyCannotSilentlyReplaceABrokenDestinationLink() = withRoot { app ->
        val root = File(app.cacheDir, "root-copy-link-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(root, "source.txt").apply { writeText("original") }
        val destination = File(root, "destination").apply { check(mkdir()) }
        val absent = File(root, "unselected.txt")
        val link = File(destination, source.name)
        Files.createSymbolicLink(link.toPath(), absent.toPath())
        try {
            val result = runCatching { app.graph.privilegedFiles.copyWithin(listOf(source.path), destination.path, false, ConflictPolicy.KEEP_BOTH, OperationContext.background()) }
            assertTrue("A broken link was treated as an unused destination", result.isFailure)
            assertEquals("original", source.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertFalse(absent.exists())
        } finally { removeOwned(root) }
    }

    @Test fun keepBothSkipsABrokenNumberedDestinationSlot() = withRoot { app ->
        val root = File(app.cacheDir, "root-numbered-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source = File(root, "source.txt").apply { writeText("original") }
        val destination = File(root, "destination").apply { check(mkdir()) }
        val existing = File(destination, "source.txt").apply { writeText("existing") }
        val absent = File(root, "unselected.txt")
        val reserved = File(destination, "source (1).txt")
        Files.createSymbolicLink(reserved.toPath(), absent.toPath())
        var accepted = false
        try {
            app.graph.privilegedFiles.copyWithin(listOf(source.path), destination.path, false, ConflictPolicy.KEEP_BOTH, OperationContext.background())
            assertEquals("original", source.readText())
            assertEquals("existing", existing.readText())
            assertTrue("Keep both replaced an unowned numbered link", Files.isSymbolicLink(reserved.toPath()))
            assertFalse(absent.exists())
            assertEquals("original", File(destination, "source (2).txt").readText())
            accepted = true
        } finally {
            if (accepted) removeOwned(root)
            else android.util.Log.e("AFRootAudit", "Retained failed numbered-copy fixture: $root")
        }
    }

    private fun withRoot(block: suspend (AFFileManagerApplication) -> Unit) = runBlocking {
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
            block(app)
        } finally { compose.runOnUiThread { vm.setAdvancedAccessMode(previous) } }
    }

    private fun removeOwned(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeOwned)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
