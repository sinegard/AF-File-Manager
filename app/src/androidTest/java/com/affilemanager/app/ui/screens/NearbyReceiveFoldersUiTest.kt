package com.affilemanager.app.ui.screens

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.NearbyReceiveFolderRules
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NearbyReceiveFoldersUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewCancelAndCreateUseTheSelectedFolderAndPersistAllSixRoutes() = withFixture { app, vm, root ->
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                NearbyReceiveSettings(vm, true)
            }
        } }
        compose.onNodeWithTag("nearby_create_type_folders").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(setOf("untouched.txt"), root.list()!!.toSet())
        assertTrue(vm.shareScreenPreferences.value.nearbyTypePaths.isEmpty())
        compose.onNodeWithTag("nearby_create_type_folders").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_confirm_type_folders").performClick()
        compose.waitUntil(10_000) { vm.shareScreenPreferences.value.nearbyTypePaths.size == 6 }
        val expected = NearbyReceiveFolderRules.destinations(root.path).mapValues { it.value.canonicalPath }
        assertEquals(expected, vm.shareScreenPreferences.value.nearbyTypePaths)
        compose.waitUntil(10_000) {
            app.graph.uiPreferences.loadShare(root.path, "Phone").nearbyTypePaths == expected
        }
        assertTrue(expected.values.all { File(it).isDirectory })
        assertEquals("original bytes", File(root, "untouched.txt").readText())
        assertTrue(runBlocking { vm.createNearbyReceiveTypeFolders() }.isSuccess)
    }

    @Test fun anExistingFileBlocksTheWholePreflightWithoutChangingRoutingOrOtherNames() = withFixture { _, vm, root ->
        File(root, "Videos").writeText("existing video-name file")
        try {
            assertTrue(runBlocking { vm.createNearbyReceiveTypeFolders() }.isFailure)
            assertFalse(File(root, "Images").exists())
            assertTrue(vm.shareScreenPreferences.value.nearbyTypePaths.isEmpty())
            assertEquals("existing video-name file", File(root, "Videos").readText())
        } finally { check(File(root, "Videos").delete()) }
    }

    @Test fun aDestinationAliasIsRejectedByTheSamePreflightWithoutFollowingIt() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        // Emulated public storage does not support symlinks. The exact shared
        // preflight is exercised on this owned internal fixture instead.
        val root = File(app.cacheDir, "type-folder-alias-${System.nanoTime()}").apply { check(mkdir()) }
        val other = File(root, "not-selected").apply { check(mkdir()) }
        val alias = File(root, "Images")
        File(other, "marker.txt").writeText("not selected")
        try {
            Files.createSymbolicLink(alias.toPath(), other.toPath())
            assertTrue(runCatching { NearbyReceiveFolderRules.preflight(root) }.isFailure)
            assertEquals(setOf("marker.txt"), other.list()!!.toSet())
            assertEquals("not selected", File(other, "marker.txt").readText())
        } finally {
            Files.deleteIfExists(alias.toPath())
            check(File(other, "marker.txt").delete()); check(other.delete()); check(root.delete())
        }
    }

    private fun withFixture(body: (AFFileManagerApplication, MainViewModel, File) -> Unit) {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AF-type-folders-${System.nanoTime()}")
        check(root.mkdirs())
        File(root, "untouched.txt").writeText("original bytes")
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("receive-folders", it) }
        val original = vm.shareScreenPreferences.value
        vm.updateShareScreenPreferences { it.copy(nearbyReceivePath = root.canonicalPath, nearbyTypePaths = emptyMap()) }
        try { body(app, vm, root) } finally {
            vm.updateShareScreenPreferences { original }
            compose.waitUntil(10_000) {
                app.graph.uiPreferences.loadShare(original.sharedPath, original.receiverName).nearbyReceivePath == original.nearbyReceivePath
            }
            store.clear()
            NearbyReceiveFolderRules.destinations(root.path).values.forEach { if (it.isDirectory && !Files.isSymbolicLink(it.toPath())) check(it.delete()) }
            check(File(root, "untouched.txt").delete()); check(root.delete())
        }
    }
}
