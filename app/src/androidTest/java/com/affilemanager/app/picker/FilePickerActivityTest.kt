package com.affilemanager.app.picker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.system.Os
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FilePickerActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
    private val root = Environment.getExternalStorageDirectory()
    private lateinit var fixture: File
    private var oldLock = false

    @Before fun createFixture() {
        check(Environment.isExternalStorageManager()) { "Grant all-files access only on the isolated test emulator before this suite" }
        fixture = File(root, "Download/af-picker-145-${UUID.randomUUID()}").apply { check(mkdirs()) }
        File(fixture, "first.txt").writeText("first picker fixture")
        File(fixture, "second.txt").writeText("second picker fixture")
        File(fixture, "excluded.png").writeBytes(byteArrayOf(0, 1, 2))
        File(fixture, "folder").mkdir()
        oldLock = app.graph.appLock.enabled.value
        app.graph.appLock.setEnabled(false)
    }

    @After fun cleanupFixture() {
        app.graph.appLock.setEnabled(oldLock)
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    @Test fun getContentHandlerIsDiscoverableAndMalformedRequestsDoNotBroadenTypes() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        assertTrue(app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
            it.activityInfo.packageName == app.packageName && it.activityInfo.name == FilePickerActivity::class.java.name
        })
        assertNull(FilePickerActivity.parseRequest(intent.putExtra(Intent.EXTRA_MIME_TYPES, "image/png")))
        assertNull(FilePickerActivity.parseRequest(Intent(Intent.ACTION_VIEW).setType("*/*")))
        val valid = FilePickerActivity.parseRequest(Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf")))!!
        assertTrue(valid.accepts("image/png"))
        assertFalse(valid.accepts("text/plain"))
        val symlinkRoot = File(app.cacheDir, "picker-boundary-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val link = File(symlinkRoot, "escape")
            Os.symlink(app.filesDir.path, link.path)
            assertNull(FilePickerBoundary(listOf(symlinkRoot)).permitted(link.path))
        } finally { symlinkRoot.deleteRecursively() }
    }

    @Test fun multipleSelectionFiltersTypesKeepsFolderNavigationAndReturnsReadOnlyUris() {
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(multiple = true)).use { scenario ->
            openFixture()
            compose.onNodeWithText("excluded.png").assertDoesNotExist()
            clickEntry(File(fixture, "first.txt").path)
            clickEntry(File(fixture, "folder").path) // folders still navigate when files are selected
            compose.onNodeWithTag("local_upload_up").performScrollTo().performClick()
            clickEntry(File(fixture, "second.txt").path)
            capture("picker-multiple")
            compose.onNodeWithText("Select (2)").performClick()
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val data = result.resultData
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, data.flags)
            assertEquals(2, data.clipData!!.itemCount)
            assertEquals("content", data.data!!.scheme)
            assertEquals("${app.packageName}.files", data.data!!.authority)
            assertEquals("first picker fixture", app.contentResolver.openInputStream(data.data!!)!!.bufferedReader().use { it.readText() })
            assertTrue(File(fixture, "first.txt").exists())
        }
    }

    @Test fun singleSelectionReplacesPreviousAndCancelReturnsNoGrant() {
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(multiple = false)).use { scenario ->
            openFixture()
            clickEntry(File(fixture, "first.txt").path)
            clickEntry(File(fixture, "second.txt").path)
            compose.onNodeWithText("Select (1)").performClick()
            assertEquals(1, scenario.result.resultData.clipData!!.itemCount)
            assertEquals("second picker fixture", app.contentResolver.openInputStream(scenario.result.resultData.data!!)!!.bufferedReader().use { it.readText() })
        }
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(multiple = true)).use { scenario ->
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertNull(scenario.result.resultData)
        }
    }

    @Test fun appLockCannotBeBypassedByExternalPickerOrBackgroundReturn() {
        app.graph.appLock.setEnabled(true)
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(false)).use { scenario ->
            compose.onNodeWithText("AF File Manager is locked").assertIsDisplayed()
            compose.onNodeWithTag("local_upload_search").assertDoesNotExist()
            compose.onNodeWithText("Unlock").performClick()
            // This isolated emulator deliberately has no biometric/device credential enrolled.
            compose.onNodeWithText("No supported biometric or screen lock is configured on this device").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
        app.graph.appLock.setEnabled(false)
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(false)).use { scenario ->
            openFixture()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            app.graph.appLock.setEnabled(true)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithText("AF File Manager is locked").assertIsDisplayed()
            compose.onNodeWithText("first.txt").assertDoesNotExist()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test fun aRemovedSelectionDoesNotReturnPartialSuccess() {
        ActivityScenario.launchActivityForResult<FilePickerActivity>(request(true)).use {
            openFixture()
            clickEntry(File(fixture, "first.txt").path)
            clickEntry(File(fixture, "second.txt").path)
            File(fixture, "second.txt").delete()
            compose.onNodeWithText("Select (2)").performClick()
            compose.onNodeWithText("Selected files are unavailable or do not match the requested type").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(Activity.RESULT_CANCELED, it.result.resultCode)
        }
    }

    private fun request(multiple: Boolean) = Intent(app, FilePickerActivity::class.java)
        .setAction(Intent.ACTION_GET_CONTENT).setType("text/plain")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)

    private fun openFixture() {
        clickEntry(root.path)
        clickEntry(File(root, "Download").path)
        clickEntry(fixture.path)
    }

    private fun clickEntry(path: String) {
        val tag = "local_upload_entry_$path"
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("local_upload_list").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false)
        }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("No screenshot")
        val directory = File(app.getExternalFilesDir(null), "validation").apply { mkdirs() }
        File(directory, "$name-${bitmap.width}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
