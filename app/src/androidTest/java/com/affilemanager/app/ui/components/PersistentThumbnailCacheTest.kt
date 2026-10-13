package com.affilemanager.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.ContentFileRepository
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.data.TrashRepository
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.thumbnail.ThumbnailDiskCache
import com.affilemanager.app.thumbnail.ThumbnailSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PersistentThumbnailCacheTest {
    private val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
    private val repository get() = app.graph.localFiles
    private fun disk() = ThumbnailDiskCache(File(app.cacheDir, "af-thumbnails-v1"))
    private fun fixtureDirectory() = File(app.filesDir, "owned-thumbnails-${UUID.randomUUID()}").apply { check(mkdir()) }

    private fun image(file: File, width: Int = 1_600, height: Int = 1_200): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        repeat(height) { y ->
            repeat(width) { x -> row[x] = Color.rgb(x % 256, y % 256, (x * 7 + y * 11) % 256) }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    private suspend fun thumbnail(file: File, size: Int = 96): LoadedFileVisual = requireNotNull(
        FileVisualLoader.loadLocal(app, repository.toEntry(file), size, size, showThumbnails = true),
    )

    private suspend fun cleanup(directory: File) {
        FileVisualLoader.invalidateLocal(app, listOf(directory))
        check(directory.deleteRecursively())
    }

    @Test fun diskHitAfterRamAndIndexResetKeepsExactPixelsAndBothSizes() = runBlocking {
        val root = fixtureDirectory()
        try {
            val original = image(File(root, "image|ž.png"))
            val first96 = thumbnail(original)
            val first256 = thumbnail(original, 256)
            val expected96 = first96.bitmap.getPixel(40, 30)
            val expected256 = first256.bitmap.getPixel(100, 80)
            val before = FileVisualLoader.cacheCounters()
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            assertEquals(expected96, thumbnail(original).bitmap.getPixel(40, 30))
            assertEquals(expected256, thumbnail(original, 256).bitmap.getPixel(100, 80))
            val after = FileVisualLoader.cacheCounters()
            assertEquals(before[1], after[1]) // No original decoding.
            assertEquals(before[0] + 2, after[0])
            assertTrue(disk().snapshot().entries >= 2)
        } finally { cleanup(root) }
    }

    @Test fun realCopyMoveFolderRenameAndPermanentDeleteInvalidateCorrectLocations() = runBlocking {
        val root = fixtureDirectory()
        try {
            val sourceDirectory = File(root, "source").apply { check(mkdir()) }
            val destination = File(root, "destination").apply { check(mkdir()) }
            val original = image(File(sourceDirectory, "image.png"), 400, 300)
            val bytes = original.readBytes()
            val originalVisual = thumbnail(original)
            assertEquals(ThumbnailSources.local(original.canonicalPath), originalVisual.sourceIdentity)
            app.graph.localFileOperator.copyOrMove(listOf(original.path), destination.path, false,
                ConflictPolicy.KEEP_BOTH, OperationContext.background())
            assertArrayEquals(bytes, original.readBytes())
            val before = FileVisualLoader.cacheCounters()
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            thumbnail(original)
            assertEquals(before[1], FileVisualLoader.cacheCounters()[1])
            val copied = File(destination, original.name)
            thumbnail(copied)
            val copiedKey = key(copied)
            app.graph.localFileOperator.copyOrMove(listOf(copied.path), sourceDirectory.path, true,
                ConflictPolicy.KEEP_BOTH, OperationContext.background())
            assertFalse(copied.exists())
            assertNull(FileVisualLoader.peek(copiedKey))
            assertFalse(disk().contains(copiedKey))
            val originalKey = key(original)
            val renamed = repository.rename(sourceDirectory.path, "renamed").getOrThrow()
            assertNull(FileVisualLoader.peek(originalKey))
            assertFalse(disk().contains(originalKey))
            val movedOriginal = File(renamed.absolutePath, original.name)
            assertArrayEquals(bytes, movedOriginal.readBytes())
            thumbnail(movedOriginal)
            val movedKey = key(movedOriginal)
            app.graph.localFileOperator.deletePermanently(listOf(movedOriginal.path), OperationContext.background())
            assertFalse(movedOriginal.exists())
            assertNull(FileVisualLoader.peek(movedKey))
            assertFalse(disk().contains(movedKey))
        } finally { cleanup(root) }
    }

    @Test fun trashRestoreEmptyAndStandaloneRetentionRepositoryRemoveStoredThumbnails() = runBlocking {
        val root = fixtureDirectory()
        val trashRoot = File(root, "trash").apply { check(mkdir()) }
        val trash = TrashRepository(app, configuredRoot = trashRoot, onMutation = app.graph.sharedStorageIndex::changed)
        try {
            val original = image(File(root, "image.png"), 400, 300)
            val bytes = original.readBytes()
            thumbnail(original)
            val originalKey = key(original)
            trash.moveToTrash(listOf(original.path), OperationContext.background())
            assertNull(FileVisualLoader.peek(originalKey))
            assertFalse(disk().contains(originalKey))
            val item = trash.list().single()
            val stored = File(item.storedPath)
            // Trash payload names have no image extension; the UI supplies the original kind.
            val entry = repository.toEntry(stored).copy(kind = EntryKind.IMAGE)
            val storedKey = key(stored, kind = EntryKind.IMAGE)
            assertNotNull(FileVisualLoader.loadLocal(app, entry, 96, 96, true))
            trash.restore(item.id).getOrThrow()
            assertArrayEquals(bytes, original.readBytes())
            assertNull(FileVisualLoader.peek(storedKey))
            assertFalse(disk().contains(storedKey))
            trash.moveToTrash(listOf(original.path), OperationContext.background())
            val second = trash.list().single()
            val secondStored = File(second.storedPath)
            assertNotNull(FileVisualLoader.loadLocal(app, repository.toEntry(secondStored).copy(kind = EntryKind.IMAGE), 96, 96, true))
            val secondKey = key(secondStored, kind = EntryKind.IMAGE)
            // Same constructor path used by the background TrashRetentionWorker, not AppGraph.
            TrashRepository(app, configuredRoot = trashRoot).emptyAll()
            assertFalse(secondStored.exists())
            assertNull(FileVisualLoader.peek(secondKey))
            assertFalse(disk().contains(secondKey))
        } finally { cleanup(root) }
    }

    @Test fun providerImageUsesDiskAndContentDeletionInvalidatesItsProvider() = runBlocking {
        val root = fixtureDirectory()
        try {
            val original = image(File(root, "provider.png"), 400, 300)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", original).toString()
            val entry = SafEntry(uri, original.name, false, original.length(), original.lastModified(), "image/png", EntryKind.IMAGE, true)
            assertNotNull(FileVisualLoader.loadSaf(app, entry, 96, 96))
            val before = FileVisualLoader.cacheCounters()
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            assertNotNull(FileVisualLoader.loadSaf(app, entry, 96, 96))
            assertEquals(before[0] + 1, FileVisualLoader.cacheCounters()[0])
            assertEquals(before[1], FileVisualLoader.cacheCounters()[1])
            ContentFileRepository(app).delete(uri).getOrThrow()
            assertFalse(original.exists())
            val key = "saf|$uri|${entry.modifiedAtMillis}|${entry.sizeBytes}|96|96"
            assertNull(FileVisualLoader.peek(key))
            assertFalse(disk().contains(key))
        } finally { cleanup(root) }
    }

    @Test fun missingProviderRevisionIsNotPersisted() = runBlocking {
        val root = fixtureDirectory()
        try {
            val original = image(File(root, "provider-unknown.png"), 400, 300)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", original).toString()
            val entry = SafEntry(uri, original.name, false, original.length(), 0L, "image/png", EntryKind.IMAGE, true)
            assertNotNull(FileVisualLoader.loadSaf(app, entry, 96, 96))
            val before = FileVisualLoader.cacheCounters()
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            assertNotNull(FileVisualLoader.loadSaf(app, entry, 96, 96))
            assertEquals(before[0], FileVisualLoader.cacheCounters()[0])
            assertEquals(before[1] + 1, FileVisualLoader.cacheCounters()[1])
        } finally { cleanup(root) }
    }

    @Test fun temporaryPreviewDoesNotLeaveAPersistentCopyOfItsContent() = runBlocking {
        val root = File(app.cacheDir, "owned-temporary-preview-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val original = image(File(root, "preview.png"), 400, 300)
            val before = FileVisualLoader.cacheCounters()
            thumbnail(original)
            assertEquals(before[2], FileVisualLoader.cacheCounters()[2])
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            val second = FileVisualLoader.cacheCounters()
            thumbnail(original)
            assertEquals(second[0], FileVisualLoader.cacheCounters()[0])
            assertEquals(second[1] + 1, FileVisualLoader.cacheCounters()[1])
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", original).toString()
            val entry = SafEntry(uri, original.name, false, original.length(), original.lastModified(), "image/png", EntryKind.IMAGE, true)
            assertNotNull(FileVisualLoader.loadSaf(app, entry, 96, 96))
            assertEquals(before[2], FileVisualLoader.cacheCounters()[2])
        } finally { cleanup(root) }
    }

    @Test fun corruptDiskEntryRegeneratesAndKeepsOriginal() = runBlocking {
        val root = fixtureDirectory()
        try {
            val original = image(File(root, "corruption.png"), 400, 300)
            val bytes = original.readBytes()
            thumbnail(original)
            val cacheFiles = File(app.cacheDir, "af-thumbnails-v1").listFiles()!!.filter { it.name.endsWith(".thumb") }
            // In this isolated case only this fixture's persisted record is relevant.
            val record = cacheFiles.first { it.readBytes().toString(Charsets.ISO_8859_1).contains(original.path) }
            val broken = record.readBytes()
            broken[broken.lastIndex] = (broken.last().toInt() xor 1).toByte()
            record.writeBytes(broken)
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            val before = FileVisualLoader.cacheCounters()
            assertNotNull(thumbnail(original))
            assertEquals(before[1] + 1, FileVisualLoader.cacheCounters()[1])
            assertArrayEquals(bytes, original.readBytes())
            assertFalse(File(app.cacheDir, "af-thumbnails-v1").listFiles()!!.any { it.name.endsWith(".pending") })
        } finally { cleanup(root) }
    }

    @Test fun processRestartReusesDiskWithoutOriginalDecode(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("thumbnailRestartPhase", "in-process")
        val root = File(app.filesDir, "owned-thumbnail-process-restart").apply { mkdirs() }
        val file = File(root, "image.png")
        try {
            if (phase != "verify") {
                image(file)
                thumbnail(file)
                FileVisualLoader.resetMemoryAndDiskIndexForTests()
            } else check(file.isFile) { "Restart preparation is missing" }
            val before = FileVisualLoader.cacheCounters()
            thumbnail(file)
            val after = FileVisualLoader.cacheCounters()
            assertEquals(before[1], after[1])
            assertEquals(before[0] + 1, after[0])
            Log.i("AFThumbnailTest", "AF_THUMB_RESTART_${phase.uppercase()}_OK")
        } finally { if (phase != "prepare") cleanup(root) }
    }

    @Test fun cachedGalleryAvoidsAllOriginalDecodesAndReportsElapsedTime(): Unit = runBlocking {
        val root = fixtureDirectory()
        try {
            val originals = (0 until 20).map { image(File(root, "image-$it.png"), 1_200, 900) }
            val startCold = System.nanoTime()
            originals.forEach { thumbnail(it, 160) }
            val coldMillis = (System.nanoTime() - startCold) / 1_000_000L
            FileVisualLoader.resetMemoryAndDiskIndexForTests()
            val before = FileVisualLoader.cacheCounters()
            val startCached = System.nanoTime()
            originals.forEach { thumbnail(it, 160) }
            val cachedMillis = (System.nanoTime() - startCached) / 1_000_000L
            val after = FileVisualLoader.cacheCounters()
            assertEquals(before[1], after[1])
            assertEquals(before[0] + originals.size, after[0])
            assertTrue(disk().snapshot().bytes <= ThumbnailDiskCache.MAX_BYTES)
            Log.i("AFThumbnailTest", "AF_THUMB_GALLERY cold_ms=$coldMillis cached_ms=$cachedMillis disk_hits=20 original_decodes=0")
        } finally { cleanup(root) }
    }

    private fun key(file: File, kind: EntryKind = EntryKind.IMAGE): String {
        val entry = repository.toEntry(file).copy(kind = kind)
        return FileVisualRules.localCacheKey(entry.absolutePath, entry.modifiedAtMillis, entry.sizeBytes,
            entry.kind, entry.extension, 96, 96, true)
    }
}
