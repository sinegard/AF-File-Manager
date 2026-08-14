package com.affilemanager.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileTagRepositoryTest {
    @Test
    fun tagsPersistAndSupportHierarchicalIntersectionSearch() {
        withSandbox { directory ->
            val tagged = File(directory, "invoice.pdf").apply { writeText("invoice") }
            val storage = File(directory, "tags.json")

            FileTagRepository(storage).apply(
                paths = listOf(tagged.absolutePath),
                tags = setOf("Projektas/Dokumentai", "Svarbu"),
                rating = 5,
            )

            val restored = FileTagRepository(storage)
            val record = restored.snapshot().records.single()
            assertEquals(5, record.rating)
            assertEquals(setOf("Projektas/Dokumentai", "Svarbu"), record.tags)
            assertTrue(record.currentlyMatchesFile())
            assertEquals(setOf(tagged.canonicalPath), restored.pathsWithAll(setOf("Svarbu", "Projektas/Dokumentai")))
            assertTrue(restored.pathsWithAll(setOf("Nėra")).isEmpty())
        }
    }

    @Test
    fun exportAndImportUseAValidatedPortableFormat() {
        withSandbox { directory ->
            val tagged = File(directory, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val firstStorage = File(directory, "first.json")
            val first = FileTagRepository(firstStorage)
            first.apply(listOf(tagged.absolutePath), setOf("Nuotraukos/Šeima"), 4)
            val exportDirectory = File(directory, "export").apply { mkdir() }

            val exported = first.exportTo(exportDirectory)
            val imported = FileTagRepository(File(directory, "second.json")).importFrom(exported)

            assertTrue(exported.isFile)
            assertEquals(setOf("Nuotraukos/Šeima"), imported.records.single().tags)
            assertEquals(4, imported.records.single().rating)
        }
    }

    @Test
    fun corruptStorageIsQuarantinedInsteadOfOverwritten() {
        withSandbox { directory ->
            val storage = File(directory, "tags.json").apply { writeText("{not-json") }
            val repository = FileTagRepository(storage)

            val error = runCatching { repository.snapshot() }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertFalse(storage.exists())
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("tags.corrupt-") })
            assertTrue(repository.snapshot().records.isEmpty())
        }
    }

    private fun withSandbox(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "file-tags-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
