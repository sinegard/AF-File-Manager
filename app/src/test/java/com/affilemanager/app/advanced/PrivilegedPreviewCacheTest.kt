package com.affilemanager.app.advanced

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivilegedPreviewCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val entry = FileEntry("/private/song.ogg", "song.ogg", EntryKind.AUDIO, 4, 1, false, true, false)

    @Test fun newPreviewDoesNotDeleteAnOwnedCopyAndCleanupStaysInsideCache() {
        val cache = PrivilegedPreviewCache(temporary.root)
        val first = cache.createDestination(entry).apply { writeText("one!") }
        val second = cache.createDestination(entry).apply { writeText("two!") }
        assertEquals("one!", first.readText())
        assertNotEquals(first, second)
        assertTrue(runCatching { cache.createDestination(entry) }.isFailure)
        assertTrue(cache.discard(first))
        assertFalse(first.exists())
        assertTrue(second.exists())
        val unrelated = temporary.newFolder("unrelated").resolve("keep.txt").apply { writeText("keep") }
        assertFalse(cache.discard(unrelated))
        assertTrue(unrelated.exists())
        assertTrue(cache.discard(second))
        assertNotNull(cache.createDestination(entry))
    }
}
