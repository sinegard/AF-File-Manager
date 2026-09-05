package com.affilemanager.app.data

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.FileEntry
import java.util.Locale
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileCategoryRepositoryPagingTest {
    @Test fun failedPageRequestCanBeRetriedWithoutPoisoningTheNextResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as com.affilemanager.app.AFFileManagerApplication
        val store = androidx.lifecycle.ViewModelStore()
        val viewModel = com.affilemanager.app.ui.MainViewModel(application)
        store.put("retry-test", viewModel)
        try {
            val failed = viewModel.loadNearbyTransferCategoryPage(FileCategory.IMAGES, -240)
            assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
            val retried = viewModel.loadNearbyTransferCategoryPage(FileCategory.IMAGES, 0,
                query = "af-no-match-${System.nanoTime()}")
            assertTrue(retried.isSuccess)
            assertTrue(retried.getOrThrow().entries.isEmpty())
            assertEquals(null, retried.getOrThrow().nextOffset)
        } finally {
            kotlinx.coroutines.withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun cancellingAPageInterruptsTheQueryBeforeItsWorkerFinishes() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            withMediaQueryCancellation { signal ->
                signal.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                assertTrue("Cancellation did not reach the active provider request", cancelled.await(3, TimeUnit.SECONDS))
                signal.throwIfCanceled()
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(0L, cancelled.count)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun nearbyBrowserFindsEveryIndexedFileBeyondOldCapsAndSearchesTheEntireCategory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val prefix = "af-complete-${System.nanoTime()}"
        val relativePath = "Pictures/$prefix/"
        val fixtureCount = 10_205
        val repository = FileCategoryRepository(context, LocalFileRepository(context))
        fun values(name: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        try {
            (0 until fixtureCount).chunked(500).forEach { batch ->
                val rows = batch.map { values("$prefix-${it.toString().padStart(5, '0')}.jpg") }.toTypedArray()
                assertEquals(rows.size, resolver.bulkInsert(collection, rows))
            }
            var offset: Int? = 0
            val seen = linkedSetOf<String>()
            var pageCount = 0
            while (offset != null) {
                val page = repository.loadBrowsePage(FileCategory.IMAGES, offset, SortMode.NAME, SortDirection.ASCENDING, prefix)
                assertTrue(page.entries.size <= 240)
                assertTrue(page.scannedRows <= 240)
                assertTrue(!page.truncated)
                page.entries.forEach { assertTrue("Duplicate or skipped window", seen.add(it.name)) }
                offset = page.nextOffset
                assertTrue("Paging must terminate", ++pageCount < 50)
            }
            assertEquals(fixtureCount, seen.size)
            assertEquals(seen.sorted(), seen.toList())
            val lastName = "$prefix-10204.jpg"
            val found = repository.loadBrowsePage(FileCategory.IMAGES, 0, SortMode.NAME, SortDirection.ASCENDING, lastName)
            assertEquals(listOf(lastName), found.entries.map(FileEntry::name))
            val reversed = repository.loadBrowsePage(FileCategory.IMAGES, 0, SortMode.NAME, SortDirection.DESCENDING, prefix)
            assertEquals(lastName, reversed.entries.first().name)
            val previous = repository.loadBrowsePage(FileCategory.IMAGES, 0, SortMode.NAME, SortDirection.ASCENDING, prefix)
            assertEquals("$prefix-00000.jpg", previous.entries.first().name)
            val literal = "$prefix-100%_literal.jpg"
            assertNotNull(resolver.insert(collection, values(literal)))
            assertNotNull(resolver.insert(collection, values("$prefix-100XXliteral.jpg")))
            val exact = repository.loadBrowsePage(FileCategory.IMAGES, 0, SortMode.NAME, SortDirection.ASCENDING, "100%_literal", forceRefresh = true)
            assertEquals(listOf(literal), exact.entries.filter { it.absolutePath.contains(prefix) }.map(FileEntry::name))
        } finally {
            resolver.delete(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath))
        }
    }

    @Test
    fun installedAppBackupIsStagedAndVerifiedBeforeExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = FileCategoryRepository(context, LocalFileRepository(context))
        val temporaryDirectory = File(context.cacheDir, "apk-stage-${System.nanoTime()}").apply { mkdirs() }
        try {
            var offset: Int? = 0
            var ownEntry: FileEntry? = null
            while (offset != null && ownEntry == null) {
                val requestedOffset = requireNotNull(offset)
                val page = repository.loadPage(
                    category = FileCategory.INSTALLED_APPS,
                    offset = requestedOffset,
                    sortMode = SortMode.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    forceRefresh = requestedOffset == 0,
                    showSystemApps = true,
                )
                ownEntry = page.entries.firstOrNull { it.packageName == context.packageName }
                offset = page.nextOffset
            }

            val backup = repository.stageInstalledApp(requireNotNull(ownEntry), temporaryDirectory)
            assertTrue(backup.isFile)
            assertTrue(backup.length() > 0L)
            assertTrue(backup.canonicalFile.toPath().startsWith(temporaryDirectory.canonicalFile.toPath()))
            if (backup.extension.equals("apks", ignoreCase = true)) {
                ZipFile(backup).use { archive -> assertNotNull(archive.getEntry("base.apk")) }
            } else {
                assertEquals("apk", backup.extension.lowercase(Locale.ROOT))
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun installedAppsHideSystemPackagesUntilExplicitlyRequested() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = FileCategoryRepository(context, LocalFileRepository(context))

        val userApps = repository.loadPage(
            category = FileCategory.INSTALLED_APPS,
            offset = 0,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.ASCENDING,
            forceRefresh = true,
            showSystemApps = false,
        ).entries
        val allApps = repository.loadPage(
            category = FileCategory.INSTALLED_APPS,
            offset = 0,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.ASCENDING,
            forceRefresh = true,
            showSystemApps = true,
        ).entries

        assertTrue(userApps.none { it.isSystemApp })
        assertTrue(allApps.any { it.isSystemApp })
        assertTrue(allApps.all { !it.packageName.isNullOrBlank() })
    }

    @Test
    fun imagesAreReturnedInBoundedGloballySortedPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "Pictures/AFPaging-${System.nanoTime()}/"
        val fixtureCount = 360
        val values = Array(fixtureCount) { index ->
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "af-page-${index.toString().padStart(4, '0')}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
        }
        try {
            assertEquals(fixtureCount, resolver.bulkInsert(collection, values))
            val repository = FileCategoryRepository(context, LocalFileRepository(context))
            repository.invalidate(FileCategory.IMAGES)

            val pages = mutableListOf<FileCategoryPage>()
            var offset: Int? = 0
            repeat(8) {
                val requestedOffset = offset ?: return@repeat
                val page = repository.loadPage(
                    category = FileCategory.IMAGES,
                    offset = requestedOffset,
                    sortMode = SortMode.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    forceRefresh = requestedOffset == 0,
                )
                pages += page
                offset = page.nextOffset
                if (pages.flatMap(FileCategoryPage::entries).count { it.absolutePath.contains(relativePath.trimEnd('/')) } >= fixtureCount) {
                    offset = null
                }
            }

            assertTrue(pages.isNotEmpty())
            assertTrue(pages.first().entries.size <= FileCategoryPagingRules.FIRST_PAGE_RESULTS)
            assertTrue(pages.first().scannedRows <= FileCategoryPagingRules.MAX_SCANNED_ROWS_PER_PAGE)
            assertNotNull(pages.first().nextOffset)
            pages.drop(1).forEach { page ->
                assertTrue(page.entries.size <= FileCategoryPagingRules.NEXT_PAGE_RESULTS)
                assertTrue(page.scannedRows <= FileCategoryPagingRules.MAX_SCANNED_ROWS_PER_PAGE)
            }
            val fixtureNames = pages.flatMap(FileCategoryPage::entries)
                .filter { it.absolutePath.contains(relativePath.trimEnd('/')) }
                .map { it.name.lowercase(Locale.ROOT) }
            assertEquals(fixtureCount, fixtureNames.distinct().size)
            assertEquals(fixtureNames.sorted(), fixtureNames)
        } finally {
            resolver.delete(
                collection,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
            )
        }
    }
}
