package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.transfer.NearbyGroupController
import com.affilemanager.app.transfer.NearbyGroupInvite
import com.affilemanager.app.transfer.NearbyGroupMember
import com.affilemanager.app.transfer.NearbyPairing
import com.affilemanager.app.ui.MainViewModel
import org.junit.Rule
import org.junit.Test

class NearbyGroupManageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun organizerCanReachDeviceManagementAndRemovalConfirmation() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val organizer = NearbyPairing.create("192.168.1.10", 30010, "11111111", "Organizer")
        val member = NearbyPairing.create("192.168.1.11", 30011, "22222222", "Peer phone")
        val invite = NearbyGroupInvite(organizer, "Home")
        try {
            NearbyGroupController.host(invite)
            NearbyGroupController.hostMembers(invite, listOf(NearbyGroupMember(organizer, true), NearbyGroupMember(member)))
            val vm = MainViewModel(app).also { store.put("group-manage-test", it) }
            compose.setContent {
                MaterialTheme {
                    NearbyPhoneTransferCard(
                        viewModel = vm,
                        receiveDirectory = app.cacheDir.path,
                        lanState = LanTransferState(status = LanTransferStatus.RUNNING, groupMode = true),
                        receiverName = "Organizer", onReceiverNameChange = {},
                        durationMinutes = 15, onDurationMinutesChange = {},
                    )
                }
            }
            compose.onNodeWithText("Group (2)").performClick()
            compose.onNodeWithTag("nearby_group_manage_devices").performScrollTo().performClick()
            compose.onNodeWithTag("nearby_group_devices_dialog").assertIsDisplayed()
            compose.onNode(hasText("Peer phone") and hasAnyAncestor(hasTestTag("nearby_group_devices_dialog")))
                .assertIsDisplayed()
            compose.onNodeWithTag("nearby_group_remove_192.168.1.11").performClick()
            compose.onNodeWithText("Remove from group?").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            NearbyGroupController.leave()
        }
    }
}
