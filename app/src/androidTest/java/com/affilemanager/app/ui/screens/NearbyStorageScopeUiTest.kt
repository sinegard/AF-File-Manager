package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NearbyStorageScopeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsingAnotherFolderAddsItsSelectionToTheExistingBasketWithoutDuplicates() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val first = File(app.cacheDir, "basket-existing-${System.nanoTime()}.txt")
        val second = File(app.cacheDir, "basket-additional-${System.nanoTime()}.txt")
        val store = ViewModelStore()
        first.writeText("original first")
        second.writeText("original second")
        try {
            val vm = MainViewModel(app).also { store.put("basket", it) }
            val existing = app.graph.localFiles.toEntry(first)
            val prepared = vm.prepareNearbyTransferPaths(listOf(second.path, first.path), listOf(existing)).getOrThrow()
            assertEquals(setOf(first.canonicalPath, second.canonicalPath), prepared.paths.map { File(it).canonicalPath }.toSet())
            assertEquals(2, prepared.paths.size)
            assertEquals("original first", first.readText())
            assertEquals("original second", second.readText())
            vm.discardNearbyTransferSources(prepared)
        } finally { store.clear(); check(first.delete()); check(second.delete()) }
    }

    @Test fun storageAndFolderFiltersKeepTheBasketAndUseTheExistingFolderBrowser() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "af-scope-ui-${System.nanoTime()}")
        check(folder.mkdirs())
        val file = File(folder, "scope-image.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var indexed: android.net.Uri? = null
        val store = ViewModelStore()
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val scanned = CountDownLatch(1)
            MediaScannerConnection.scanFile(app, arrayOf(file.path), arrayOf("image/png")) { _, uri -> indexed = uri; scanned.countDown() }
            assertTrue(scanned.await(15, TimeUnit.SECONDS))
            assertNotNull(indexed)
            val vm = MainViewModel(app).also { store.put("scope", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Nuotraukos").performClick()
            waitTag("nearby_entry_${file.path}")
            compose.onNodeWithTag("nearby_entry_${file.path}").performClick()
            compose.onNodeWithText("Next (1)").assertIsEnabled()
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val accessibility = automation.serviceInfo
            accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = accessibility
            compose.onNodeWithTag("nearby_open_storage").performScrollTo().performTouchInput { longClick() }
            compose.waitUntil(10_000) { automation.windows.any { it.root?.packageName?.toString()?.endsWith(".documentsui") == true } }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitTag("nearby_storage_scope")
            compose.onNodeWithText("Next (1)").assertIsEnabled()
            choose("FILES_USB")
            waitText("No matching files were found in this category")
            compose.onNodeWithText("Next (1)").assertIsEnabled()
            choose("FILES_INTERNAL")
            waitTag("nearby_entry_${file.path}")
            choose("FOLDERS_INTERNAL")
            waitTag("nearby_entry_${folder.path}")
            capture(app, "nearby-folder-scope.png")
            compose.onNodeWithTag("nearby_entry_${folder.path}").performClick()
            waitTag("local_upload_dialog")
            compose.onNodeWithText(file.name).assertIsDisplayed()
            // Cancel only the folder browser: the selected send basket survives.
            compose.onNodeWithText("Cancel").performClick()
            waitTag("nearby_storage_scope")
            compose.onNodeWithText("Next (1)").assertIsEnabled()
            choose("FILES_ALL")
            waitTag("nearby_entry_${file.path}")
            compose.onNodeWithText("Next (1)").performClick()
            waitText("Ready to send: 1")
        } finally {
            compose.runOnUiThread { store.clear() }
            bitmap.recycle()
            indexed?.let { app.contentResolver.delete(it, null, null) }
            if (file.exists()) check(file.delete())
            check(folder.delete())
        }
    }

    private fun choose(choice: String) {
        compose.onNodeWithTag("nearby_storage_scope").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_scope_$choice").performScrollTo().performClick()
    }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(app: AFFileManagerApplication, name: String) {
        val image = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(uiAuditDirectory(app), name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
}
