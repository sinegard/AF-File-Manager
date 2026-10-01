package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NearbyStagingCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedConcurrentCancellationRemovesOnlyThePrivateCopy() {
        val root = temporary.newFolder("staging")
        val original = temporary.newFile("original.txt").apply { writeText("keep me") }
        val stage = File(root, "batch").apply { mkdirs() }
        original.copyTo(File(stage, "copy.txt"))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..20).map { executor.submit<Boolean> { NearbyStagingCleanup.delete(root, stage.path) } }
            tasks.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertFalse(stage.exists())
            assertEquals("keep me", original.readText())
            assertFalse(NearbyStagingCleanup.delete(root, original.path))
            assertFalse(NearbyStagingCleanup.delete(root, root.path))
        } finally { executor.shutdownNow() }
    }

    @Test fun nestedSymbolicLinksNeverDeleteTheirTargets() {
        val root = temporary.newFolder("staging")
        val outside = temporary.newFolder("originals")
        val original = File(outside, "important.txt").apply { writeText("keep me") }
        val stage = File(root, "batch").apply { mkdirs() }
        try { Files.createSymbolicLink(File(stage, "link").toPath(), outside.toPath()) }
        catch (unavailable: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException(unavailable) }
        assertTrue(NearbyStagingCleanup.delete(root, stage.path))
        assertFalse(stage.exists())
        assertEquals("keep me", original.readText())
    }
}
