package com.affilemanager.app.ui.screens

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
                    onChooseUpload = {},
                    onCreateFolder = {},
                    onRename = {},
                    onDelete = {},
                    onSync = {},
                )
            }
        }

        compose.onNodeWithTag("remote_upload_choose").assertIsEnabled().assertHasClickAction()
        compose.onAllNodesWithContentDescription("Kopijuoti į aktyvų vietinį aplanką")
            .assertCountEquals(2)[0]
            .assertHasClickAction()
        compose.onNodeWithText("Iš serverio → /local/target").fetchSemanticsNode()
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
        compose.onNodeWithText("Kopijuoti (1)").performClick()

        compose.runOnIdle { assertEquals(listOf(folder.absolutePath), copied) }
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
        compose.onNodeWithText("Neteisingi jungties duomenys", substring = true).fetchSemanticsNode()
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
        compose.onNodeWithText("Išsaugoti").assertIsEnabled()
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
        compose.onNodeWithText("Išsaugoti").assertIsEnabled().performClick()

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

        compose.onNodeWithText("Serverio adresas neteisingas").fetchSemanticsNode()
        compose.onNodeWithText("Diagnostikos kodas: NET-DNS").fetchSemanticsNode()
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
