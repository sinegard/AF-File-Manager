package com.affilemanager.app

import android.os.Build
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Requires an explicitly owned emulator with a newly created virtual public disk. */
class LegacyRemovableStorageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readOnlyDirectStorageOffersTheExistingSystemPermissionPath(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Legacy removable-volume fixture requires Android8–10", Build.VERSION.SDK_INT in 26..29)
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.refreshStorageRoots() }
        compose.waitUntil(10_000) { vm.roots.value.any { it.removable } }
        val root = vm.roots.value.single { it.removable }
        val directory = File(root.path)
        assertTrue(Process.myUid() >= 10_000)
        assertTrue(directory.canRead())
        assertFalse(directory.canWrite())
        val name = "AF-owned-denied-${System.nanoTime()}"
        val result = app.graph.localFiles.createDirectory(root.path, name)
        File(uiAuditDirectory(app), "legacy-removable-direct.json").writeText(JSONObject()
            .put("sdk", Build.VERSION.SDK_INT).put("uid", Process.myUid()).put("volume", root.id)
            .put("readable", directory.canRead()).put("writable", directory.canWrite())
            .put("rawCreateSucceeded", result.isSuccess).put("rootKind", root.kind.name).toString())
        if (result.isSuccess) {
            check(File(root.path,name).delete())
            fail("The selected fixture did not reproduce restricted removable storage")
        }
        assertFalse(File(root.path, name).exists())
        compose.runOnUiThread { vm.openStorageRoot(root) }
        val selected = vm.safLocations.value.firstOrNull { it.canWrite &&
            com.affilemanager.app.data.RemovableStorageAccess.belongsToVolume(it.uri, root.id, rootOnly = true) }
        if (selected != null) {
            compose.waitUntil(10_000) { vm.safBrowser.value.currentUri == selected.uri && !vm.safBrowser.value.loading }
            assertTrue(vm.safBrowser.value.location?.canWrite == true)
        } else {
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.path == root.path }
            compose.onNodeWithTag("removable_write_access").assertIsDisplayed()
        }
    }
}
