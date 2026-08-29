package com.affilemanager.app.ui.components

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVisualRulesTest {
    @Test
    fun unreadableEntriesUseAnAccessLockBadge() {
        assertTrue(FileVisualRules.showAccessLock(isReadable = false))
        assertFalse(FileVisualRules.showAccessLock(isReadable = true))
    }

    @Test
    fun removableBadgesOnlyApplyInsideTheActualVolume() {
        val roots = listOf(
            storageRoot("/storage/emulated/0", StorageRootKind.INTERNAL),
            storageRoot("/storage/ABCD-1234", StorageRootKind.SD_CARD),
            storageRoot("/storage/USB/drive", StorageRootKind.USB_STORAGE),
        )

        assertEquals(StorageRootKind.SD_CARD, StorageLocationBadgeRules.kindForPath("/storage/ABCD-1234/DCIM/photo.jpg", roots))
        assertEquals(StorageRootKind.USB_STORAGE, StorageLocationBadgeRules.kindForPath("/storage/USB/drive/movie.mp4", roots))
        assertEquals(null, StorageLocationBadgeRules.kindForPath("/storage/emulated/0/DCIM/photo.jpg", roots))
        assertEquals(null, StorageLocationBadgeRules.kindForPath("/storage/ABCD-12345/not-the-card.txt", roots))
    }

    @Test
    fun longestContainingRemovableRootWins() {
        val roots = listOf(
            storageRoot("/storage/removable", StorageRootKind.REMOVABLE),
            storageRoot("/storage/removable/usb", StorageRootKind.USB_STORAGE),
        )

        assertEquals(
            StorageRootKind.USB_STORAGE,
            StorageLocationBadgeRules.kindForPath("/storage/removable/usb/file.txt", roots),
        )
    }

    @Test
    fun codeVectorAndSmilFilesUseDistinctIconFamilies() {
        assertEquals(FileIconFamily.CODE, FileVisualRules.iconFamily(EntryKind.DOCUMENT, "xml"))
        assertEquals(FileIconFamily.CODE, FileVisualRules.iconFamily(EntryKind.DOCUMENT, "lua"))
        assertEquals(FileIconFamily.VECTOR_IMAGE, FileVisualRules.iconFamily(EntryKind.IMAGE, "svg"))
        assertEquals(FileIconFamily.PRESENTATION, FileVisualRules.iconFamily(EntryKind.DOCUMENT, "smil"))
        assertEquals(FileIconFamily.ARCHIVE, FileVisualRules.iconFamily(EntryKind.ARCHIVE, "zip"))
    }

    @Test
    fun fitWithinPreservesAspectRatioAndDoesNotUpscale() {
        assertEquals(128 to 96, FileVisualRules.fitWithin(4_000, 3_000, 128, 128))
        assertEquals(40 to 20, FileVisualRules.fitWithin(40, 20, 128, 128))
    }

    @Test
    fun targetDimensionsAndSamplingAreBounded() {
        assertEquals(32, FileVisualRules.boundedDimension(1))
        assertEquals(512, FileVisualRules.boundedDimension(4_096))
        assertEquals(8, FileVisualRules.sampleSize(4_000, 3_000, 200, 200))
    }

    @Test
    fun extensionBadgeIsShortAndStable() {
        assertEquals("JPEG", FileVisualRules.extensionBadge(".jpeg"))
        assertEquals("", FileVisualRules.extensionBadge(""))
    }

    @Test
    fun richPreviewTypesAreExplicit() {
        assertTrue(FileVisualRules.hasContentThumbnail(EntryKind.IMAGE, "jpg"))
        assertTrue(FileVisualRules.hasContentThumbnail(EntryKind.DOCUMENT, "pdf"))
        assertFalse(FileVisualRules.hasContentThumbnail(EntryKind.DOCUMENT, "docx"))
        assertFalse(FileVisualRules.hasContentThumbnail(EntryKind.ARCHIVE, "zip"))
    }

    @Test
    fun ordinaryIconsShareATypeKeyWhileContentPreviewsRemainFileSpecific() {
        fun key(path: String, kind: EntryKind, extension: String, thumbnails: Boolean) =
            FileVisualRules.localCacheKey(path, 100L, 200L, kind, extension, 96, 96, thumbnails)

        assertEquals(
            key("/one/a.txt", EntryKind.DOCUMENT, "txt", false),
            key("/two/b.txt", EntryKind.DOCUMENT, "txt", false),
        )
        assertEquals(
            key("/one/a.jpg", EntryKind.IMAGE, "jpg", false),
            key("/two/b.jpg", EntryKind.IMAGE, "jpg", false),
        )
        assertNotEquals(
            key("/one/a.jpg", EntryKind.IMAGE, "jpg", true),
            key("/two/b.jpg", EntryKind.IMAGE, "jpg", true),
        )
        assertNotEquals(
            key("/one/a.apk", EntryKind.APK, "apk", false),
            key("/two/b.apk", EntryKind.APK, "apk", false),
        )
    }

    private fun storageRoot(path: String, kind: StorageRootKind) = StorageRoot(
        id = path,
        title = path,
        path = path,
        totalBytes = 0L,
        freeBytes = 0L,
        removable = kind != StorageRootKind.INTERNAL,
        kind = kind,
    )
}
