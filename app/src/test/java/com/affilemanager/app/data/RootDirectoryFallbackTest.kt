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

        val detected = RootDirectoryFallback.existingChildren(root).map { it.name }

        assertEquals(setOf("data", "system", "vendor", "init.rc"), detected.toSet())
        assertFalse("vendor-specific-unknown" in detected)
    }

    @Test
    fun doesNotInventEntriesForAnEmptyDirectory() {
        assertEquals(emptyList<File>(), RootDirectoryFallback.existingChildren(temporary.newFolder("empty")))
    }
}
