package com.affilemanager.app

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyWindowRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun receiverWindowSurvivesActivityRecreationWithoutPersistingPairingSecrets() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.setSection(AppSection.SHARE) }
        compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
        compose.onNodeWithTag("nearby_receive_close").performClick()
    }

    @Test fun sendPairingAndHistoryAreReachableInTheMainApp() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.setSection(AppSection.SHARE) }

        val mainTags = listOf("nearby_main_send", "nearby_main_receive", "nearby_group_button", "nearby_send_details", "nearby_diagnostics")
        mainTags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        assertTrue(bottom("nearby_main_send") <= minOf(top("nearby_main_receive"), top("nearby_group_button")))
        assertTrue(maxOf(bottom("nearby_main_receive"), bottom("nearby_group_button")) <= top("nearby_send_details"))
        assertTrue(bottom("nearby_send_details") <= top("nearby_diagnostics"))
        capture("nearby-main-actions")
        compose.onNodeWithTag("nearby_send_details").performClick()
        compose.onNodeWithTag("nearby_history_dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()

        compose.onNodeWithTag("nearby_main_send").performClick()
        compose.onNodeWithTag("nearby_continue").performClick()
        assertOpeningDoesNotShowKeyboard("nearby_pairing_input")
        assertTrue(bottom("nearby_pairing_input") <= top("nearby_scan_qr"))
        assertTrue(bottom("nearby_scan_qr") <= minOf(top("nearby_find_devices"), top("nearby_join_wifi_direct")))
        if (kotlin.math.abs(top("nearby_find_devices") - top("nearby_join_wifi_direct")) <= 1f) {
            assertEquals(bottom("nearby_find_devices"), bottom("nearby_join_wifi_direct"), 1f)
        } else {
            assertTrue("Stacked choices should not overlap", bottom("nearby_find_devices") <= top("nearby_join_wifi_direct"))
        }
        listOf("nearby_pairing_input", "nearby_scan_qr", "nearby_find_devices", "nearby_join_wifi_direct")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        assertEquals(LanTransferStatus.STOPPED, LanTransferController.state.value.status)
        capture("nearby-send-pairing")
    }

    @Test fun receiveLayoutKeepsCopyAndStopTogetherAndCloseDoesNotStopTheService() {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.setSection(AppSection.SHARE) }
        assertEquals(LanTransferStatus.STOPPED, LanTransferController.state.value.status)
        try {
            compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
            assertOpeningDoesNotShowKeyboard("nearby_receiver_name")
            compose.onNodeWithTag("nearby_receive_network").performScrollTo().assertIsEnabled().assertIsDisplayed()
            capture("nearby-receive-setup")
            compose.onNodeWithTag("nearby_receive_network").performClick()
            compose.waitUntil(15_000) {
                val receiver = LanTransferController.state.value
                receiver.status == LanTransferStatus.RUNNING && receiver.phoneReceiver
            }
            val session = requireNotNull(LanTransferController.state.value.receiveSessionId)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nearby_receive_qr").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nearby_receive_copy").assertIsEnabled().assertIsDisplayed()
            compose.onNodeWithTag("nearby_receive_stop").assertIsEnabled().assertIsDisplayed()
            assertEquals(top("nearby_receive_copy"), top("nearby_receive_stop"), 1f)
            assertEquals(bottom("nearby_receive_copy"), bottom("nearby_receive_stop"), 1f)
            assertTrue(bottom("nearby_receive_stop") <= top("nearby_receive_path"))
            assertTrue(bottom("nearby_receive_session_info") <= top("nearby_receive_qr"))
            compose.onNodeWithTag("nearby_receive_qr").performScrollTo().assertIsDisplayed()

            compose.onNodeWithTag("nearby_receive_close").performClick()
            compose.onNodeWithTag("nearby_receive_dialog").assertDoesNotExist()
            assertEquals(LanTransferStatus.RUNNING, LanTransferController.state.value.status)
            compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
            assertTrue(session == LanTransferController.state.value.receiveSessionId)
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("nearby_receive_dialog").assertDoesNotExist()
            assertEquals(LanTransferStatus.RUNNING, LanTransferController.state.value.status)
            compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
            assertTrue(session == LanTransferController.state.value.receiveSessionId)
            compose.onNodeWithTag("nearby_receive_stop").performClick()
            compose.waitUntil(10_000) { LanTransferController.state.value.status == LanTransferStatus.STOPPED }
            compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
            compose.onNodeWithTag("nearby_receive_qr").assertDoesNotExist()
        } finally {
            if (LanTransferController.state.value.status != LanTransferStatus.STOPPED) {
                compose.runOnUiThread { LanTransferController.stop(compose.activity) }
            }
        }
    }

    @Test fun groupNameOpensTheKeyboardOnlyWhenTouched() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.setSection(AppSection.SHARE) }
        compose.onNodeWithTag("nearby_group_button").performScrollTo().performClick()
        assertOpeningDoesNotShowKeyboard("nearby_group_name")
        compose.onNodeWithTag("nearby_group_name").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("nearby_group_name").assertIsFocused()
        compose.waitUntil(5_000) { keyboardVisible() }
        assertEquals(LanTransferStatus.STOPPED, LanTransferController.state.value.status)
    }

    // Order is a property of the scroll content, not just the clipped visible slice.
    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
    private fun bottom(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }

    private fun assertOpeningDoesNotShowKeyboard(fieldTag: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automation.waitForIdle(350, 5_000)
        compose.onNodeWithTag(fieldTag).assertIsNotFocused()
        assertTrue("Keyboard opened before editing", !keyboardVisible())
    }

    private fun keyboardVisible() = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        .any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun capture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(250, 5_000)
        val frame = requireNotNull(automation.takeScreenshot())
        try { File(uiAuditDirectory(compose.activity), "$name.png").outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { frame.recycle() }
    }
}
