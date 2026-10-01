package com.affilemanager.app.transfer

import android.system.Os
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NearbyStagingCleanupAndroidTest {
    @Test fun concurrentCancellationKeepsOriginalsAndDoesNotFollowRealSymlinks() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixture = File(app.cacheDir, "cleanup-boundary-${UUID.randomUUID()}").apply { check(mkdir()) }
        val root = File(fixture, "staging").apply { check(mkdir()) }
        val originals = File(fixture, "originals").apply { check(mkdir()) }
        val original = File(originals, "keep.txt").apply { writeText("original must remain") }
        val stage = File(root, "batch").apply { check(mkdir()) }
        original.copyTo(File(stage, "copy.txt"))
        val link = File(stage, "outside")
        val executor = Executors.newFixedThreadPool(4)
        try {
            Os.symlink(originals.path, link.path)
            val tasks = (1..20).map { executor.submit<Boolean> { NearbyStagingCleanup.delete(root, stage.path) } }
            tasks.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertFalse(stage.exists())
            assertEquals("original must remain", original.readText())
            assertFalse(NearbyStagingCleanup.delete(root, original.path))
            assertFalse(NearbyStagingCleanup.delete(root, root.path))
        } finally {
            executor.shutdownNow()
            link.delete() // Never follow a fixture link during failure cleanup either.
            fixture.deleteRecursively()
        }
    }
}
