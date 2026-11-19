package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.DocumentWorkflowRuntimeVerifier
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class NearbyReceiveLocationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseAnOwnedVirtualVolumeThroughTheActualAfAndSystemFolderPickers() {
        check(Build.MODEL.contains("sdk", true))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val selectedVolume = app.getSystemService(StorageManager::class.java).storageVolumes.singleOrNull {
            it.isRemovable && !it.isPrimary && it.getDescription(app).contains("Virtual", true)
        }
        org.junit.Assume.assumeNotNull("Owned virtual public-volume fixture required", selectedVolume)
        val volume = requireNotNull(selectedVolume)
        val child = if (Build.VERSION.SDK_INT >= 30) "AFNearbyGrant-${System.nanoTime()}" else null
        if (child != null) check(File(requireNotNull(volume.directory), child).mkdir())
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("test", it) }
        val before = vm.shareScreenPreferences.value
        val language = mutableStateOf("en")
        try {
            compose.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language.value))) }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, 1.5f),
                    LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            val state by LanTransferController.state.collectAsState()
                            NearbyReceiveLocation(vm, state)
                        }
                    }
                }
            }
            compose.onNodeWithTag("nearby_create_type_folders").assertDoesNotExist()
            compose.onNodeWithTag("nearby_receive_path").performClick()
            compose.onNodeWithTag("nearby_choose_receive_path").performClick()
            compose.onNodeWithTag("share_folder_more").performClick()
            compose.onNodeWithTag("share_folder_android_picker").performClick()
            DocumentWorkflowRuntimeVerifier.chooseOwnedVolumeInSystemPicker(InstrumentationRegistry.getInstrumentation(), volume.getDescription(app), child)
            compose.waitUntil(20_000) { !vm.nearbyReceiveFolderChanging.value && vm.shareScreenPreferences.value.nearbyReceivePath.startsWith("content://") }
            assertNull(vm.nearbyReceiveFolderError.value)
            val uri = android.net.Uri.parse(vm.shareScreenPreferences.value.nearbyReceivePath)
            assertEquals(volume.uuid + ":" + child.orEmpty(), android.provider.DocumentsContract.getTreeDocumentId(uri))
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
            val existingNames = runBlocking { app.graph.safFiles.list(uri.toString()).getOrThrow() }.map { it.name }.toSet()
            compose.onNodeWithTag("nearby_receive_type_toggle").performClick()
            compose.onNodeWithTag("nearby_create_type_folders").performScrollTo().performClick()
            compose.onNodeWithTag("nearby_confirm_type_folders").performClick()
            compose.waitUntil(15_000) { vm.shareScreenPreferences.value.nearbyTypePaths.size == 6 }
            assertTrue(vm.shareScreenPreferences.value.nearbyTypePaths.values.all { it.startsWith("content://") })
            assertEquals(existingNames + com.affilemanager.app.transfer.NearbyReceiveFolderRules.names.values,
                runBlocking { app.graph.safFiles.list(uri.toString()).getOrThrow() }.map { it.name }.toSet())
            compose.onNodeWithTag("nearby_confirm_type_folders").assertDoesNotExist()
            val documents = vm.shareScreenPreferences.value.nearbyTypePaths.getValue(com.affilemanager.app.model.EntryKind.DOCUMENT)
            compose.onNodeWithTag("receive_path_IMAGE").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                val node = compose.onAllNodesWithTag("share_folder_select").fetchSemanticsNodes().singleOrNull()
                node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("share_folder_up").performClick()
            compose.waitUntil(10_000) {
                val node = compose.onAllNodesWithTag("share_folder_select").fetchSemanticsNodes().singleOrNull()
                node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("share_folder_picker").performScrollToNode(hasTestTag("share_folder_$documents"))
            compose.onNodeWithTag("share_folder_$documents").performClick()
            compose.waitUntil(10_000) {
                val node = compose.onAllNodesWithTag("share_folder_select").fetchSemanticsNodes().singleOrNull()
                node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("share_folder_select").assertIsEnabled()
            compose.onNodeWithTag("share_folder_select").performClick()
            compose.waitUntil(10_000) { !vm.nearbyReceiveFolderChanging.value &&
                vm.shareScreenPreferences.value.nearbyTypePaths[com.affilemanager.app.model.EntryKind.IMAGE] == documents }
            assertNull(vm.nearbyReceiveFolderError.value)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
            for (locale in listOf("en", "lt", "ar")) {
                compose.runOnIdle { language.value = locale }
                val name = runBlocking { vm.nearbyReceivePathLabel(vm.shareScreenPreferences.value.nearbyReceivePath) }
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nearby_receive_path") and hasText(name, substring = true)).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("nearby_receive_path").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("nearby_receive_path").assertTextContains(name, substring = true)
                compose.onNodeWithTag("nearby_receive_type_toggle").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try { File(uiAuditDirectory(app), "nearby-receive-location-$locale.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            File(uiAuditDirectory(app), "owned-document-fixture.json").writeText(org.json.JSONObject().put("uri", uri.toString())
                .put("volume", volume.uuid).put("child", child).put("retention", "One owned disposable guest stage; keep this OS grant for subsequent source/receiver tests, then remove guest disks.").toString())
        } catch (failure: Throwable) {
            File(uiAuditDirectory(app), "receive-location-failure.json").writeText(org.json.JSONObject()
                .put("error", vm.nearbyReceiveFolderError.value).put("changing", vm.nearbyReceiveFolderChanging.value)
                .put("path", vm.shareScreenPreferences.value.nearbyReceivePath)
                .put("types", org.json.JSONObject(vm.shareScreenPreferences.value.nearbyTypePaths.mapKeys { it.key.name }))
                .put("ui", runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("No Compose root"))
                .toString())
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) try { File(uiAuditDirectory(app), "receive-location-failure.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            throw failure
        } finally {
            vm.updateShareScreenPreferences { before }
            compose.waitUntil(10_000) { app.graph.uiPreferences.loadShare(before.sharedPath, before.receiverName).let {
                it.nearbyReceivePath == before.nearbyReceivePath && it.nearbyTypePaths == before.nearbyTypePaths
            } }
            store.clear()
        }
    }
}
