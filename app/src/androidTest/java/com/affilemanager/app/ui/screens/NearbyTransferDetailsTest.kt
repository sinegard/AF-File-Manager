package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.TransferFileProgress
import com.affilemanager.app.transfer.TransferFileStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyTransferDetailsTest {
    @get:Rule val compose = createComposeRule()

    @org.junit.Before fun isolateUnpairedFixture() {
        check(android.os.Build.MODEL.contains("sdk"))
        com.affilemanager.app.transfer.NearbyTransferController.connection.clear()
        com.affilemanager.app.transfer.NearbyTransferController.clearFinished()
    }

    @Test fun receivingShowsDetailsOnceAndTheOriginalQrControlsRemainReachable() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val state = mutableStateOf(com.affilemanager.app.transfer.LanTransferState())
        try {
            val vm = com.affilemanager.app.ui.MainViewModel(app).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbyPhoneTransferCard(vm, app.cacheDir.path, state.value,
                receiverName = "Test receiver", onReceiverNameChange = {}) } }
            compose.onNodeWithText("Receive").performClick()
            compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
            compose.runOnIdle {
                state.value = com.affilemanager.app.transfer.LanTransferState(
                    status = com.affilemanager.app.transfer.LanTransferStatus.RUNNING,
                    url = "http://10.0.2.15:8080/", code = "12345678",
                    incomingUpload = com.affilemanager.app.transfer.LanUploadProgress("", 0, 1, 0, 0, 0, 10,
                        files = listOf(TransferFileProgress("pending.txt", 10))),
                )
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nearby_transfer_details").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Stop receiving").assertIsEnabled()
            compose.onAllNodesWithText("Close").onFirst().performClick()
            compose.onNodeWithText("Receive").performClick()
            compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nearby_receive_qr").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag("nearby_receive_dialog"))).performClick()
            compose.onNodeWithTag("nearby_transfer_details").assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun pairedDetailsCombineBothDirectionsAndCloseNeverDisconnects() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val peer = com.affilemanager.app.transfer.NearbyPairing("192.168.1.10", 8080, "12345678", "Other phone")
        val controller = com.affilemanager.app.transfer.NearbyTransferController
        try {
            controller.connection.remember(peer)
            controller.publish(com.affilemanager.app.transfer.NearbyTransferState(
                status = com.affilemanager.app.transfer.NearbyTransferStatus.RUNNING,
                files = listOf(TransferFileProgress("sending.txt", 20))))
            val vm = com.affilemanager.app.ui.MainViewModel(app).also { store.put("paired-test", it) }
            val lan = com.affilemanager.app.transfer.LanTransferState(status = com.affilemanager.app.transfer.LanTransferStatus.RUNNING,
                incomingUpload = com.affilemanager.app.transfer.LanUploadProgress("", 0, 1, 0, 0, 0, 30,
                    files = listOf(TransferFileProgress("receiving.txt", 30))))
            compose.setContent { com.affilemanager.app.ui.theme.AFFileManagerTheme {
                NearbyPhoneTransferCard(vm, app.cacheDir.path, lan, receiverName = "Test", onReceiverNameChange = {})
            } }
            compose.onNodeWithText("Receive").assertDoesNotExist()
            compose.onNodeWithText("Send").assertDoesNotExist()
            compose.onNodeWithTag("nearby_send_details").performClick()
            compose.onNode(hasTestTag("nearby_transfer_file_0") and hasAnyDescendant(hasText("sending.txt"))).assertIsDisplayed()
            compose.onNode(hasTestTag("nearby_transfer_file_1") and hasAnyDescendant(hasText("receiving.txt"))).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close").performClick()
            assertEquals(peer, controller.connectedPairing())
            compose.onNodeWithTag("nearby_send_details").performClick()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("nearby_disconnect").performClick()
            compose.onNodeWithTag("nearby_confirm_disconnect").assertIsDisplayed()
            compose.onAllNodesWithText("Cancel").onFirst().performClick()
            assertEquals(peer, controller.connectedPairing())
            compose.onNodeWithTag("nearby_send_details").performClick()
            compose.runOnIdle { controller.connection.clear() }
            compose.onNodeWithTag("nearby_transfer_details").assertDoesNotExist()
            // Session loss dismisses the modal, not the evidence of finished/pending files.
            compose.onNodeWithTag("nearby_send_details").performClick()
            compose.onNode(hasTestTag("nearby_transfer_file_0") and hasAnyDescendant(hasText("sending.txt"))).assertIsDisplayed()
            compose.onNode(hasTestTag("nearby_transfer_file_1") and hasAnyDescendant(hasText("receiving.txt"))).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            controller.connection.clear(); controller.publish(com.affilemanager.app.transfer.NearbyTransferState())
        }
    }

    @Test fun pairedScreenHidesOtherProtocolSetupButKeepsSessionActions() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val controller = com.affilemanager.app.transfer.NearbyTransferController
        try {
            controller.connection.remember(com.affilemanager.app.transfer.NearbyPairing("192.168.1.10", 8080, "12345678", "Other phone"))
            val vm = com.affilemanager.app.ui.MainViewModel(app).also { store.put("paired-screen", it) }
            compose.setContent { com.affilemanager.app.ui.theme.AFFileManagerTheme {
                SharingScreen(vm, androidx.compose.foundation.layout.PaddingValues())
            } }
            compose.onNodeWithText("FTP").assertDoesNotExist()
            compose.onNodeWithText("WebDAV").assertDoesNotExist()
            compose.onNodeWithText("Receive").assertDoesNotExist()
            compose.onNodeWithTag("nearby_add_files").assertIsEnabled()
            compose.onNodeWithTag("nearby_disconnect").assertIsDisplayed()
            compose.onNodeWithTag("nearby_send_details").assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() }; controller.connection.clear() }
    }

    @Test fun pairedDetailsShowParticipantCapsulesAndSendMessages() {
        var sent: String? = null
        val messages = listOf(
            com.affilemanager.app.transfer.NearbyChatMessage("1", "Alice phone", "Already sent", true, 1),
            com.affilemanager.app.transfer.NearbyChatMessage("2", "Bob phone", "Received reply", false, 2),
        )
        compose.setContent { com.affilemanager.app.ui.theme.AFFileManagerTheme {
            NearbyTransferDetails(
                files = emptyList(), transferredBytes = 0, totalBytes = 0, totalFiles = 0,
                onPreview = {}, onDismiss = {}, localName = "Alice phone", peerName = "Bob phone",
                chatMessages = messages, onSendMessage = { sent = it },
            )
        } }
        compose.onNodeWithText("Alice phone").assertIsDisplayed()
        compose.onNodeWithText("Bob phone").assertIsDisplayed()
        compose.onNodeWithText("Already sent").assertIsDisplayed()
        compose.onNodeWithText("Received reply").assertIsDisplayed()
        compose.onNodeWithTag("nearby_chat_input").performTextInput("New message")
        compose.onNodeWithTag("nearby_chat_send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("New message", sent) }
    }

    @Test fun onlyCompleteReceivedFilesOfferPreviewAndClosingDoesNotCancel() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val photo = File(app.cacheDir, "transfer-preview-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff008577.toInt()) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var opened: String? = null
        var closed = 0
        var cancelled = 0
        val details = mutableStateOf(listOf(
            TransferFileProgress("Pictures/ready.png", photo.length(), photo.length(), TransferFileStatus.COMPLETED, photo.path),
            TransferFileProgress("Pictures/still-receiving.png", 4096, 2048, TransferFileStatus.TRANSFERRING),
            TransferFileProgress("Documents/this-is-a-long-document-name-that-must-stay-inside-the-row.pdf", 8192),
        ))
        try {
            compose.setContent { MaterialTheme { NearbyTransferDetails(details.value, photo.length() + 2048,
                photo.length() + 12288, 3, { opened = it.absolutePath }, { closed++ }, { cancelled++ }) } }
            compose.onNodeWithTag("nearby_transfer_preview_0").assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(photo.path, opened) }
            compose.onNodeWithTag("nearby_transfer_preview_1").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_transfer_preview_2").assertIsNotEnabled()
            val evidence = requireNotNull(app.getExternalFilesDir("validation"))
            compose.onNodeWithTag("nearby_transfer_details").captureToImage().asAndroidBitmap().let {
                File(evidence, "nearby-details-${app.resources.displayMetrics.widthPixels}.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            compose.onAllNodesWithText("Close").onFirst().performClick()
            compose.runOnIdle { assertEquals(1, closed); assertEquals(0, cancelled) }
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { assertEquals(1, cancelled) }
        } finally { photo.delete() }
    }
}
