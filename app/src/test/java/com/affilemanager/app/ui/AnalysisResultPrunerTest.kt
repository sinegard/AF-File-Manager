package com.affilemanager.app.ui

import com.affilemanager.app.model.DirectoryUsage
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SimilarImageGroup
import com.affilemanager.app.model.StorageAnalysis
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisResultPrunerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun removesMovedRootAndNestedCandidatesWithoutRescanningTotals() {
        val root = temporary.newFolder("analysis")
        val removedDirectory = File(root, "old")
        val removedFile = File(removedDirectory, "one.jpg")
        val keptA = File(root, "keep-a.jpg")
        val keptB = File(root, "keep-b.jpg")
        val state = AnalysisUiState(
            analysis = StorageAnalysis(
                scannedFiles = 3,
                scannedDirectories = 1,
                totalBytes = 300,
                largestFiles = listOf(entry(removedFile), entry(keptA)),
                oldestFiles = listOf(entry(removedFile), entry(keptB)),
                emptyDirectories = listOf(removedDirectory.absolutePath, File(root, "empty").absolutePath),
                truncated = false,
                largestDirectories = listOf(
                    DirectoryUsage(removedDirectory.absolutePath, 100, 1),
                    DirectoryUsage(root.absolutePath, 300, 3),
                ),
                installerAndArchiveFiles = listOf(entry(removedFile), entry(keptA)),
                similarImageCandidates = listOf(entry(removedFile), entry(keptA), entry(keptB)),
                oldMediaFiles = listOf(entry(removedFile), entry(keptB)),
            ),
            duplicates = listOf(
                DuplicateGroup("three", 100, listOf(removedFile.absolutePath, keptA.absolutePath, keptB.absolutePath)),
                DuplicateGroup("two", 100, listOf(removedFile.absolutePath, keptA.absolutePath)),
            ),
            similarImages = listOf(
                SimilarImageGroup("three", listOf(entry(removedFile), entry(keptA), entry(keptB))),
                SimilarImageGroup("two", listOf(entry(removedFile), entry(keptA))),
            ),
        )

        val pruned = AnalysisResultPruner.prune(state, listOf(removedDirectory.absolutePath))

        assertEquals(listOf(keptA.absolutePath), pruned.analysis?.largestFiles?.map(FileEntry::absolutePath))
        assertEquals(listOf(keptB.absolutePath), pruned.analysis?.oldestFiles?.map(FileEntry::absolutePath))
        assertEquals(3, pruned.analysis?.scannedFiles)
        assertEquals(300L, pruned.analysis?.totalBytes)
        assertEquals(listOf("three"), pruned.duplicates.map(DuplicateGroup::sha256))
        assertEquals(listOf(keptA.absolutePath, keptB.absolutePath), pruned.duplicates.single().paths)
        assertEquals(listOf("three"), pruned.similarImages.map(SimilarImageGroup::id))
        assertEquals(listOf(keptA.absolutePath, keptB.absolutePath), pruned.similarImages.single().files.map(FileEntry::absolutePath))
    }

    @Test
    fun emptyRemovalKeepsTheExistingSnapshotInstance() {
        val state = AnalysisUiState()
        assertSame(state, AnalysisResultPruner.prune(state, emptyList()))
    }

    private fun entry(file: File) = FileEntry(
        absolutePath = file.absolutePath,
        name = file.name,
        kind = EntryKind.IMAGE,
        sizeBytes = 100,
        modifiedAtMillis = 1,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
