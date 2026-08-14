package com.affilemanager.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBrowserTest {
    @Test
    fun rootContainsOnlyImmediateChildrenAndSynthesizesMissingFolders() {
        val index = ArchiveBrowserIndex.from(
            listOf(
                entry("šaknis.txt", size = 12),
                entry("Nuotraukos/2026/vasara.jpg", size = 48),
                entry("Nuotraukos/žiema.jpg", size = 32),
            ),
        )

        val root = index.children("")
        assertEquals(listOf("Nuotraukos", "šaknis.txt"), root.map { it.name })
        assertTrue(root.first().directory)
        assertFalse(root.last().directory)

        assertEquals(listOf("2026", "žiema.jpg"), index.children("Nuotraukos").map { it.name })
        assertEquals(listOf("vasara.jpg"), index.children("Nuotraukos/2026").map { it.name })
    }

    @Test
    fun explicitDirectoriesMergeWithImplicitDirectoriesAndWindowsSeparators() {
        val index = ArchiveBrowserIndex.from(
            listOf(
                entry("Dokumentai/", directory = true),
                entry("Dokumentai\\Sutartys\\sutartis.pdf", size = 1_024),
                entry("Dokumentai/Sutartys/", directory = true),
            ),
        )

        assertEquals(listOf("Dokumentai"), index.children("").map { it.name })
        assertEquals(listOf("Sutartys"), index.children("Dokumentai").map { it.name })
        val file = index.children("Dokumentai/Sutartys").single()
        assertEquals("sutartis.pdf", file.name)
        assertEquals(1_024, file.sizeBytes)
        assertFalse(file.directory)
    }

    @Test
    fun parentMovesExactlyOneArchiveLevel() {
        assertEquals("", ArchiveBrowserIndex.parentOf("A"))
        assertEquals("A", ArchiveBrowserIndex.parentOf("A/B"))
        assertEquals("A/B", ArchiveBrowserIndex.parentOf("A/B/C"))
    }

    @Test
    fun pathsBeyondTheSafeDepthAreReportedInsteadOfIndexedWithoutBounds() {
        val tooDeep = (1..65).joinToString("/") { "lygis$it" } + "/failas.txt"
        val index = ArchiveBrowserIndex.from(listOf(entry(tooDeep, size = 10)))

        val warning = index.children("").single()
        assertFalse(warning.directory)
        assertTrue(warning.name.contains("1 archyvo įrašų nerodoma"))
    }

    private fun entry(name: String, directory: Boolean = false, size: Long = -1) = ArchiveEntryInfo(
        name = name,
        directory = directory,
        sizeBytes = size,
    )
}
