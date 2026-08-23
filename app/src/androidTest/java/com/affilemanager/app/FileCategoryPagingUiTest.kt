package com.affilemanager.app

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.FileCategoryPagingRules
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileCategoryPagingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun imagesShowABoundedFirstPageAndAppendWhileScrolling() {
        val resolver = compose.activity.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "Pictures/AFUiPaging-${System.nanoTime()}/"
        val fixtureCount = 600
        val values = Array(fixtureCount) { index ->
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "af-ui-${index.toString().padStart(4, '0')}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
        }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            assertTrue(resolver.bulkInsert(collection, values) == fixtureCount)
            val startedAt = SystemClock.elapsedRealtime()
            compose.runOnUiThread {
                viewModel.setSection(AppSection.FILES)
                viewModel.openFileCategory(FileCategory.IMAGES, forceRefresh = true)
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                val state = viewModel.fileCategory.value
                state.open && !state.loading && state.entries.isNotEmpty()
            }
            val firstPageMillis = SystemClock.elapsedRealtime() - startedAt
            val firstPageSize = viewModel.fileCategory.value.entries.size
            Log.i("AF_CATEGORY_PERF", "first_page_ms=$firstPageMillis entries=$firstPageSize")
            assertTrue(firstPageSize <= FileCategoryPagingRules.FIRST_PAGE_RESULTS)
            assertNotNull(viewModel.fileCategory.value.nextOffset)
            compose.onNodeWithTag("category_list").assertIsDisplayed().performScrollToIndex(firstPageSize - 1)

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.fileCategory.value.entries.size > firstPageSize && !viewModel.fileCategory.value.loadingMore
            }
            assertTrue(viewModel.fileCategory.value.entries.size <= firstPageSize + FileCategoryPagingRules.NEXT_PAGE_RESULTS)
            compose.onNodeWithTag("category_list").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.closeFileCategory() }
            resolver.delete(
                collection,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
            )
        }
    }
}
