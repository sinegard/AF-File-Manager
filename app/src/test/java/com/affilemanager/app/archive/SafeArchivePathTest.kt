package com.affilemanager.app.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeArchivePathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsContainedRelativePath() {
        val root = temporaryFolder.newFolder("out")
        val target = SafeArchivePath.resolve(root, "docs/ataskaita.txt")
        assertEquals("ataskaita.txt", target.name)
        assertTrue(target.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversal() {
        SafeArchivePath.resolve(temporaryFolder.root, "../../slaptas.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWindowsAbsolutePath() {
        SafeArchivePath.resolve(temporaryFolder.root, "C:\\Users\\failas.txt")
    }
}
