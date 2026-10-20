package com.affilemanager.app

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.core.CopyPathRules
import com.affilemanager.app.data.*
import com.affilemanager.app.ui.*
import com.affilemanager.app.ui.localization.AppLanguageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class NewIssueWorkflowsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun english() { compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, "en") } }
    private fun vm() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private fun navigate(vm: MainViewModel, directory: File) {
        compose.runOnUiThread { vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, directory.path) }
        compose.waitUntil(15_000) { vm.leftPanel.value.path == directory.canonicalPath && !vm.leftPanel.value.loading }
        compose.waitForIdle()
    }

    @Test fun unsupportedFileOffersADirectExternalOpenButton() {
        val vm = vm()
        val source = File(compose.activity.getExternalFilesDir(null), "unsupported-${System.nanoTime()}.af-fixture").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        try {
            compose.runOnUiThread { vm.open(LocalFileRepository(compose.activity).toEntry(source)) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("unsupported_open_with").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("unsupported_open_with").assertHasClickAction().assertIsDisplayed()
        } finally { compose.runOnUiThread { vm.closePreview() }; source.delete() }
    }

    @Test fun localMenusAndSelectionShareVisibilityAndCopyQuotedPathsInListAndGrid() {
        val vm = vm()
        val root = File(compose.activity.getExternalFilesDir(null), "issue-menu-${System.nanoTime()}").apply { mkdirs() }
        val sources = listOf("a space.txt", "b'quote.txt").map { File(root, it).apply { writeText("original") } }
        val app = compose.activity.application as AFFileManagerApplication
        val previous = vm.featureVisibility.value
        try {
            navigate(vm, root)
            for (grid in listOf(false, true)) {
                compose.runOnUiThread {
                    vm.clearSelection(PanelId.LEFT)
                    vm.setDirectoryDisplaySettings(PanelId.LEFT, DirectoryDisplaySettings(layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST))
                    listOf(OptionalFeature.SHARING, OptionalFeature.FILE_COPY, OptionalFeature.FILE_INFO, OptionalFeature.FILE_BOOKMARK).forEach { vm.setFeatureVisible(it, false) }
                }
                compose.onNodeWithContentDescription("File actions: ${sources.first().name}").performClick()
                compose.onNodeWithText("Share").assertDoesNotExist()
                compose.onNodeWithText("Copy").assertDoesNotExist()
                compose.onNodeWithText("Info").assertDoesNotExist()
                compose.onNodeWithText("Copy path (POSIX)").performClick()
                compose.runOnIdle { assertEquals(CopyPathRules.quotedArguments(listOf(sources.first().canonicalPath)),
                    compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()) }
                compose.onNodeWithTag("local_entry_${sources.first().canonicalPath}").performTouchInput { longClick() }
                compose.waitUntil(5000) { vm.leftPanel.value.selectedPaths.isNotEmpty() }
                compose.onNodeWithContentDescription("File actions: ${sources.first().name}").assertDoesNotExist()
                compose.onNodeWithContentDescription("File actions: ${sources.last().name}").assertDoesNotExist()
                compose.onNodeWithContentDescription("Share").assertDoesNotExist()
                compose.onNodeWithContentDescription("Copy").assertDoesNotExist()
                compose.runOnUiThread { vm.selectPaths(PanelId.LEFT, sources.map { it.canonicalPath }) }
                compose.onNodeWithTag("copy_quoted_paths").performClick()
                compose.runOnIdle { assertEquals(CopyPathRules.quotedArguments(sources.map { it.canonicalPath }),
                    compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()) }
                assertTrue(sources.all { it.readText() == "original" })
            }
        } finally {
            compose.runOnUiThread {
                OptionalFeature.entries.forEach { vm.setFeatureVisible(it, previous.isVisible(it)) }
                vm.clearSelection(PanelId.LEFT)
                vm.navigate(PanelId.LEFT, requireNotNull(root.parentFile).path)
            }
            root.deleteRecursively()
        }
    }

    @Test fun typedRelativeDirectoryOpensAndProviderSelectionIsAvailable() {
        val vm = vm()
        val root = File(compose.activity.getExternalFilesDir(null), "issue-address-${System.nanoTime()}").apply { mkdirs() }
        val child = File(root, "child folder").apply { mkdir() }
        try {
            navigate(vm, root)
            compose.onNodeWithTag("directory_address_local_LEFT").performClick()
            compose.onNodeWithTag("directory_choose_provider").assertHasClickAction()
            compose.onNodeWithTag("directory_address_input").performTextReplacement(child.name)
            compose.onNodeWithTag("directory_address_open").performClick()
            compose.waitUntil(10_000) { vm.leftPanel.value.path == child.canonicalPath && !vm.leftPanel.value.loading }
            compose.onNodeWithTag("directory_address_local_LEFT").assertIsDisplayed()
        } finally { root.deleteRecursively() }
    }

    @Test fun lyricsSubtitlesAndBashUseTheActualAfEditorAndSaveBackWithoutRenaming() {
        val vm = vm()
        val root = File(compose.activity.getExternalFilesDir(null), "issue-text-${System.nanoTime()}").apply { mkdirs() }
        val repository = LocalFileRepository(compose.activity)
        try {
            listOf("lyrics.lrc", "subtitles.srt", "captions.vtt", ".bashrc").forEach { name ->
                val source = File(root, name).apply { writeText("hello\nsecond line\n") }
                compose.runOnUiThread { vm.open(repository.toEntry(source)) }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("full-text-editor").fetchSemanticsNodes().isNotEmpty() }
                val working = requireNotNull(vm.fileEditState.value.session).workingFile
                compose.onNodeWithTag("full-text-editor").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("updated\nsecond line\n")) }
                compose.waitUntil(5000) { vm.fileEditState.value.text?.startsWith("updated") == true }
                assertEquals("hello\nsecond line\n", source.readText())
                compose.onNodeWithTag("save-edit-original").performClick()
                compose.waitUntil(10_000) { source.readText() == "updated\nsecond line\n" }
                compose.runOnUiThread { vm.closePreview() }
                compose.waitUntil(5000) { !working.exists() }
                assertEquals(name, source.name)
            }
        } finally {
            compose.runOnUiThread { vm.closePreview() }
            root.deleteRecursively()
        }
    }
}
