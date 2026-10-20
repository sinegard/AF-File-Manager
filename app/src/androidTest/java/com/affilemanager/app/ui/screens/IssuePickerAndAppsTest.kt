package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.FileCategoryPage
import com.affilemanager.app.data.InstalledAppScope
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.model.*
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class IssuePickerAndAppsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mediaCategoryWaitsForStorageAndBackDoesNotReopenThePreviousScope() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val directory = File(app.cacheDir, "scoped-picker-${UUID.randomUUID()}").apply { check(mkdir()) }
        val loads = AtomicInteger()
        var closed = false
        val root = StorageRoot("fixture", "Vidinė atmintis", directory.path, 100, 100, false, StorageRootKind.INTERNAL)
        try {
            compose.setContent { MaterialTheme {
                LocalUploadDialog("", "", emptyList(), emptySet(), { Result.success(emptyList()) }, { closed = true }, {},
                    storageRoots = listOf(root), loadCategory = { category, chosenRoot, offset, hidden ->
                        assertEquals(FileCategory.IMAGES, category); assertEquals(root, chosenRoot); assertEquals(0, offset); assertFalse(hidden)
                        loads.incrementAndGet(); Result.success(FileCategoryPage(emptyList(), 0, null, false))
                    })
            } }
            compose.onNodeWithTag("picker_category_IMAGES").performClick()
            compose.onNodeWithText("Select storage").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, loads.get()) }
            compose.onNodeWithTag("local_upload_entry_${root.path}").performClick()
            compose.waitUntil(5000) { loads.get() == 1 }
            pressBack()
            compose.onNodeWithText("Select storage").assertIsDisplayed()
            pressBack()
            compose.onAllNodesWithText("Select storage").assertCountEquals(0)
            compose.runOnIdle { assertFalse(closed); assertEquals(1, loads.get()) }
            pressBack()
            compose.runOnIdle { assertTrue(closed) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun installedAppClickShowsInformationWhileHoldStillSelectsAndFiltersStayInline() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val vm = MainViewModel(app).also { store.put("installed-app-issue", it) }
        try {
            compose.setContent { val state by vm.fileCategory.collectAsState(); MaterialTheme { FileCategoryBrowser(state, vm) } }
            compose.runOnUiThread { vm.openFileCategory(FileCategory.INSTALLED_APPS) }
            compose.waitUntil(15000) { !vm.fileCategory.value.loading && vm.fileCategory.value.entries.isNotEmpty() }
            compose.onNodeWithTag("installed_apps_filters").assertIsDisplayed()
            compose.onNodeWithTag("installed_apps_scope_ALL").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.fileCategory.value.appScope == InstalledAppScope.ALL && !vm.fileCategory.value.loading }
            compose.onNodeWithTag("installed_apps_scope_USER").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.fileCategory.value.appScope == InstalledAppScope.USER && !vm.fileCategory.value.loading }
            val entry = vm.fileCategory.value.entries.first()
            // Different installed packages can have the same human-facing label
            // (including release/debug AF). Select the exact displayed APK owner.
            val row = hasText(entry.name) and hasText(File(entry.absolutePath).parent.orEmpty(), substring = true)
            compose.onNode(row).performScrollTo().performClick()
            compose.onNodeWithTag("installed_app_information").assertIsDisplayed()
            compose.onAllNodesWithTag("confirm_apk_install").assertCountEquals(0)
            pressBack()
            compose.onNode(row).performScrollTo().performTouchInput { longClick() }
            compose.waitUntil(5000) { entry.absolutePath in vm.fileCategory.value.selectedPaths }
            compose.onNodeWithTag("installed_apps_export").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("installed_app_information").assertCountEquals(0)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun hiddenToggleChangesTheRealDirectoryListingWithoutChangingEitherFile() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "hidden-picker-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val hidden = root.resolve(".hidden.txt").apply { writeText("hidden fixture") }
        val visible = root.resolve("visible.txt").apply { writeText("visible fixture") }
        try {
            compose.setContent { MaterialTheme {
                LocalUploadDialog(root.path, "", emptyList(), emptySet(),
                    { path -> app.graph.localFiles.list(path, false, SortMode.NAME, SortDirection.ASCENDING) }, {}, {},
                    loadWithHidden = { path, includeHidden -> app.graph.localFiles.list(path, includeHidden, SortMode.NAME, SortDirection.ASCENDING) })
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("local_upload_entry_${visible.path}").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("local_upload_entry_${hidden.path}").assertCountEquals(0)
            compose.onNodeWithTag("local_upload_more").performClick()
            compose.onNodeWithTag("local_upload_hidden").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("local_upload_entry_${hidden.path}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("local_upload_entry_${hidden.path}").assertIsDisplayed()
            compose.onNodeWithTag("local_upload_more").performClick()
            compose.onNodeWithTag("local_upload_hidden").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("local_upload_entry_${hidden.path}").fetchSemanticsNodes().isEmpty() }
            assertEquals("hidden fixture", hidden.readText())
            assertEquals("visible fixture", visible.readText())
        } finally { root.deleteRecursively() }
    }

    private fun pressBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
    }
}
