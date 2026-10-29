package com.affilemanager.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.model.EntryKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ThumbnailQualityTest {
    @org.junit.Test fun tinySourceKeepsItsPixelsWithoutInflatingItsCachedBitmap(): Unit = kotlinx.coroutines.runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.affilemanager.app.AFFileManagerApplication>()
        val file = java.io.File(app.cacheDir, "AF-tiny-thumbnail-${System.nanoTime()}.png")
        val source = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        try {
            file.outputStream().use { check(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            val entry = app.graph.localFiles.toEntry(file)
            val loaded = requireNotNull(FileVisualLoader.loadLocal(app, entry, 256, 256, showThumbnails = true))
            org.junit.Assert.assertTrue(loaded.contentThumbnail && loaded.crop)
            org.junit.Assert.assertEquals(1, loaded.bitmap.width)
            org.junit.Assert.assertEquals(1, loaded.bitmap.height)
            org.junit.Assert.assertEquals(android.graphics.Color.BLUE, loaded.bitmap.getPixel(0, 0))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file).toString()
            val saf = SafEntry(uri, file.name, false, file.length(), file.lastModified(), "image/png", EntryKind.IMAGE, true)
            val provider = requireNotNull(FileVisualLoader.loadSaf(app, saf, 256, 256))
            assertEquals(1, provider.bitmap.width)
            assertEquals(1, provider.bitmap.height)
            assertEquals(Color.BLUE, provider.bitmap.getPixel(0, 0))
        } finally { source.recycle(); check(file.delete()) }
    }
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    @Test fun localAndProviderCropsUseTheFullTargetAndKeepExactPixelsOnDisk() = runBlocking {
        val file = fixture()
        try {
            val entry = app.graph.localFiles.toEntry(file)
            val first = requireNotNull(FileVisualLoader.loadLocal(app, entry, 256, 256, true))
            assertEquals(256, first.bitmap.width)
            assertEquals(256, first.bitmap.height)
            assertEquals(Color.GREEN, first.bitmap.getPixel(128, 128))
            val counters = FileVisualLoader.cacheCounters()
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            val disk = requireNotNull(FileVisualLoader.loadLocal(app, entry, 256, 256, true))
            assertTrue(first.bitmap.sameAs(disk.bitmap))
            assertEquals(counters[1], FileVisualLoader.cacheCounters()[1])
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file).toString()
            val saf = SafEntry(uri, file.name, false, file.length(), file.lastModified(), "image/png", EntryKind.IMAGE, true)
            val provider = requireNotNull(FileVisualLoader.loadSaf(app, saf, 192, 144))
            assertEquals(192, provider.bitmap.width)
            assertEquals(144, provider.bitmap.height)
            assertEquals(Color.GREEN, provider.bitmap.getPixel(96, 72))
        } finally { FileVisualLoader.invalidateLocal(app, listOf(file)); file.delete() }
    }

    @Test fun actualFrameSizeOverridesTheOldGuessedGridWidth() {
        val file = fixture()
        val entry = app.graph.localFiles.toEntry(file)
        var width = 0
        var height = 0
        try {
            compose.setContent { MaterialTheme {
                val density = androidx.compose.ui.platform.LocalDensity.current
                width = FileVisualRules.boundedDimension(with(density) { 200.dp.roundToPx() })
                height = FileVisualRules.boundedDimension(with(density) { 100.dp.roundToPx() })
                LocalFileVisual(entry, 48.dp, 48.dp, true, Modifier.size(200.dp, 100.dp))
            } }
            compose.waitUntil(10_000) {
                FileVisualLoader.peek(FileVisualRules.localCacheKey(entry.absolutePath, entry.modifiedAtMillis, entry.sizeBytes,
                    entry.kind, entry.extension, width, height, true)) != null
            }
            val bitmap = requireNotNull(FileVisualLoader.peek(FileVisualRules.localCacheKey(entry.absolutePath,
                entry.modifiedAtMillis, entry.sizeBytes, entry.kind, entry.extension, width, height, true))).bitmap
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
        } finally { runBlocking { FileVisualLoader.invalidateLocal(app, listOf(file)) }; file.delete() }
    }

    @Test fun nativeVideoThumbnailUsesTheFullCropAndDoesNotModifyTheVideo() = runBlocking {
        val file = File(app.filesDir, "video-quality-${System.nanoTime()}.mp4")
        try {
            com.affilemanager.app.media.editing.MediaTestFixtures.video(file, audio = false)
            val original = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val visual = requireNotNull(FileVisualLoader.loadLocal(app, app.graph.localFiles.toEntry(file), 192, 144, true))
            assertEquals(192, visual.bitmap.width)
            assertEquals(144, visual.bitmap.height)
            assertTrue(visual.contentThumbnail && visual.crop)
            assertArrayEquals(original, java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { FileVisualLoader.invalidateLocal(app, listOf(file)); file.delete() }
    }

    @Test fun thinImagesUseBoundedOutputAndPreserveTransparentPixels() = runBlocking {
        val file = File(app.filesDir, "thin-quality-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(16_000, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val local = requireNotNull(FileVisualLoader.loadLocal(app, app.graph.localFiles.toEntry(file), 512, 512, true))
            assertEquals(128, local.bitmap.width)
            assertEquals(128, local.bitmap.height)
            assertEquals(Color.TRANSPARENT, local.bitmap.getPixel(64, 64))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file).toString()
            val saf = SafEntry(uri, file.name, false, file.length(), file.lastModified(), "image/png", EntryKind.IMAGE, true)
            val provider = requireNotNull(FileVisualLoader.loadSaf(app, saf, 512, 512))
            assertEquals(128, provider.bitmap.width)
            assertEquals(128, provider.bitmap.height)
            assertEquals(Color.TRANSPARENT, provider.bitmap.getPixel(64, 64))
        } finally { bitmap.recycle(); FileVisualLoader.invalidateLocal(app, listOf(file)); check(file.delete()) }
    }

    private fun fixture(): File {
        val file = File(app.filesDir, "quality-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }
}
