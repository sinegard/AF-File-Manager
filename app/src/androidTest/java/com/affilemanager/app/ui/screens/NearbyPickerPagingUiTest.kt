package com.affilemanager.app.ui.screens

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
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
        val collection = imageCollection()
        val prefix = "af-dates-${System.nanoTime()}"
        val relative = "Pictures/$prefix/"
        val legacyRoot = legacyRoot(relative)
        val names = listOf("$prefix-c-old.jpg", "$prefix-b-middle.jpg", "$prefix-a-new.jpg")
        val store = ViewModelStore()
        try {
            names.forEachIndexed { index, name ->
                // On Android 8 the provider owns files it creates. Create this
                // disposable fixture ourselves so its filesystem date is writable.
                if (legacyRoot != null) check(File(legacyRoot, name).createNewFile())
                val uri = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    else put(MediaStore.MediaColumns.DATA, File(requireNotNull(legacyRoot), name).path)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.DATE_MODIFIED, 1_700_000_000L + index * 86_400)
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try { resolver.openOutputStream(uri)!!.use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                finally { image.recycle() }
                if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val actualFile = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                    check(it.moveToFirst()); File(it.getString(0))
                }
                org.junit.Assert.assertTrue("Published fixture absent: $actualFile", actualFile.isFile)
                val date = 1_700_000_000L + index * 86_400
                if (Build.VERSION.SDK_INT >= 29) {
                    org.junit.Assert.assertTrue("Cannot set date of published fixture: $actualFile", actualFile.setLastModified(date * 1000))
                }
                kotlinx.coroutines.runBlocking { application.graph.sharedStorageIndex.changed(listOf(actualFile)) }
                if (Build.VERSION.SDK_INT < 29) {
                    // Android 8's shared-storage mount rejects utimes even for a
                    // file created by this UID. This browser sorts the real provider's
                    // DATE_MODIFIED, so set and verify that indexed fixture date.
                    assertEquals(1, resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DATE_MODIFIED, date)
                    }, null, null))
                }
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)!!.use {
                    check(it.moveToFirst()); assertEquals(date, it.getLong(0))
                }
            }
            val vm = MainViewModel(application).also { store.put("test", it) }
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Nuotraukos").performClick()
            compose.onNodeWithTag("nearby_search_toggle").performClick()
            compose.onNodeWithTag("nearby_search").performTextInput(prefix)
            // The search remains live while the keyboard is shown. Close it so
            // the first sorted row can be checked in a stable viewport.
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val service = automation.serviceInfo
            service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
            check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5_000) { automation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            automation.waitForIdle(500, 3000)
            compose.onNodeWithTag("nearby_sort_direction").performTouchInput { longClick() }
            compose.onNodeWithTag("nearby_sort_MODIFIED").performScrollTo().assertIsDisplayed().performClick()
            waitForFirstEntry(names.first())
            compose.onNodeWithTag("nearby_sort_direction").assertIsDisplayed().performClick()
            waitForFirstEntry(names.last())
            compose.onNodeWithTag("nearby_select_all").performClick()
            waitForNext(3)
            compose.onNodeWithText("Next (3)").assertIsEnabled()
            compose.onNodeWithTag("nearby_select_all").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("nearby_continue").fetchSemanticsNodes()
                    .singleOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null &&
                    compose.onAllNodesWithTag("nearby_continue").fetchSemanticsNodes().size == 1
            }
            reach(compose.onNodeWithTag("nearby_continue")).assertIsDisplayed().assertIsEnabled().performClick()
            waitFor("Pair the receiving phone")
            compose.onNodeWithTag("nearby_pairing_input").assertIsNotFocused()
            reach(compose.onNodeWithText("Back")).assertIsDisplayed().performClick()
            waitFor("Choose files to send")
            compose.onNodeWithTag("nearby_continue").assertIsEnabled()
            reach(compose.onNodeWithTag("nearby_open_storage")).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            cleanupImages(resolver, collection, relative, legacyRoot)
        }
    }

    @Test fun selectingAcrossPagesAndSearchPreparesBothFilesWithoutSending() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val resolver = application.contentResolver
        val collection = imageCollection()
        val prefix = "af-picker-${System.nanoTime()}"
        val relative = "Pictures/$prefix/"
        val legacyRoot = legacyRoot(relative)
        val values = Array(265) { index -> ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$prefix-${index.toString().padStart(3, '0')}.jpg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            else put(MediaStore.MediaColumns.DATA, File(requireNotNull(legacyRoot), "$prefix-${index.toString().padStart(3, '0')}.jpg").path)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // Publish only after the bytes are written. A factory scanner must
            // not observe hundreds of empty files while this corpus is prepared.
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        } }
        val store = ViewModelStore()
        try {
            assertEquals(265, resolver.bulkInsert(collection, values))
            // Every visible row must be a real published file, including search-only rows.
            // Pending or stale metadata is not a valid paging/sorting fixture.
            val preparingCollection = if (Build.VERSION.SDK_INT >= 29) MediaStore.setIncludePending(collection) else collection
            resolver.query(preparingCollection, arrayOf(BaseColumns._ID),
                fixtureSelection(), fixtureArguments(relative, legacyRoot), null)!!.use { cursor ->
                assertEquals(265, cursor.count)
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        resolver.openOutputStream(uri)!!.use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                        }
                        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { actual ->
                            check(actual.moveToFirst())
                            val file = File(actual.getString(0))
                            check(file.isFile) { "Published paging fixture absent: $file; exists=${file.exists()}, readable=${file.canRead()}, " +
                                "allFiles=${Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()}" }
                            kotlinx.coroutines.runBlocking { application.graph.sharedStorageIndex.changed(listOf(file)) }
                        }
                    }
                } finally { bitmap.recycle() }
            }
            val vm = MainViewModel(application)
            store.put("test", vm)
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_category_Nuotraukos").performClick()
            compose.onNodeWithTag("nearby_search_toggle").performClick()
            compose.onNodeWithTag("nearby_search").performTextInput(prefix)
            waitFor("$prefix-000.jpg")
            compose.onNodeWithText("$prefix-000.jpg").performClick()
            reach(compose.onNodeWithTag("nearby_next_page")).assertIsDisplayed().performClick()
            waitFor("$prefix-240.jpg")
            compose.onNodeWithText("$prefix-240.jpg").performClick()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            reach(compose.onNodeWithTag("nearby_previous_page")).assertIsDisplayed().performClick()
            waitFor("$prefix-000.jpg")
            waitForNext(2)
            reach(compose.onNodeWithTag("nearby_category_Vaizdo įrašai")).assertIsDisplayed().performClick()
            waitForNext(2)
            reach(compose.onNodeWithTag("nearby_category_Nuotraukos")).assertIsDisplayed().performClick()
            waitFor("$prefix-000.jpg")
            reach(compose.onNodeWithTag("nearby_search")).performTextReplacement("$prefix-no-match")
            waitFor("No matching files were found in this category")
            compose.onNodeWithTag("nearby_previous_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            compose.onNodeWithText("Next (2)").assertIsEnabled()
            reach(compose.onNodeWithTag("nearby_search")).performTextReplacement("$prefix-264.jpg")
            waitFor("$prefix-264.jpg")
            compose.onNodeWithTag("nearby_previous_page").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_next_page").assertIsNotEnabled()
            val root = requireNotNull(application.getExternalFilesDir("validation"))
            val width = application.resources.displayMetrics.widthPixels
            // Compose PixelCopy cannot capture Dialog windows below API 28.
            // Android's actual full-window screenshot covers the same rendered UI.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                try { File(root, "nearby-paged-$width.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            reach(compose.onNodeWithText("Next (2)")).assertIsDisplayed().performClick()
            waitFor("Ready to send: 2")
            compose.onNodeWithTag("nearby_pairing_input").assertIsNotFocused()
            val test = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            test.uiAutomation.takeScreenshot().let { bitmap ->
                try { File(root, "nearby-pairing-$width.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            // Initial focus must not prevent normal native typing afterwards.
            val access = test.uiAutomation.serviceInfo
            access.flags = access.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            test.uiAutomation.serviceInfo = access
            reach(compose.onNodeWithTag("nearby_pairing_input")).performTouchInput { click() }
            compose.waitUntil(5_000) { test.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            compose.onNodeWithTag("nearby_pairing_input").performTextInput("not-a-pairing-code")
            compose.onNodeWithTag("nearby_pairing_input").assertIsFocused()
            compose.onNodeWithText("Start transfer").assertIsNotEnabled()
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5_000) { test.uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            reach(compose.onNodeWithText("Back")).assertIsDisplayed().performClick()
            waitFor("Choose files to send")
            compose.onNodeWithText("Next (2)").assertIsEnabled()
        } finally {
            compose.runOnUiThread { store.clear() }
            cleanupImages(resolver, collection, relative, legacyRoot)
        }
    }

    private fun imageCollection() = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun legacyRoot(relative: String): File? = if (Build.VERSION.SDK_INT >= 29) null else
        File(Environment.getExternalStorageDirectory(), relative).apply { check(mkdirs()) }

    private fun fixtureSelection() = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        else "${MediaStore.MediaColumns.DATA} LIKE ?"

    private fun fixtureArguments(relative: String, root: File?) = arrayOf(
        if (Build.VERSION.SDK_INT >= 29) relative else requireNotNull(root).absolutePath.trimEnd('/') + "/%")

    private fun cleanupImages(resolver: android.content.ContentResolver, collection: android.net.Uri, relative: String, root: File?) {
        resolver.delete(collection, fixtureSelection(), fixtureArguments(relative, root))
        root?.let { check(it.deleteRecursively() || !it.exists()) }
    }

    private fun waitFor(text: String) {
        val result = hasText(text) and hasSetTextAction().not()
        try {
            compose.waitUntil(15_000) {
                val node = compose.onAllNodes(result).fetchSemanticsNodes().singleOrNull()
                // Compact keyboard layouts have a real scrolling body. Reach
                // the result like a user, then still require it to be rendered.
                if (node != null) reach(compose.onNode(result))
                compose.onNode(result).isDisplayed()
            }
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

    private fun waitForNext(count: Int) {
        val label = "Next ($count)"
        compose.waitUntil(15_000) {
            val node = compose.onAllNodesWithText(label).fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithText(label).assertIsEnabled()
    }

    private fun reach(target: SemanticsNodeInteraction): SemanticsNodeInteraction {
        // A row/category can be inside several independent scroll viewports.
        // The nearest one alone cannot bring it through the outer body's clip.
        // Refresh each observed parent below: closing the native IME can replace
        // the compact root between fetching a node and a performScrollTo call.
        repeat(4) {
            val current = target.fetchSemanticsNode()
            val ancestors = generateSequence(current.parent) { it.parent }
                .filter { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) }
                .map { it.id }.toList()
            for (id in ancestors) {
                val ancestor = compose.onNode(SemanticsMatcher("observed scroll ancestor $id") { it.id == id }, useUnmergedTree = true)
                val viewport = ancestor.fetchSemanticsNode()
                val actual = target.fetchSemanticsNode()
                fun delta(start: Float, size: Int, viewportStart: Float, viewportSize: Int) = when {
                    start < viewportStart -> start - viewportStart
                    start + size > viewportStart + viewportSize -> start + size - viewportStart - viewportSize
                    else -> 0f
                }
                val dx = if (viewport.config.contains(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
                    delta(actual.positionInRoot.x, actual.size.width, viewport.positionInRoot.x, viewport.size.width) else 0f
                val dy = if (viewport.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                    delta(actual.positionInRoot.y, actual.size.height, viewport.positionInRoot.y, viewport.size.height) else 0f
                if (kotlin.math.abs(dx) > 1f || kotlin.math.abs(dy) > 1f) {
                    ancestor.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { scroll -> scroll(dx, dy) }
                    compose.waitForIdle()
                }
            }
        }
        return target
    }

    private fun waitForFirstEntry(text: String) {
        val result = hasTestTag("nearby_first_entry") and hasText(text)
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(result, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Waiting for first entry $text\n" + compose.onNodeWithTag("nearby_send_dialog").printToString(),
                failure,
            )
        }
        reach(compose.onNode(result, useUnmergedTree = true)).assertIsDisplayed()
    }
}
