package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.FileTagSnapshot
import com.affilemanager.app.data.HomeCustomization
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.NetworkProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudLocationsUiTest {
    @get:Rule val compose = createComposeRule()
    private val cloud = NetworkProfile("cloud-fixture", "Nextcloud fixture", NetworkProtocol.WEBDAV,
        "cloud.example.test", 443, "fixture", "/remote.php/dav/files/fixture", provider = NetworkProvider.NEXTCLOUD)

    @Test fun nextcloudAppearsInCloudAndCanBeOpenedEditedOrRemoved() {
        var opened: NetworkProfile? = null
        var edited: NetworkProfile? = null
        var removed: String? = null
        compose.setContent { MaterialTheme {
            FilesHome(
                roots = emptyList(), safLocations = emptyList(), recentFiles = emptyList(),
                recentAddedFiles = emptyList(), recentOpenedFiles = emptyList(), recentFilesLoading = false,
                recentFilesError = null, storageDisplaySettings = DirectoryDisplaySettings(),
                quickLocationsDisplaySettings = DirectoryDisplaySettings(), customization = HomeCustomization(),
                favorites = emptyList(), tagSnapshot = FileTagSnapshot(), trashCount = 0, rootStorageAvailable = false,
                onOpen = {}, onOpenStorage = {}, onOpenRoot = {}, onOpenRecent = {}, onRenameRecent = {},
                onShareRecent = {}, onTrashRecent = {}, onRevealRecent = {}, onCopyRecent = { _, _ -> },
                onOpenTrash = {}, onOpenFavoritesPage = {}, onOpenTagsPage = {}, onOpenPlans = {}, onOpenCleanup = {},
                onRefreshRecent = {}, onToggleLayout = {}, onConfigureLayout = {}, onAddSafLocation = {}, onAddNextcloud = {},
                onOpenSafLocation = {}, onRenameSafLocation = { _, _ -> }, onRemoveSafLocation = {},
                onOpenSystemFiles = {}, onCustomizeHome = {}, cloudProfiles = listOf(cloud),
                onOpenCloudProfile = { opened = it }, onEditCloudProfile = { edited = it }, onRemoveCloudProfile = { removed = it },
            )
        } }
        fun showCloud() {
            compose.onNodeWithTag("home_tool_cloud").performScrollTo().performClick()
            compose.onNodeWithTag("cloud_locations_dialog").assertIsDisplayed()
            compose.onNodeWithText(cloud.name).assertIsDisplayed()
        }
        showCloud()
        compose.onNodeWithTag("profile_connect_${cloud.id}").performClick()
        compose.runOnIdle { assertEquals(cloud, opened) }
        showCloud()
        compose.onNodeWithTag("profile_edit_${cloud.id}").performClick()
        compose.runOnIdle { assertEquals(cloud, edited) }
        showCloud()
        compose.onNodeWithTag("profile_remove_${cloud.id}").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(cloud.id, removed) }
    }

    @Test fun longConnectionNameDoesNotPushItsActionsOutsideANarrowCard() {
        val name = mutableStateOf("A long Nextcloud connection name that must leave room for its actions")
        var edits = 0
        var removals = 0
        var connects = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(280.dp)) {
            ProfileCard(cloud.copy(name = name.value), false, { connects++ }, { edits++ }, { removals++ })
        } } }
        compose.onNodeWithTag("profile_edit_${cloud.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("profile_remove_${cloud.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("profile_connect_${cloud.id}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, removals); assertEquals(1, connects) }
    }
}
