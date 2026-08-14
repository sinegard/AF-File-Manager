package com.affilemanager.app.search

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SearchFilters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSearchEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun searchHonorsSelectedRootAndDoesNotReturnTheRootItself() = runBlocking {
        val firstRoot = temporary.newFolder("first-root")
        val secondRoot = temporary.newFolder("second-root")
        File(firstRoot, "wanted.pdf").writeText("one")
        File(secondRoot, "wanted.pdf").writeText("two")

        val result = engine().search(listOf(firstRoot.absolutePath), SearchFilters(query = "wanted"))

        assertEquals(1, result.entries.size)
        assertTrue(result.entries.single().absolutePath.startsWith(firstRoot.absolutePath))
        assertFalse(result.entries.any { it.absolutePath == firstRoot.absolutePath })
    }

    @Test
    fun searchAcrossMultipleRootsCombinesStorageLocations() = runBlocking {
        val firstRoot = temporary.newFolder("multi-first")
        val secondRoot = temporary.newFolder("multi-second")
        File(firstRoot, "shared-one.txt").writeText("one")
        File(secondRoot, "shared-two.txt").writeText("two")

        val result = engine().search(
            listOf(firstRoot.absolutePath, secondRoot.absolutePath),
            SearchFilters(query = "shared"),
        )

        assertEquals(setOf("shared-one.txt", "shared-two.txt"), result.entries.map(FileEntry::name).toSet())
    }

    @Test
    fun searchCombinesKindSizeAndModifiedDateFilters() = runBlocking {
        val root = temporary.newFolder("filters")
        val recentLarge = File(root, "recent-large.pdf").apply {
            writeBytes(ByteArray(2_048))
            setLastModified(System.currentTimeMillis())
        }
        File(root, "small.pdf").apply { writeBytes(ByteArray(10)) }
        File(root, "recent-large.jpg").apply { writeBytes(ByteArray(2_048)) }

        val result = engine().search(
            listOf(root.absolutePath),
            SearchFilters(
                minBytes = 1_024,
                modifiedAfter = System.currentTimeMillis() - 60_000,
                kinds = setOf(EntryKind.DOCUMENT),
            ),
        )

        assertEquals(listOf(recentLarge.absolutePath), result.entries.map(FileEntry::absolutePath))
    }

    @Test
    fun searchSupportsAClosedModifiedDateInterval() = runBlocking {
        val root = temporary.newFolder("date-range")
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1_000L
        File(root, "recent.txt").apply { writeText("recent"); setLastModified(now - day) }
        val middle = File(root, "middle.txt").apply { writeText("middle"); setLastModified(now - 20L * day) }
        File(root, "old.txt").apply { writeText("old"); setLastModified(now - 90L * day) }

        val result = engine().search(
            listOf(root.absolutePath),
            SearchFilters(modifiedAfter = now - 30L * day, modifiedBefore = now - 7L * day),
        )

        assertEquals(listOf(middle.absolutePath), result.entries.map(FileEntry::absolutePath))
    }

    @Test
    fun hiddenDirectoryIsNotTraversedUnlessRequested() = runBlocking {
        val root = temporary.newFolder("hidden")
        val hidden = File(root, ".private").apply { mkdir() }
        File(hidden, "inside.txt").writeText("secret")

        val defaultResult = engine().search(listOf(root.absolutePath), SearchFilters(query = "inside"))
        val includedResult = engine().search(
            listOf(root.absolutePath),
            SearchFilters(query = "inside", includeHidden = true),
        )

        assertTrue(defaultResult.entries.isEmpty())
        assertEquals(1, includedResult.entries.size)
    }

    @Test
    fun resultLimitStopsEarlyAndReportsTruncation() = runBlocking {
        val root = temporary.newFolder("limited")
        repeat(8) { index -> File(root, "item-$index.txt").writeText(index.toString()) }
        val engine = FileSearchEngine(::entry, maxScannedEntries = 100, maxResults = 3)

        val result = engine.search(listOf(root.absolutePath), SearchFilters(query = "item"))

        assertEquals(3, result.entries.size)
        assertTrue(result.truncated)
        assertTrue(result.scannedEntries < 9)
    }

    @Test
    fun scanLimitReportsTruncationInsteadOfCrashingSearch() = runBlocking {
        val root = temporary.newFolder("scan-limited")
        repeat(8) { index -> File(root, "file-$index.txt").writeText(index.toString()) }
        val engine = FileSearchEngine(::entry, maxScannedEntries = 4, maxResults = 100)

        val result = engine.search(listOf(root.absolutePath), SearchFilters(query = "file"))

        assertTrue(result.truncated)
        assertEquals(4, result.scannedEntries)
    }

    @Test
    fun pathPredicateFiltersBeforeTheResultLimit() = runBlocking {
        val root = temporary.newFolder("predicate")
        repeat(20) { index -> File(root, "file-$index.txt").writeText(index.toString()) }
        val engine = FileSearchEngine(::entry, maxScannedEntries = 100, maxResults = 1)

        val result = engine.search(listOf(root.absolutePath), SearchFilters()) { it.name == "file-0.txt" }

        assertEquals(listOf("file-0.txt"), result.entries.map(FileEntry::name))
        assertTrue(result.scannedEntries > 1)
    }

    @Test
    fun analysisCalculatesDirectorySizesAndTypeDistribution() = runBlocking {
        val root = temporary.newFolder("analysis")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "photo.jpg").writeBytes(ByteArray(150))
        File(root, "notes.txt").writeBytes(ByteArray(30))

        val analysis = engine().analyze(listOf(root.absolutePath))

        assertEquals(180, analysis.totalBytes)
        assertEquals(180, analysis.largestDirectories.first { it.path == root.absolutePath }.sizeBytes)
        assertEquals(150, analysis.largestDirectories.first { it.path == nested.absolutePath }.sizeBytes)
        assertEquals(150, analysis.typeUsage.first { it.kind == EntryKind.IMAGE }.sizeBytes)
        assertEquals(30, analysis.typeUsage.first { it.kind == EntryKind.DOCUMENT }.sizeBytes)
    }

    private fun engine() = FileSearchEngine(::entry)

    private fun entry(file: File) = FileEntry(
        absolutePath = file.absolutePath,
        name = file.name,
        kind = FileSystemRules.detectKind(file),
        sizeBytes = if (file.isFile) file.length() else 0,
        modifiedAtMillis = file.lastModified(),
        isHidden = file.isHidden,
        isReadable = true,
        isWritable = true,
    )
}
