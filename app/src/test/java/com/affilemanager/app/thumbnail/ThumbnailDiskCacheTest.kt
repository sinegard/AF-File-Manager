package com.affilemanager.app.thumbnail

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ThumbnailDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cache(bytes: Long = ThumbnailDiskCache.MAX_BYTES, count: Int = ThumbnailDiskCache.MAX_ENTRIES) =
        ThumbnailDiskCache(File(temporary.root, "private-thumbnails"), bytes, count)
    private fun record(size: Int = 100) = ThumbnailDiskCache.Record(ByteArray(size) { (it % 251).toByte() }, true, true)
    private fun local(name: String) = ThumbnailSources.local(File(temporary.root, name).path)

    @Test fun reopensWithoutRecreatingContentAndUsesOpaqueNames() {
        val first = cache()
        assertTrue(first.write("../../a/photo|ž.png|96", local("a/photo ž.png"), "10:200", record()))
        val reopened = cache()
        assertArrayEquals(record().bytes, reopened.read("../../a/photo|ž.png|96", local("a/photo ž.png"), "10:200")!!.bytes)
        assertTrue(first.directory.listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.thumb")) })
    }

    @Test fun newRevisionRemovesAllOldSizesButNotAnotherFile() {
        val disk = cache()
        disk.write("one96", local("one.png"), "old", record())
        disk.write("one256", local("one.png"), "old", record())
        disk.write("two", local("two.png"), "old", record())
        disk.write("one-new", local("one.png"), "new", record())
        assertNull(disk.read("one96", local("one.png"), "old"))
        assertNull(disk.read("one256", local("one.png"), "old"))
        assertNotNull(disk.read("two", local("two.png"), "old"))
        assertEquals(2, disk.snapshot().entries)
    }

    @Test fun deletingOrMovingFolderInvalidatesDescendantsNotSimilarlyNamedSibling() {
        val disk = cache()
        disk.write("child", local("photos/sub/one.png"), "r", record())
        disk.write("sibling", local("photos-other/one.png"), "r", record())
        disk.invalidateLocal(listOf(File(temporary.root, "photos").path))
        assertNull(disk.read("child", local("photos/sub/one.png"), "r"))
        assertNotNull(disk.read("sibling", local("photos-other/one.png"), "r"))
        assertEquals(1, cache().snapshot().entries)
    }

    @Test fun copyKeepsOriginalWhileMoveRemovesOnlyOldLocation() {
        val disk = cache()
        disk.write("original", local("original.png"), "r", record())
        disk.write("copied", local("copy.png"), "r", record())
        disk.invalidateLocal(listOf(File(temporary.root, "copy.png").path))
        assertNotNull(disk.read("original", local("original.png"), "r"))
        disk.invalidateLocal(listOf(File(temporary.root, "original.png").path))
        assertEquals(0, disk.snapshot().entries)
    }

    @Test fun pruneAfterExternalDeletionOrModificationPreservesCurrentSource() {
        val original = temporary.newFile("source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val current = temporary.newFile("current.png").apply { writeBytes(byteArrayOf(1)) }
        val disk = cache()
        disk.write("old", local(original.name), "${original.lastModified()}:${original.length()}", record())
        disk.write("current", local(current.name), "${current.lastModified()}:${current.length()}", record())
        original.appendBytes(byteArrayOf(4))
        disk.pruneMissingLocalSources()
        assertFalse(disk.contains("old"))
        assertEquals(1, disk.snapshot().entries)
        assertTrue(current.delete())
        cache().pruneMissingLocalSources()
        assertEquals(0, cache().snapshot().entries)
    }

    @Test fun countLruEvictsLeastRecentlyUsedOnly() {
        val disk = cache(count = 2)
        disk.write("one", local("one"), "r", record())
        disk.write("two", local("two"), "r", record())
        assertNotNull(disk.read("one", local("one"), "r"))
        disk.write("three", local("three"), "r", record())
        assertNull(disk.read("two", local("two"), "r"))
        assertNotNull(disk.read("one", local("one"), "r"))
        assertEquals(2, disk.snapshot().entries)
    }

    @Test fun byteBudgetSurvivesReopen() {
        val disk = cache(bytes = 7_000)
        repeat(8) { disk.write("key$it", local("source$it"), "r", record(3_000)) }
        assertTrue(disk.snapshot().bytes <= 7_000)
        assertEquals(2, cache(bytes = 7_000).snapshot().entries)
    }

    @Test fun obsoleteInflightResultCannotBeCommitted() {
        val disk = cache()
        assertFalse(disk.write("key", local("source"), "r", record()) { false })
        assertEquals(0, disk.snapshot().entries)
        var calls = 0
        assertFalse(disk.write("key", local("source"), "r", record()) { ++calls == 1 })
        assertTrue(disk.directory.listFiles()!!.isEmpty())
    }

    @Test fun corruptOrTruncatedPayloadIsNotAHit() {
        val disk = cache()
        disk.write("key", local("source"), "r", record())
        val file = disk.directory.listFiles()!!.single()
        val data = file.readBytes()
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        file.writeBytes(data)
        assertNull(cache().read("key", local("source"), "r"))
        disk.write("key", local("source"), "r", record())
        file.writeBytes(file.readBytes().dropLast(3).toByteArray())
        assertNull(cache().read("key", local("source"), "r"))
    }

    @Test fun startupCleansOnlyRecognizedPartialAndInvalidRecords() {
        val disk = cache()
        disk.snapshot()
        val sentinel = File(disk.directory, "not-cache-data.txt").apply { writeText("keep") }
        File(disk.directory, "${"a".repeat(64)}-00000000-0000-0000-0000-000000000000.pending").writeText("partial")
        File(disk.directory, "${"b".repeat(64)}.thumb").writeText("wrong version")
        assertEquals(0, cache().snapshot().entries)
        assertEquals(listOf(sentinel), disk.directory.listFiles()!!.toList())
        assertEquals("keep", sentinel.readText())
    }

    @Test fun documentDeletionInvalidatesItsProviderOnly() {
        val disk = cache()
        val one = ThumbnailSources.document("content://one/tree/root/document/image")
        val two = ThumbnailSources.document("content://two/document/image")
        disk.write("one", one, "r", record())
        disk.write("two", two, "r", record())
        disk.write("local", local("image"), "r", record())
        disk.invalidateDocuments(listOf("content://one/tree/root/document/folder"))
        assertNull(disk.read("one", one, "r"))
        assertNotNull(disk.read("two", two, "r"))
        assertEquals(2, disk.snapshot().entries)
    }

    @Test fun badCacheLocationDoesNotTouchAnOriginalFile() {
        val original = temporary.newFile("original").apply { writeText("original bytes") }
        try {
            ThumbnailDiskCache(original).write("key", local("source"), "r", record())
            fail("A file cannot be the cache directory")
        } catch (_: IOException) { }
        assertEquals("original bytes", original.readText())
    }

    @Test fun oversizedPayloadIsRejectedWithoutARecord() {
        val disk = cache()
        assertFalse(disk.write("key", local("source"), "r", record(ThumbnailDiskCache.MAX_PAYLOAD_BYTES + 1)))
        assertEquals(0, disk.snapshot().entries)
    }

    @Test fun largeMetadataIsBoundedDuringWritesAndStartup() {
        val disk = cache()
        repeat(300) { disk.write("$it-${"x".repeat(7_500)}", local("source$it"), "r", record()) }
        assertTrue(disk.snapshot().indexBytes <= ThumbnailDiskCache.MAX_INDEX_BYTES)
        val reopened = cache()
        assertTrue(reopened.snapshot().indexBytes <= ThumbnailDiskCache.MAX_INDEX_BYTES)
        assertEquals(disk.snapshot().entries, reopened.snapshot().entries)
    }
}
