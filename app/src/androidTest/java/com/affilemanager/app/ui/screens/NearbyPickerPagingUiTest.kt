package com.affilemanager.app.ui.screens

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NearbyPickerPagingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dateDirectionActuallyReversesRowsAndSelectAllTogglesThePage() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val resolver = application.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val prefix = "af-dates-${System.nanoTime()}"
        val relative = "Pictures/$prefix/"
        val names = listOf("$prefix-c-old.jpg", "$prefix-b-middle.jpg", "$prefix-a-new.jpg")
        val store = ViewModelStore()
        try {
            names.forEachIndexed { index, name ->
                resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.DATE_MODIFIED, 1_700_000_000L + index * 86_400)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                })!!
            }
            val vm = MainViewModel(application).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Nuotraukos").performClick()
            compose.onNodeWithTag("nearby_search").performTextInput(prefix)
            // The search remains live while the keyboard is shown. Close the
            // keyboard before asserting the relative positions of all three rows.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.onNodeWithTag("nearby_sort_MODIFIED").performScrollTo().assertIsDisplayed().performClick()
            waitFor(names.first())
            fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
            compose.waitUntil(10_000) { top(names.first()) < top(names.last()) }
            compose.onNodeWithTag("nearby_sort_direction").assertIsDisplayed().performClick()
            waitFor(names.last())
            compose.waitUntil(10_000) { top(names.first()) > top(names.last()) }
            compose.onNodeWithTag("nearby_select_all").performClick()
            compose.onNodeWithText("Next (3)").assertIsEnabled()
            compose.onNodeWithTag("nearby_select_all").performClick()
            compose.onNodeWithText("Next (0)").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_open_storage").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            resolver.delete(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relative))
        }
    }

    @Test fun selectingAcrossPagesAndSearchPreparesBothFilesWithoutSending() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val resolver = application.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val prefix = "af-picker-${System.nanoTime()}"
        val relative = "Pictures/$prefix/"
        val values = Array(265) { index -> ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$prefix-${index.toString().padStart(3, '0')}.jpg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        } }
        val store = ViewModelStore()
        try {
            assertEquals(265, resolver.bulkInsert(collection, values))
            // MediaStore rows alone are not readable files. Materialize the two
            // selected fixtures before exercising the real transfer preparer.
            resolver.query(collection, arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} IN (?, ?)",
                arrayOf("$prefix-000.jpg", "$prefix-240.jpg"), null)!!.use { cursor ->
                assertEquals(2, cursor.count)
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    while (cursor.moveToNext()) {
                        resolver.openOutputStream(ContentUris.withAppendedId(collection, cursor.getLong(0)))!!.use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                        }
                    }
                } finally { bitmap.recycle() }
            }
            val vm = MainViewModel(application)
            store.put("test", vm)
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Nuotraukos").performClick()
            compose.onNodeWithTag("nearby_search").performTextInput(prefix)
            waitFor("$prefix-000.jpg")
            compose.onNodeWithText("$prefix-000.jpg").performClick()
            compose.onNodeWithTag("nearby_next_page").performClick()
            waitFor("$prefix-240.jpg")
            compose.onNodeWithText("$prefix-240.jpg").performClick()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_previous_page").performClick()
            waitFor("$prefix-000.jpg")
            compose.onNodeWithText("Next (2)").assertIsEnabled()
            compose.onNodeWithTag("nearby_search").performTextReplacement("$prefix-no-match")
            waitFor("No matching files were found in this category")
            compose.onNodeWithTag("nearby_previous_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            compose.onNodeWithText("Next (2)").assertIsEnabled()
            compose.onNodeWithTag("nearby_search").performTextReplacement("$prefix-264.jpg")
            waitFor("$prefix-264.jpg")
            compose.onNodeWithTag("nearby_previous_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            val root = requireNotNull(application.getExternalFilesDir("validation"))
            val width = application.resources.displayMetrics.widthPixels
            compose.onNodeWithTag("nearby_send_dialog").captureToImage().asAndroidBitmap().let { bitmap ->
                File(root, "nearby-paged-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Next (2)").performClick()
            waitFor("Ready to send: 2")
            compose.onNodeWithText("Start transfer").assertIsNotEnabled()
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Next (2)").assertIsEnabled()
        } finally {
            compose.runOnUiThread { store.clear() }
            resolver.delete(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relative))
        }
    }

    private fun waitFor(text: String) {
        val result = hasText(text) and hasSetTextAction().not()
        try {
            compose.waitUntil(15_000) { compose.onNode(result).isDisplayed() }
        } catch (failure: Throwable) {
            val context = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir("validation"), "nearby-layout-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw AssertionError("Waiting for $text\n" + compose.onNodeWithTag("nearby_send_dialog").printToString(), failure)
        }
        compose.onNode(result).assertIsDisplayed()
    }
}
