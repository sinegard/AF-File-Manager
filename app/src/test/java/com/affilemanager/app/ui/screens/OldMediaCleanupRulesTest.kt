package com.affilemanager.app.ui.screens

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.cleanup.OldMediaRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OldMediaCleanupRulesTest {
    @Test
    fun acceptsMediaAndScreenshotPathsButNotOrdinaryDocuments() {
        assertTrue(isOldMediaCleanupCandidate(entry("/Pictures/photo.jpg", EntryKind.IMAGE)))
        assertTrue(isOldMediaCleanupCandidate(entry("/Download/Screenshot_1.bin", EntryKind.OTHER)))
        assertTrue(isOldMediaCleanupCandidate(entry("/Pictures/Screenshots/capture.bin", EntryKind.OTHER)))
        assertFalse(isOldMediaCleanupCandidate(entry("/Documents/report.pdf", EntryKind.DOCUMENT)))
    }

    @Test
    fun requiresTheFileToBeAtLeastNinetyDaysOldForCleanup() {
        val now = 200L * 24L * 60L * 60L * 1_000L
        assertTrue(OldMediaRules.isOldCandidate(entry("/Pictures/old.jpg", EntryKind.IMAGE, now - 91L * 24L * 60L * 60L * 1_000L), now))
        assertFalse(OldMediaRules.isOldCandidate(entry("/Pictures/recent.jpg", EntryKind.IMAGE, now - 10L * 24L * 60L * 60L * 1_000L), now))
    }

    private fun entry(path: String, kind: EntryKind, modifiedAtMillis: Long = 1) = FileEntry(
        absolutePath = path,
        name = path.substringAfterLast('/'),
        kind = kind,
        sizeBytes = 1,
        modifiedAtMillis = modifiedAtMillis,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
