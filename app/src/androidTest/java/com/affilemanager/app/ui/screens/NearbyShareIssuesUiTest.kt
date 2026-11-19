package com.affilemanager.app.ui.screens

import android.content.ContentProviderOperation
import android.os.Build
import android.provider.ContactsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.InstalledAppScope
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyShareIssuesUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    @Test fun optionalSystemAppFilterLoadsRealSystemPackagesWithoutLosingTheBasket() {
        check(Build.MODEL.contains("sdk", true))
        val store = ViewModelStore()
        try {
            val vm = MainViewModel(app).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Programos").performScrollTo().performClick()
            val own = runBlocking { vm.loadNearbyTransferCategoryPage(FileCategory.INSTALLED_APPS, 0).getOrThrow() }
                .entries.first { it.packageName == app.packageName }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("nearby_entry_${own.absolutePath}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nearby_entry_${own.absolutePath}").performClick()
            compose.onNodeWithTag("nearby_picker_menu").performClick()
            compose.onNodeWithTag("nearby_system_apps").performClick()
            val system = runBlocking { vm.loadNearbyTransferCategoryPage(FileCategory.INSTALLED_APPS, 0,
                appScope = InstalledAppScope.ALL).getOrThrow() }.entries.first { it.isSystemApp }
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("nearby_page_entries").performScrollToNode(hasTestTag("nearby_entry_${system.absolutePath}")); true }.getOrDefault(false)
            }
            compose.onNodeWithTag("nearby_entry_${system.absolutePath}").performClick()
            compose.onNodeWithTag("nearby_continue").assertTextContains("Next (2)")
            compose.onNodeWithTag("nearby_picker_menu").performClick()
            compose.onNodeWithTag("nearby_system_apps").performClick()
            compose.onNodeWithTag("nearby_continue").assertTextContains("Next (2)")
            capture("nearby-system-apps")
        } finally { store.clear() }
    }

    @Test fun contactSearchAndPagingReachTheLastContactAndKeepSelectionsAcrossPages() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        check(Build.MODEL.contains("sdk", true))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val resolver = app.contentResolver
        val ids = ArrayList<String>()
        val prefix = "AFContact-${System.nanoTime()}-"
        val store = ViewModelStore()
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_CONTACTS, android.Manifest.permission.READ_CONTACTS)
        try {
            repeat(5) { batch ->
                val operations = ArrayList<ContentProviderOperation>()
                val count = if (batch == 4) 205 else 200
                repeat(count) { row ->
                    val index = batch * 200 + row
                    val rawIndex = operations.size
                    operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED).build()
                    operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, prefix + index.toString().padStart(4, '0')).build()
                }
                val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
                results.filterIndexed { index, _ -> index % 2 == 0 }.forEach { ids += requireNotNull(it.uri).lastPathSegment!! }
            }
            val vm = MainViewModel(app).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Kontaktai").performScrollTo().performClick()
            compose.onNodeWithTag("nearby_search_toggle").performClick()
            compose.onNodeWithTag("nearby_search").performTextInput(prefix)
            compose.waitUntil(15_000) { compose.onAllNodesWithText(prefix + "0000").fetchSemanticsNodes().isNotEmpty() }
            // This ordinary search field has ImeAction.Default. Hide only the
            // test's own dialog IME, including a still-pending cold-start show.
            if (Build.VERSION.SDK_INT >= 29) compose.runOnIdle {
                val input = app.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                    input.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
            val serviceInfo = automation.serviceInfo
            serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo
            if (automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(5_000) { automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            }
            automation.waitForIdle(250, 3_000)
            compose.onNodeWithTag("nearby_select_all").performClick()
            compose.onNodeWithTag("nearby_continue").assertTextContains("Next (240)")
            repeat(4) {
                val expected = prefix + ((it + 1) * 240).toString().padStart(4, '0')
                try {
                    compose.waitUntil(5_000) {
                        val next = compose.onNodeWithTag("nearby_next_page")
                        if (next.isDisplayed()) true else { runCatching { next.performScrollTo() }; false }
                    }
                    compose.onNodeWithTag("nearby_next_page").assertIsDisplayed().assertIsEnabled().performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
                } catch (failure: Throwable) {
                    capture("nearby-contacts-paging-failure-$it")
                    val direct = runBlocking { vm.loadNearbyContacts((it + 1) * 240, prefix).getOrThrow() }
                    File(uiAuditDirectory(app), "contact-paging-failure-$it.txt").writeText(
                        "expected=$expected\nproviderFirst=${direct.contacts.firstOrNull()}\nproviderLast=${direct.contacts.lastOrNull()}\nproviderNext=${direct.nextOffset}\n" +
                            compose.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { it.config.toString() })
                    throw failure
                }
            }
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_select_all").performClick()
            compose.onNodeWithTag("nearby_continue").assertTextContains("Next (285)")
            compose.onNodeWithTag("nearby_contacts").performScrollToNode(hasText(prefix + "1004"))
            compose.onNodeWithText(prefix + "1004").assertIsDisplayed()
            compose.onNodeWithTag("nearby_search").performTextReplacement(prefix + "1004")
            compose.waitUntil(15_000) { compose.onAllNodesWithText(prefix + "1004").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nearby_continue").assertTextContains("Next (285)")
            capture("nearby-contacts-after-1000")
        } finally {
            store.clear()
            ids.chunked(200).forEach { chunk ->
                resolver.delete(ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
            }
            automation.dropShellPermissionIdentity()
        }
    }

    @Test fun groupJoiningIsOnThePairingFormWithoutStartingAListenerOnOpen() {
        val store = ViewModelStore()
        val before = com.affilemanager.app.transfer.LanTransferController.state.value
        try {
            val vm = MainViewModel(app).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_continue").performClick()
            compose.onNodeWithTag("nearby_group_join_form").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nearby_group_pairing_input").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nearby_group_join").performScrollTo().assertIsNotEnabled()
            assertEquals(before, com.affilemanager.app.transfer.LanTransferController.state.value)
            capture("nearby-group-in-sender")
        } finally { store.clear() }
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(uiAuditDirectory(app), "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
