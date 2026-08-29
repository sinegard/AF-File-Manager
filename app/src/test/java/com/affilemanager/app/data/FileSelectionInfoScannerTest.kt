package com.affilemanager.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSelectionInfoScannerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun reportsFilesFoldersAndBytesForOneFolder() = runBlocking {
        val root = temporary.newFolder("root")
        File(root, "nested").mkdir()
        File(root, "one.txt").writeText("1234")
        File(root, "nested/two.txt").writeText("12")

        val summary = FileSelectionInfoScanner().scan(listOf(root.absolutePath))

        assertEquals(1, summary.selectedItems)
        assertEquals(2, summary.fileCount)
        assertEquals(1, summary.folderCount)
        assertEquals(6L, summary.totalBytes)
        assertTrue(summary.complete)
    }

    @Test
    fun nestedSelectedRootIsNotCountedTwice() = runBlocking {
        val root = temporary.newFolder("selected-root")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "only.txt").writeText("content")

        val summary = FileSelectionInfoScanner().scan(listOf(root.absolutePath, nested.absolutePath))

        assertEquals(2, summary.selectedItems)
        assertEquals(1, summary.fileCount)
        assertEquals(1, summary.folderCount)
        assertEquals(7L, summary.totalBytes)
    }

    @Test
    fun scanLimitReturnsAnExplicitPartialResult() = runBlocking {
        val root = temporary.newFolder("bounded")
        repeat(20) { index -> File(root, "$index.txt").writeText("x") }

        val summary = FileSelectionInfoScanner(maxScannedNodes = 5).scan(listOf(root.absolutePath))

        assertFalse(summary.complete)
        assertTrue(summary.scannedNodes <= 5)
        assertTrue(summary.fileCount <= 4)
    }
}
