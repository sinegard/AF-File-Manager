package com.affilemanager.app

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.data.CategoryStorageScope
import com.affilemanager.app.data.CategoryViewMode
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

class IssueNavigationRegressionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyFileCategoryStaysScopedInsideItsFolderAndBackRestoresTheCategory() {
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AFCategory-${UUID.randomUUID()}")
        check(root.mkdirs())
        val specifications = listOf(
            Triple(FileCategory.IMAGES, "photo.jpg", "image/jpeg"),
            Triple(FileCategory.VIDEOS, "movie.mp4", "video/mp4"),
            Triple(FileCategory.AUDIO, "song.mp3", "audio/mpeg"),
            Triple(FileCategory.DOCUMENTS, "note.txt", "text/plain"),
            Triple(FileCategory.ARCHIVES, "archive.zip", "application/zip"),
            Triple(FileCategory.APPS, "package.apk", "application/vnd.android.package-archive"),
        )
        val collection = MediaStore.Files.getContentUri("external")
        val rows = mutableListOf<android.net.Uri>()
        fun register(file: File, mime: String) {
            file.parentFile!!.mkdirs()
            val type = when {
                mime.startsWith("image/") -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                mime.startsWith("video/") -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                mime.startsWith("audio/") -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                else -> MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.path)
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.Files.FileColumns.MEDIA_TYPE, type)
            }
            val uri = requireNotNull(app.contentResolver.insert(collection, values))
            rows += uri
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write("fixture used only for category navigation".toByteArray()) }
            check(file.isFile) { "Provider did not materialize ${file.name} at its indexed path" }
        }
        try {
            specifications.forEach { (_, name, mime) -> register(File(root, name), mime); register(File(root, "child/$name"), mime) }
            repeat(40) { register(File(root, "scroll-$it/photo.jpg"), "image/jpeg") }
            for ((category, name, _) in specifications) {
                compose.runOnUiThread { vm.openFileCategory(category, true); vm.setFileCategoryView(CategoryStorageScope.INTERNAL, CategoryViewMode.FILES) }
                compose.waitUntil(10_000) { !vm.fileCategory.value.loading && compose.onAllNodesWithTag("category_folder_chips").fetchSemanticsNodes().isNotEmpty() }
                val pinnedBefore = compose.onNodeWithTag("category_storage_scope").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("category_folder_chips").performTouchInput { swipeLeft() }
                val pinnedAfter = compose.onNodeWithTag("category_storage_scope").fetchSemanticsNode().boundsInRoot
                assertEquals(pinnedBefore, pinnedAfter)
                compose.onNodeWithTag("category_storage_scope").performClick()
                compose.onNodeWithTag("category_scope_FOLDERS_INTERNAL").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { !vm.fileCategory.value.loading }
                assertTrue("$category: ${vm.fileCategory.value.error}; folders=${vm.fileCategory.value.entries.map { it.absolutePath }}",
                    vm.fileCategory.value.entries.any { it.absolutePath == root.path })
                val folder = vm.fileCategory.value.entries.first { it.absolutePath == root.path }
                compose.onNodeWithTag("category_storage_scope").assertIsDisplayed()
                var previousPosition: Float? = null
                if (category == FileCategory.IMAGES) {
                    compose.onNodeWithTag("category_list").performScrollToIndex(25)
                    previousPosition = compose.onNodeWithTag("category_list").fetchSemanticsNode()
                        .config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(previousPosition >= 25f)
                }
                compose.runOnUiThread { vm.openFileCategoryEntry(folder) }
                compose.waitUntil(10_000) { !vm.fileCategory.value.loading && vm.fileCategory.value.parentFolderPath == root.path }
                assertEquals(category, vm.fileCategory.value.category)
                assertEquals(listOf(name), vm.fileCategory.value.entries.map { it.name })
                assertFalse(vm.fileCategory.value.entries.any { it.isDirectory })
                compose.runOnUiThread { vm.backFileCategory() }
                assertTrue(vm.fileCategory.value.open)
                assertNull(vm.fileCategory.value.parentFolderPath)
                assertEquals(CategoryViewMode.FOLDERS, vm.fileCategory.value.viewMode)
                assertEquals(category, vm.fileCategory.value.category)
                if (previousPosition != null) {
                    compose.waitForIdle()
                    val restoredPosition = compose.onNodeWithTag("category_list").fetchSemanticsNode()
                        .config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertEquals(previousPosition, restoredPosition, 0.01f)
                }
                compose.runOnUiThread { vm.backFileCategory() }
                assertFalse(vm.fileCategory.value.open)
            }
        } finally {
            compose.runOnUiThread { vm.closeFileCategory() }
            root.deleteRecursively()
            rows.forEach { app.contentResolver.delete(it, null, null) }
            runBlocking { app.graph.sharedStorageIndex.changed(listOf(root)) }
        }
    }
}
