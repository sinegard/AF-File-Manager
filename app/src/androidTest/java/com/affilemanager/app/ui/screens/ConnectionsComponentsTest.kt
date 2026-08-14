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

class ConnectionsComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectedBrowserExposesCopyActionsInBothDirectionsIncludingFolders() {
        val folder = RemoteEntry("remote-folder", "/remote-folder", true, 0, null)
        val file = RemoteEntry("remote.txt", "/remote.txt", false, 12, null)
        compose.setContent {
            MaterialTheme {
                RemoteBrowser(
                    state = NetworkUiState(
                        connectedProfile = profile(),
                        path = "/remote",
                        entries = listOf(folder, file),
                    ),
                    localDirectory = "/local/target",
                    onUp = {},
                    onRefresh = {},
                    onOpen = {},
                    onDownload = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onSelectAll = {},
                    onDownloadSelected = {},
                    onChooseUpload = {},
                    onCreateFolder = {},
                    onRename = {},
                    onDelete = {},
                    onSync = {},
                )
            }
        }

        compose.onNodeWithTag("remote_upload_choose").assertIsEnabled().assertHasClickAction()
        compose.onAllNodesWithContentDescription("Copy to active local folder")
            .assertCountEquals(2)[0]
            .assertHasClickAction()
        compose.onNodeWithText("From server → /local/target").fetchSemanticsNode()
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
                        onChooseUpload = {},
                        onCreateFolder = {},
                        onRename = {},
                        onDelete = {},
                        onSync = {},
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
        compose.onNodeWithContentDescription("Copy to active local folder").performClick()
        compose.runOnIdle { assertEquals(listOf(folder.path, file.path), copied) }
        compose.onNodeWithContentDescription("Deselect all").performClick()
        compose.onAllNodesWithText("Selected:", substring = true).assertCountEquals(0)
    }

    @Test
    fun localPickerReturnsSelectedFilesAndFolders() {
        var copied = emptyList<String>()
        val folder = localEntry("folder", EntryKind.DIRECTORY)
        val file = localEntry("file.txt", EntryKind.DOCUMENT)
        compose.setContent {
            MaterialTheme {
                LocalUploadDialog(
                    directoryPath = "/local",
                    remotePath = "/remote",
                    entries = listOf(folder, file),
                    initiallySelected = emptySet(),
                    onDismiss = {},
                    onCopy = { copied = it },
                )
            }
        }

        compose.onNodeWithText("folder").performClick()
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
                    directoryPath = "/local",
                    remotePath = "/remote",
                    entries = entries,
                    initiallySelected = setOf(entries.first().absolutePath),
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
        name = name,
        kind = kind,
        sizeBytes = 12,
        modifiedAtMillis = 1,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
