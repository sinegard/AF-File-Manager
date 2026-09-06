package com.affilemanager.app.apk

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SplitApkArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(vararg names: String) = temporary.newFile("${System.nanoTime()}.apks").apply {
        ZipOutputStream(outputStream()).use { zip -> names.forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write("part:$name".toByteArray()); zip.closeEntry()
        } }
    }
    @Test fun flatAndBundletoolPartsRemainOriginalAndNeverFollowArchivePaths() {
        val file = archive("splits/base-master.apk", "splits/base-en.apk", "main.obb")
        val plan = SplitApkArchive.plan(file)
        assertEquals(2, plan.parts.size)
        assertTrue(plan.hasExtraData)
        val output = temporary.newFile()
        SplitApkArchive.copyPart(file, plan.base, output)
        assertEquals("part:splits/base-master.apk", output.readText())
    }
    @Test fun universalIsAlreadyStandaloneAndOtherVariantsAreNotMixedIntoIt() {
        val file = archive("universal.apk", "splits/base-master.apk", "splits/base-en.apk")
        assertEquals(listOf("universal.apk"), SplitApkArchive.plan(file).parts.map { it.name })
    }
    @Test fun ambiguousVariantsTraversalAndMissingBaseAreRejected() {
        listOf(arrayOf("a/base.apk", "b/base.apk"), arrayOf("base.apk", "../outside.apk"),
            arrayOf("config.en.apk"), arrayOf("splits/base-master.apk", "standalones/standalone.apk")).forEach { names ->
            assertThrows(IllegalArgumentException::class.java) { SplitApkArchive.plan(archive(*names)) }
        }
    }
    @Test fun partCountAndChangedArchiveAreRejectedWithoutLeavingPartialCopies() {
        assertThrows(IllegalArgumentException::class.java) {
            SplitApkArchive.plan(archive("base.apk", *(1..SplitApkArchive.MAX_PARTS).map { "$it.apk" }.toTypedArray()))
        }
        val file = archive("base.apk")
        val output = temporary.newFile()
        val part = SplitApkArchive.plan(file).base.copy(bytes = 1)
        assertThrows(IllegalArgumentException::class.java) { SplitApkArchive.copyPart(file, part, output) }
        assertFalse(output.exists())
    }
    @Test fun cancelledPrivateStagingRemovesOnlyThePartialCopyAndPreservesTheArchive() {
        val file = archive("base.apk")
        val original = file.readBytes()
        val output = temporary.newFile()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SplitApkArchive.copyPart(file, SplitApkArchive.plan(file).base, output, checkActive = {
                throw kotlinx.coroutines.CancellationException()
            })
        }
        assertFalse(output.exists())
        assertArrayEquals(original, file.readBytes())
    }
}
