package com.affilemanager.app.ui.components

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryEntryFilterRulesTest {
    private val folder = FileEntry("/folder", "folder", EntryKind.DIRECTORY, 0, 0, false, true, true)
    private val image = FileEntry("/photo.jpg", "photo.jpg", EntryKind.IMAGE, 10, 0, false, true, true)

    @Test
    fun `inactive query and filter preserve the original list`() {
        val entries = listOf(folder, image)

        assertSame(entries, DirectoryEntryFilterRules.visibleEntries(entries, "  ", emptySet()))
    }

    @Test
    fun `active query and filter return only matching entries`() {
        val entries = listOf(folder, image)

        val result = DirectoryEntryFilterRules.visibleEntries(
            entries = entries,
            query = "PHOTO",
            selected = setOf(DirectoryEntryFilter.IMAGES),
        )

        assertSame(image, result.single())
    }

    @Test
    fun `empty selection shows every entry`() {
        EntryKind.entries.forEach { kind ->
            assertTrue(DirectoryEntryFilterRules.matches(kind, emptySet()))
        }
    }

    @Test
    fun `file selector includes every non-directory kind`() {
        val selected = setOf(DirectoryEntryFilter.FILES)

        assertFalse(DirectoryEntryFilterRules.matches(EntryKind.DIRECTORY, selected))
        EntryKind.entries.filterNot { it == EntryKind.DIRECTORY }.forEach { kind ->
            assertTrue(DirectoryEntryFilterRules.matches(kind, selected))
        }
    }

    @Test
    fun `specific selectors combine without including unrelated entries`() {
        val selected = setOf(DirectoryEntryFilter.FOLDERS, DirectoryEntryFilter.IMAGES, DirectoryEntryFilter.APKS)

        assertTrue(DirectoryEntryFilterRules.matches(EntryKind.DIRECTORY, selected))
        assertTrue(DirectoryEntryFilterRules.matches(EntryKind.IMAGE, selected))
        assertTrue(DirectoryEntryFilterRules.matches(EntryKind.APK, selected))
        assertFalse(DirectoryEntryFilterRules.matches(EntryKind.DOCUMENT, selected))
        assertFalse(DirectoryEntryFilterRules.matches(EntryKind.OTHER, selected))
    }
}
