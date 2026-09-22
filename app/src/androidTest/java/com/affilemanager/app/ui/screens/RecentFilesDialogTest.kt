package com.affilemanager.app.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.affilemanager.app.data.RecentFileItem
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecentFilesDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchesRealSourcesSearchesAndExposesFileActions() {
        val added = item("new-report.pdf", "/storage/emulated/0/Download/new-report.pdf", 200)
        val opened = item("opened-notes.txt", "/storage/emulated/0/Documents/opened-notes.txt", 100)
        val copied = AtomicReference<List<FileEntry>>(emptyList())
        compose.setContent {
            AFFileManagerTheme {
                RecentFilesDialog(
                    addedItems = listOf(added),
                    openedItems = listOf(opened),
                    loading = false,
                    onRefresh = {},
                    onOpen = {},
                    onRename = {},
                    onShare = {},
                    onTrash = {},
                    onReveal = {},
                    onCopy = { entries, _ -> copied.set(entries) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("new-report.pdf").assertIsDisplayed()
        compose.onNodeWithText("opened-notes.txt").assertDoesNotExist()
        compose.onNodeWithTag("recent_tab_opened").performClick()
        compose.onNodeWithText("opened-notes.txt").assertIsDisplayed()
        compose.onNodeWithText("new-report.pdf").assertDoesNotExist()

        compose.onNodeWithTag("recent_search_toggle").performClick()
        compose.onNodeWithTag("recent_files_search").performTextInput("missing")
        compose.onNodeWithText("opened-notes.txt").assertDoesNotExist()
        compose.onNodeWithTag("recent_files_search").performTextClearance()
        compose.onNodeWithText("opened-notes.txt").assertIsDisplayed()

        compose.onNodeWithTag("recent_file_menu").performClick()
        compose.onNodeWithTag("recent_action_copy").performClick()
        assertEquals(listOf(opened.entry), copied.get())
    }

    private fun item(name: String, path: String, time: Long) = RecentFileItem(
        FileEntry(path, name, EntryKind.DOCUMENT, 12, time, false, true, true),
        time,
    )
}
