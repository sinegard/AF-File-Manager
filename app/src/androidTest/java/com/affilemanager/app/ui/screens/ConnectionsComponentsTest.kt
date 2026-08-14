package com.affilemanager.app.ui.screens

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemoteErrorPresenter
import com.affilemanager.app.network.RemoteOperation
import com.affilemanager.app.ui.NetworkUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ConnectionsComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectedBrowserExposesCopyActionsInBothDirectionsIncludingFolders() {
        val folder = RemoteEntry("remote-folder", "/remote-folder", true, 0, null)
        val file = RemoteEntry("remote.txt", "/remote.txt", false, 12, null)
        val pasteRequests = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                Column {
                    RemoteBrowser(
                        state = NetworkUiState(
                            connectedProfile = profile(),
                            path = "/remote",
                            entries = listOf(folder, file),
                        ),
                        localDirectory = "/local/target",
                        compactToolbar = true,
                        onBack = {},
                        onForward = {},
                        onUp = {},
                        onRefresh = {},
                        onOpen = {},
                        onDownload = {},
                        onToggleSelection = {},
                        onClearSelection = {},
                        onSelectAll = {},
                        onDownloadSelected = {},
                        onCopySelected = {},
                        localClipboardCount = 2,
                        onPasteLocalClipboard = { pasteRequests.incrementAndGet() },
                        onChooseUpload = {},
                        onCreateFolder = {},
                        onRename = {},
                        onDelete = {},
                        onSync = {},
                        onToggleHidden = {},
                        onToggleGrid = {},
                        onSort = {},
                        onDisconnect = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Folder actions").performClick()
        compose.onNodeWithTag("remote_upload_choose").assertIsEnabled().assertHasClickAction()
        compose.onNodeWithTag("remote_paste_local").assertIsEnabled().assertHasClickAction()
        compose.onNodeWithText("Paste (2)").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { pasteRequests.get() == 1 }
        compose.onNodeWithContentDescription("File actions: remote.txt").performClick()
        compose.onNodeWithText("Copy to phone").assertHasClickAction()
    }

    @Test
    fun remoteSelectionUsesLongPressSelectAllToggleAndOneGroupCopy() {
        val folder = RemoteEntry("remote-folder", "/remote-folder", true, 0, null)
        val file = RemoteEntry("remote.txt", "/remote.txt", false, 12, null)
        var copied = emptyList<String>()
        var observedSelection = emptySet<String>()
        compose.setContent {
            MaterialTheme {
                var state by remember {
                    mutableStateOf(
                        NetworkUiState(
                            connectedProfile = profile(),
                            path = "/remote",
                            entries = listOf(folder, file),
                        ),
                    )
                }
                Column {
                    RemoteBrowser(
                        state = state,
                        localDirectory = "/local/target",
                        compactToolbar = true,
                        onBack = {},
                        onForward = {},
                        onUp = {},
                        onRefresh = {},
                        onOpen = {},
                        onDownload = {},
                        onToggleSelection = { path ->
                            state = state.copy(
                                selectedPaths = state.selectedPaths.toMutableSet().apply {
                                    if (!add(path)) remove(path)
                                },
                            )
                            observedSelection = state.selectedPaths
                        },
                        onClearSelection = {
                            state = state.copy(selectedPaths = emptySet())
                            observedSelection = state.selectedPaths
                        },
                        onSelectAll = {
                            state = state.copy(selectedPaths = state.entries.map(RemoteEntry::path).toSet())
                            observedSelection = state.selectedPaths
                        },
                        onDownloadSelected = {
                            copied = state.entries.filter { it.path in state.selectedPaths }.map(RemoteEntry::path)
                        },
                        onCopySelected = {
                            copied = state.entries.filter { it.path in state.selectedPaths }.map(RemoteEntry::path)
                            state = state.copy(selectedPaths = emptySet())
                            observedSelection = state.selectedPaths
                        },
                        localClipboardCount = 0,
                        onPasteLocalClipboard = {},
                        onChooseUpload = {},
                        onCreateFolder = {},
                        onRename = {},
                        onDelete = {},
                        onSync = {},
                        onToggleHidden = {},
                        onToggleGrid = {},
                        onSort = {},
                        onDisconnect = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("remote_entry_/remote-folder").performTouchInput { longClick() }
        compose.onNodeWithText("Selected: 1").fetchSemanticsNode()
        compose.runOnIdle {
            assertEquals(setOf(folder.path), observedSelection)
        }
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.runOnIdle {
            assertEquals(setOf(folder.path, file.path), observedSelection)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Selected: 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Deselect all").performClick()
        compose.onAllNodesWithText("Selected:", substring = true).assertCountEquals(0)

        compose.onNodeWithTag("remote_entry_/remote-folder").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.runOnIdle {
            assertEquals(listOf(folder.path, file.path), copied)
            assertEquals(emptySet<String>(), observedSelection)
        }
        compose.onAllNodesWithText("Selected:", substring = true).assertCountEquals(0)
    }

    @Test
    fun remoteFolderUsesTheSameNavigationAndDisplayMenuPatternAsLocalStorage() {
        val back = AtomicInteger()
        val forward = AtomicInteger()
        val up = AtomicInteger()
        val hidden = AtomicInteger()
        val grid = AtomicInteger()
        val sort = AtomicInteger()
        val refresh = AtomicInteger()
        val disconnect = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                RemoteBrowser(
                    state = NetworkUiState(
                        connectedProfile = profile(),
                        path = "/remote",
                        entries = listOf(RemoteEntry("file.txt", "/remote/file.txt", false, 12, null)),
                        backHistory = listOf("/"),
                        forwardHistory = listOf("/future"),
                    ),
                    localDirectory = "/local/target",
                    compactToolbar = true,
                    onBack = { back.incrementAndGet() },
                    onForward = { forward.incrementAndGet() },
                    onUp = { up.incrementAndGet() },
                    onRefresh = { refresh.incrementAndGet() },
                    onOpen = {},
                    onDownload = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onSelectAll = {},
                    onDownloadSelected = {},
                    onCopySelected = {},
                    localClipboardCount = 0,
                    onPasteLocalClipboard = {},
                    onChooseUpload = {},
                    onCreateFolder = {},
                    onRename = {},
                    onDelete = {},
                    onSync = {},
                    onToggleHidden = { hidden.incrementAndGet() },
                    onToggleGrid = { grid.incrementAndGet() },
                    onSort = { sort.incrementAndGet() },
                    onDisconnect = { disconnect.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Forward").performClick()
        compose.onNodeWithContentDescription("Up").performClick()
        clickFolderMenuItem("Show hidden files")
        clickFolderMenuItem("Show grid")
        clickFolderMenuItem("By size")
        clickFolderMenuItem("Refresh")
        clickFolderMenuItem("Disconnect")

        compose.runOnIdle {
            assertEquals(1, back.get())
            assertEquals(1, forward.get())
            assertEquals(1, up.get())
            assertEquals(1, hidden.get())
            assertEquals(1, grid.get())
            assertEquals(1, sort.get())
            assertEquals(1, refresh.get())
            assertEquals(1, disconnect.get())
        }
    }

    @Test
    fun localPickerBrowsesIntoFoldersAndReturnsNestedSelection() {
        var copied = emptyList<String>()
        val folder = localEntry("folder", EntryKind.DIRECTORY)
        val file = localEntry("file.txt", EntryKind.DOCUMENT)
        val nested = localEntry("folder/nested.txt", EntryKind.DOCUMENT)
        compose.setContent {
            MaterialTheme {
                LocalUploadDialog(
                    initialDirectoryPath = "/local",
                    remotePath = "/remote",
                    initialEntries = listOf(folder, file),
                    initiallySelected = emptySet(),
                    loadDirectory = { path ->
                        Result.success(if (path == folder.absolutePath) listOf(nested) else listOf(folder, file))
                    },
                    onDismiss = {},
                    onCopy = { copied = it },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("folder").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("folder").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("nested.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("nested.txt").performClick()
        compose.onNodeWithText("Copy (1)").performClick()

        compose.runOnIdle { assertEquals(listOf(nested.absolutePath), copied) }
    }

    @Test
    fun localPickerLongPressSelectsTheWholeFolderWithoutOpeningIt() {
        val folder = localEntry("folder", EntryKind.DIRECTORY)
        var copied = emptyList<String>()
        compose.setContent {
            MaterialTheme {
                LocalUploadDialog(
                    initialDirectoryPath = "/local",
                    remotePath = "/remote",
                    initialEntries = listOf(folder),
                    initiallySelected = emptySet(),
                    loadDirectory = { Result.success(listOf(folder)) },
                    onDismiss = {},
                    onCopy = { copied = it },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("folder").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("local_upload_entry_${folder.absolutePath}").performTouchInput { longClick() }
        compose.onNodeWithText("Copy (1)").performClick()

        compose.runOnIdle { assertEquals(listOf(folder.absolutePath), copied) }
    }

    @Test
    fun localPickerSelectAllActionAlsoClearsEverySelection() {
        val entries = listOf(
            localEntry("folder", EntryKind.DIRECTORY),
            localEntry("file.txt", EntryKind.DOCUMENT),
        )
        compose.setContent {
            MaterialTheme {
                LocalUploadDialog(
                    initialDirectoryPath = "/local",
                    remotePath = "/remote",
                    initialEntries = entries,
                    initiallySelected = setOf(entries.first().absolutePath),
                    loadDirectory = { Result.success(entries) },
                    onDismiss = {},
                    onCopy = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("Copy (2)").fetchSemanticsNode()
        compose.onNodeWithContentDescription("Deselect all").performClick()
        compose.onAllNodesWithText("Selected:", substring = true).assertCountEquals(0)
    }

    @Test
    fun corruptedLegacyProfileNeverRendersPastedCredentialLines() {
        val marker = "TOP_SECRET_MARKER"
        val corrupted = profile().copy(
            name = "Office\naccount\n$marker",
            host = "192.0.2.10\naccount\n$marker",
        )
        compose.setContent {
            MaterialTheme {
                ProfileCard(corrupted, loading = false, onConnect = {}, onEdit = {}, onDelete = {})
            }
        }

        compose.onAllNodesWithText(marker, substring = true).assertCountEquals(0)
        compose.onNodeWithText("Invalid connection data", substring = true).fetchSemanticsNode()
    }

    @Test
    fun corruptedProfileEditorKeepsOnlySafeFirstLines() {
        val marker = "TOP_SECRET_MARKER"
        val corrupted = profile().copy(
            name = "Office\naccount\n$marker",
            host = "192.0.2.10\naccount\n$marker",
        )
        compose.setContent {
            MaterialTheme {
                NetworkProfileDialog(corrupted, onDismiss = {}, onSave = { _, _, _ -> })
            }
        }

        compose.onAllNodesWithText(marker, substring = true).assertCountEquals(0)
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun networkProfileEditorRemovesServerAddressSpacesBeforeSaving() {
        var savedProfile: NetworkProfile? = null
        compose.setContent {
            MaterialTheme {
                NetworkProfileDialog(
                    existingProfile = profile(),
                    onDismiss = {},
                    onSave = { saved, _, _ -> savedProfile = saved },
                )
            }
        }

        val hostField = compose.onNodeWithTag("network_host")
        hostField.performTextClearance()
        hostField.performTextInput(" 203 . 0 . 113 . 190 ")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals("203.0.113.190", savedProfile?.host) }
    }

    @Test
    fun networkProfileEditorExcludesAndroidAutofill() {
        var dialogView: View? = null
        compose.setContent {
            MaterialTheme {
                NetworkProfileDialog(
                    existingProfile = null,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onAutofillViewReady = { dialogView = it },
                )
            }
        }

        compose.runOnIdle {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, dialogView?.importantForAutofill)
        }
    }

    @Test
    fun structuredNetworkErrorIsClearAndDoesNotEchoExceptionText() {
        val marker = "TOP_SECRET_MARKER"
        val error = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.CONNECT,
            UnknownHostException("192.0.2.10\naccount\n$marker"),
        )
        compose.setContent { MaterialTheme { NetworkError(error) } }

        compose.onNodeWithText("Invalid server address").fetchSemanticsNode()
        compose.onNodeWithText("Diagnostic code: NET-DNS").fetchSemanticsNode()
        compose.onAllNodesWithText(marker, substring = true).assertCountEquals(0)
    }

    private fun clickFolderMenuItem(text: String) {
        compose.onNodeWithContentDescription("Folder actions").performClick()
        compose.onNodeWithText(text).performClick()
    }

    private fun profile() = NetworkProfile(
        id = "profile",
        name = "Test server",
        protocol = NetworkProtocol.FTP,
        host = "127.0.0.1",
        port = 2121,
        username = "tester",
        basePath = "/",
    )

    private fun localEntry(name: String, kind: EntryKind) = FileEntry(
        absolutePath = "/local/$name",
        name = name.substringAfterLast('/'),
        kind = kind,
        sizeBytes = 12,
        modifiedAtMillis = 1,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
