package com.affilemanager.app.search

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarImageEngineTest {
    @Test
    fun groupsOnlyCloseHashesWithMatchingAspectRatio() {
        val engine = SimilarImageEngine()
        val groups = engine.group(
            listOf(
                fingerprint("/a.jpg", 0L, 1_600, 900),
                fingerprint("/b.jpg", 0b11L, 3_200, 1_800),
                fingerprint("/different.jpg", -1L, 1_600, 900),
                fingerprint("/portrait.jpg", 0L, 900, 1_600),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("/a.jpg", "/b.jpg"), groups.single().files.map(FileEntry::absolutePath))
    }

    private fun fingerprint(path: String, hash: Long, width: Int, height: Int) = ImageFingerprint(
        entry = FileEntry(path, path.substringAfterLast('/'), EntryKind.IMAGE, 100_000, 0, false, true, true),
        differenceHash = hash,
        width = width,
        height = height,
    )
}
