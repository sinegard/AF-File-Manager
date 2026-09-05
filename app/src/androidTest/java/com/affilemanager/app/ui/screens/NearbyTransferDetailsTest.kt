package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            compose.onNodeWithText("Files").performClick()
            compose.onNodeWithTag("nearby_transfer_details").assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() } }
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
