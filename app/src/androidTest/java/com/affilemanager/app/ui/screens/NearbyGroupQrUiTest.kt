package com.affilemanager.app.ui.screens

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.*
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

class NearbyGroupQrUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRealGroupControlsToggleADecodableQrCopyItsCodeAndStopOnlyWhenAsked() {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("group-qr", it) }
        lateinit var clipboard: ClipboardManager
        var priorClip: android.content.ClipData? = null
        compose.runOnUiThread {
            clipboard = app.getSystemService(ClipboardManager::class.java)
            priorClip = clipboard.primaryClip
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val accessibility = automation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = accessibility
        val address = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).take(32)
            .flatMap { java.util.Collections.list(it.inetAddresses).take(16) }
            .first { it is java.net.Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
        val pairing = NearbyPairing.create(requireNotNull(address.hostAddress), 23456, "01234567", "Fixture phone")
        val invite = NearbyGroupInvite(pairing, "Fixture group")
        val capture = File(app.cacheDir, "group-qr-${System.nanoTime()}.png")
        try {
            NearbyGroupController.leave()
            NearbyGroupController.host(invite)
            compose.setContent { MaterialTheme {
                NearbyPhoneTransferCard(vm, app.cacheDir.path,
                    LanTransferState(status = LanTransferStatus.RUNNING, protocol = LanTransferProtocol.WEB, groupMode = true),
                    receiverName = "Fixture phone", onReceiverNameChange = {}, durationMinutes = 0, onDurationMinutesChange = {})
            } }
            compose.onNodeWithTag("nearby_group_button").performClick()
            compose.onNodeWithTag("nearby_group_qr").assertDoesNotExist()
            compose.onNodeWithTag("nearby_group_add_phone").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nearby_group_qr").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nearby_group_qr").performScrollTo().assertIsDisplayed()
            automation.waitForIdle(250, 3000)
            val bounds = findQrBounds(requireNotNull(automation.rootInActiveWindow))
            val screen = requireNotNull(automation.takeScreenshot())
            try {
                require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.width && bounds.bottom <= screen.height)
                val qr = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try { capture.outputStream().use { check(qr.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { qr.recycle() }
            } finally { screen.recycle() }
            assertTrue("The displayed QR no longer contains the group invitation", NearbyQrCode.decode(capture) == invite.encoded())
            compose.onNodeWithTag("nearby_group_copy").performScrollTo().performClick()
            var copied: String? = null
            compose.runOnUiThread { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            assertEquals(invite.encoded(), copied)
            assertEquals(NearbyGroupStatus.HOSTING, NearbyGroupController.state.value.status)
            compose.onNodeWithTag("nearby_group_add_phone").performScrollTo().performClick()
            compose.onNodeWithTag("nearby_group_qr").assertDoesNotExist()
            assertEquals(NearbyGroupStatus.HOSTING, NearbyGroupController.state.value.status)
            compose.onNodeWithTag("nearby_group_leave").performScrollTo().performClick()
            compose.waitUntil(5000) { NearbyGroupController.state.value.status != NearbyGroupStatus.HOSTING }
        } finally {
            if (capture.exists()) check(capture.delete())
            compose.runOnUiThread {
                priorClip?.let(clipboard::setPrimaryClip)
                    ?: if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
            NearbyGroupController.leave()
            store.clear()
        }
    }

    private fun findQrBounds(root: AccessibilityNodeInfo): Rect {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty()) {
                check(++visited <= 2048) { "Unexpectedly large group window" }
                val node = queue.removeFirst()
                try {
                    if (node.contentDescription?.toString() == "Group QR code") return Rect().also { node.getBoundsInScreen(it) }
                    for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
                } finally { node.recycle() }
            }
        } finally { queue.forEach { it.recycle() } }
        error("The visible group QR did not have accessible bounds")
    }
}
