package com.affilemanager.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrashRepositoryBrowserTest {
    @Test
    fun browsesDirectoriesOneLevelAtATimeAndEmptiesAllTrackedItems() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val sourceRoot = File(requireNotNull(application.getExternalFilesDir("trash-browser-source")), "run-${System.nanoTime()}")
        val trashRoot = File(application.cacheDir, "trash-browser-${System.nanoTime()}")
        require(sourceRoot.mkdirs())
        try {
            val deletedDirectory = File(sourceRoot, "kelionė").apply { require(mkdir()) }
            File(deletedDirectory, "viršuje.txt").writeText("viršus")
            val nested = File(deletedDirectory, "nuotraukos").apply { require(mkdir()) }
            File(nested, "viduje.txt").writeText("vidus")
            val deletedFile = File(sourceRoot, "pastaba.txt").apply { writeText("pastaba") }
            val repository = TrashRepository(application, configuredRoot = trashRoot)

            repository.moveToTrash(
                listOf(deletedDirectory.absolutePath, deletedFile.absolutePath),
                OperationContext.background(),
            )

            assertFalse(deletedDirectory.exists())
            assertFalse(deletedFile.exists())
            val rootEntries = repository.browse(itemId = null).getOrThrow()
            assertEquals(listOf("kelionė", "pastaba.txt"), rootEntries.map(TrashBrowserEntry::name).sorted())

            val directoryEntry = rootEntries.first { it.name == "kelionė" }
            assertTrue(directoryEntry.topLevel)
            val directoryChildren = repository.browse(directoryEntry.itemId).getOrThrow()
            assertEquals(listOf("nuotraukos", "viršuje.txt"), directoryChildren.map(TrashBrowserEntry::name).sorted())
            assertFalse(directoryChildren.any { it.name == "viduje.txt" })

            val nestedEntry = directoryChildren.first { it.name == "nuotraukos" }
            val nestedChildren = repository.browse(directoryEntry.itemId, nestedEntry.relativePath).getOrThrow()
            assertEquals(listOf("viduje.txt"), nestedChildren.map(TrashBrowserEntry::name))

            val result = repository.emptyAll()
            assertEquals(2, result.deletedItems)
            assertEquals(0, result.failedItems)
            assertTrue(repository.list().isEmpty())
            assertTrue(repository.browse(itemId = null).getOrThrow().isEmpty())
            assertFalse(repository.storageState().hasStoredData)
        } finally {
            sourceRoot.deleteRecursively()
            trashRoot.deleteRecursively()
        }
    }

    @Test
    fun emptyAllRemovesInvisibleOrphanedAndInterruptedTrashData() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val sourceRoot = File(requireNotNull(application.getExternalFilesDir("trash-orphan-source")), "run-${System.nanoTime()}")
        val trashRoot = File(application.cacheDir, "trash-orphans-${System.nanoTime()}")
        require(sourceRoot.mkdirs())
        try {
            val trackedSource = File(sourceRoot, "tracked.bin").apply { writeBytes(ByteArray(257) { 7 }) }
            val repository = TrashRepository(application, configuredRoot = trashRoot)
            repository.moveToTrash(listOf(trackedSource.absolutePath), OperationContext.background())

            val orphanId = "orphan-${System.nanoTime()}"
            File(trashRoot, "$orphanId.payload").writeBytes(ByteArray(4_096) { 3 })
            File(trashRoot, "$orphanId.partial").writeBytes(ByteArray(2_048) { 5 })
            val corruptId = "corrupt-${System.nanoTime()}"
            File(trashRoot, "$corruptId.payload").writeBytes(ByteArray(1_024) { 9 })
            File(trashRoot, "$corruptId.json").writeText("{not valid trash metadata")
            File(trashRoot, "$corruptId.json.partial").writeText("unfinished")
            File(trashRoot, "legacy-residue.bin").writeBytes(ByteArray(512) { 1 })

            assertEquals(1, repository.list().size)
            assertTrue(repository.browse(itemId = null).getOrThrow().size == 1)
            val before = repository.storageState()
            assertTrue(before.hasStoredData)
            assertEquals(4, before.storedItemCount)

            val result = repository.emptyAll(OperationContext.background())

            assertEquals(4, result.deletedItems)
            assertEquals(0, result.failedItems)
            assertFalse(repository.storageState().hasStoredData)
            assertTrue(trashRoot.listFiles().orEmpty().isEmpty())
        } finally {
            sourceRoot.deleteRecursively()
            trashRoot.deleteRecursively()
        }
    }
}
