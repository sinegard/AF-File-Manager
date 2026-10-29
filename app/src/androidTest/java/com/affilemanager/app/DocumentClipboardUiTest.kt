package com.affilemanager.app

import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.data.SafLocation
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.workflow.AfLocationKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DocumentClipboardUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectionCopyMoreCutAndPasteUseTheSharedClipboardInListAndGrid(): Unit = runBlocking {
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission &&
            it.uri.authority == "com.android.externalstorage.documents" && DocumentsContract.getTreeDocumentId(it.uri).endsWith(":") &&
            !DocumentsContract.getTreeDocumentId(it.uri).startsWith("primary:") }
        org.junit.Assume.assumeNotNull(grant)
        val folder = app.graph.safFiles.createDirectory(requireNotNull(grant).uri.toString(), "AF-clipboard-${System.nanoTime()}").getOrThrow()
        app.graph.safFiles.createFile(folder.uri, "first.txt").getOrThrow()
        app.graph.safFiles.createFile(folder.uri, "second.txt").getOrThrow()
        try {
            compose.runOnUiThread { vm.openSafLocation(SafLocation(folder.uri, folder.name, canWrite = true)) }
            compose.waitUntil(10_000) { !vm.safBrowser.value.loading && vm.safBrowser.value.entries.size == 2 }
            compose.onNodeWithTag("saf_entry_first.txt").performTouchInput { longClick() }
            compose.onNodeWithTag("saf_selection_header").assertIsDisplayed()
            compose.onNodeWithTag("saf_entry_second.txt").performClick()
            assertEquals(2, vm.safBrowser.value.selectedUris.size)
            compose.onNodeWithContentDescription("Deselect all").performClick()
            assertTrue(vm.safBrowser.value.selectedUris.isEmpty())
            compose.onNodeWithTag("saf_actions_first.txt").performClick()
            compose.onNodeWithText("Copy", substring = false).performClick()
            assertEquals(1, vm.afClipboard.value?.sources?.size)
            assertNull(vm.clipboard.value)
            compose.onNodeWithTag("saf_actions_second.txt").performClick()
            compose.onNodeWithText("Copy more", substring = false).performClick()
            assertEquals(2, vm.afClipboard.value?.sources?.size)
            assertTrue(vm.afClipboard.value!!.sources.all { it.location.kind == AfLocationKind.DOCUMENT })
            compose.onNodeWithTag("directory_layout_saf").performClick()
            compose.onNodeWithTag("saf_entry_second.txt").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Move", substring = false).performClick()
            assertTrue(vm.afClipboard.value?.moveAfterVerifiedCopies == true)
            assertEquals(1, vm.afClipboard.value?.sources?.size)
            compose.onNode(hasContentDescription("Folder actions") and hasAnyAncestor(hasTestTag("directory_toolbar_saf"))).performClick()
            compose.onNodeWithTag("saf_paste").performClick()
            compose.onNodeWithTag("transfer_options_dialog").assertIsDisplayed()
            assertTrue(vm.afClipboard.value?.moveAfterVerifiedCopies == true)
            compose.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(uiAuditDirectory(app), "document-grid-clipboard.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
            compose.onNodeWithContentDescription("Close").performClick()
        } finally {
            compose.runOnUiThread { vm.closeAfWorkflowCenter(); vm.closeSafBrowser(); vm.clearAfClipboard() }
            app.graph.safFiles.delete(folder.uri).getOrThrow()
        }
    }
}
