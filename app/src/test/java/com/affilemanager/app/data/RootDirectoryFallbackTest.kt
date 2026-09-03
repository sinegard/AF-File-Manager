package com.affilemanager.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootDirectoryFallbackTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun returnsOnlyConfirmedCommonRootEntries() {
        val root = temporary.newFolder("root")
        listOf("data", "system", "vendor").forEach { name ->
            root.resolve(name).mkdir()
        }
        root.resolve("init.rc").writeText("test")
        root.resolve("vendor-specific-unknown").mkdir()

        val detected = RootDirectoryFallback.existingChildren(root, emptyList()).map { it.name }

        assertEquals(setOf("data", "system", "vendor", "init.rc"), detected.toSet())
        assertFalse("vendor-specific-unknown" in detected)
    }

    @Test
    fun doesNotInventEntriesForAnEmptyDirectory() {
        assertEquals(emptyList<File>(), RootDirectoryFallback.existingChildren(temporary.newFolder("empty"), emptyList()))
    }

    @Test
    fun discoversExistingVendorMountsWithoutTrustingArbitraryPaths() {
        val root = temporary.newFolder("mounted-root")
        root.resolve("vendor_runtime").mkdir()
        root.resolve("escaped").mkdir()
        val mountInfo = temporary.newFile("mountinfo").apply {
            writeText(
                "36 25 0:32 / /vendor_runtime/sub rw,nosuid - tmpfs tmpfs rw\n" +
                    "37 25 0:33 / /../../escaped rw,nosuid - tmpfs tmpfs rw\n",
            )
        }

        val detected = RootDirectoryFallback.existingChildren(root, listOf(mountInfo)).map { it.name }

        assertEquals(listOf("vendor_runtime"), detected)
    }
}
