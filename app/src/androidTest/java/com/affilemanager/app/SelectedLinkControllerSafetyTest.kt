package com.affilemanager.app

import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.UiMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises public controllers as well as the low-level link checks. Private synthetic files only. */
class SelectedLinkControllerSafetyTest {
    @Test fun aRejectedLinkDoesNotReplaceTheExistingCopyClipboard() = fixture { vm, root, messages ->
        val existing = root.resolve("already-copied.txt").apply { writeText("existing clipboard") }
        val target = root.resolve("not-selected.txt").apply { writeText("not selected") }
        val link = root.resolve("selected-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        main { vm.copyEntries(listOf(existing.path), move = false) }
        val previous = vm.clipboard.value
        messages.clear()
        main { vm.copyEntries(listOf(link.path), move = false) }
        assertEquals("The clipboard resolved an unsupported selected link", previous, vm.clipboard.value)
        assertTrue("Rejected selection needs visible error feedback", messages.any { it.error })
        assertEquals("not selected", target.readText())
    }

    @Test fun copyMoreRejectsTheLinkWithoutAddingItsUnselectedTarget() = fixture { vm, root, messages ->
        val existing = root.resolve("already-copied.txt").apply { writeText("existing clipboard") }
        val target = root.resolve("not-selected.txt").apply { writeText("not selected") }
        val link = root.resolve("selected-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        main { vm.navigate(PanelId.LEFT, root.path, rememberHistory = false); vm.copyEntries(listOf(existing.path), move = false) }
        withTimeout(10_000) { vm.leftPanel.first { !it.loading && it.entries.any { e -> e.absolutePath == link.path } } }
        val previous = vm.clipboard.value
        messages.clear()
        main { vm.selectPaths(PanelId.LEFT, listOf(link.path)); vm.addSelectionToClipboard(PanelId.LEFT) }
        assertEquals("Copy more appended an unselected link target", previous, vm.clipboard.value)
        assertTrue(messages.any { it.error })
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun quickLocationTrashCannotResolveASelectedLinkIntoItsTarget() = fixture { vm, root, messages ->
        val target = root.resolve("not-selected.txt").apply { writeText("not selected") }
        val link = root.resolve("selected-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val before = vm.operations.value.map { it.id }.toSet()
        main { vm.moveLocalPathsToTrash(listOf(link.path)) }
        awaitFailureOrCompletion(vm, before, messages)
        assertTrue("A quick-location delete moved an unselected target", target.isFile)
        assertEquals("not selected", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(messages.any { it.error } || vm.operations.value.any { it.id !in before && it.status == OperationStatus.FAILED })
    }

    @Test fun analysisTrashRejectsAFileReplacedByALinkSinceItsScan() = fixture { vm, root, messages ->
        val scanned = root.resolve("selected.txt").apply { writeText("selected") }
        val target = root.resolve("not-selected.txt").apply { writeText("not selected") }
        main { vm.analyze(root.path) }
        withTimeout(15_000) { vm.analysisState.first { !it.running && it.analysis != null } }
        check(scanned.delete())
        Files.createSymbolicLink(scanned.toPath(), target.toPath())
        val before = vm.operations.value.map { it.id }.toSet()
        messages.clear()
        main { vm.trashAnalysisSelection(listOf(scanned.path), keepOneCopy = false) }
        awaitFailureOrCompletion(vm, before, messages)
        assertTrue("Cleanup moved a link's unselected target", target.isFile)
        assertEquals("not selected", target.readText())
        assertTrue(Files.isSymbolicLink(scanned.toPath()))
        assertTrue(messages.any { it.error } || vm.operations.value.any { it.id !in before && it.status == OperationStatus.FAILED })
    }

    private fun fixture(block: suspend (MainViewModel, File, MutableList<UiMessage>) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(Build.MODEL.contains("sdk", true) && app.packageName.endsWith(".debug"))
        val root = File(app.cacheDir, "controller-link-${UUID.randomUUID()}").apply { check(mkdir()) }.canonicalFile
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        main { vm = MainViewModel(app); store.put("link-safety", vm) }
        val messages: MutableList<UiMessage> = CopyOnWriteArrayList()
        val collector = launch(Dispatchers.Unconfined) { vm.messages.collect { messages += it } }
        try { block(vm, root, messages) }
        finally {
            app.graph.trash.list().filter { it.originalPath.startsWith(root.path + File.separator) }
                .forEach { app.graph.trash.deleteForever(it.id).getOrThrow() }
            collector.cancelAndJoin()
            main { store.clear() }
            removeFixtureWithoutFollowingLinks(root)
        }
    }

    private suspend fun awaitFailureOrCompletion(vm: MainViewModel, previous: Set<String>, messages: List<UiMessage>) {
        withTimeout(15_000) {
            while (messages.none { it.error } && vm.operations.value.none {
                it.id !in previous && it.status in setOf(OperationStatus.SUCCEEDED, OperationStatus.COMPLETED_WITH_ERRORS, OperationStatus.FAILED, OperationStatus.CANCELLED)
            }) delay(20)
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun removeFixtureWithoutFollowingLinks(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeFixtureWithoutFollowingLinks)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
