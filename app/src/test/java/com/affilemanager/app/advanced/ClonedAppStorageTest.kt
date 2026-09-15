package com.affilemanager.app.advanced

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClonedAppStorageTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun locatesOnlyAnExistingNormalCloneFolder() {
        val root = temporary.newFolder("primary")
        assertNull(ClonedAppStorage.normalLocation(root))

        val twin = File(root, "Twin App data").apply { mkdir() }

        assertEquals(twin.canonicalFile, ClonedAppStorage.normalLocation(root)?.canonicalFile)
    }

    @Test
    fun offersTheEntryOnlyForKnownOemsOrARealFolder() {
        val root = temporary.newFolder("manufacturer")
        assertTrue(ClonedAppStorage.shouldOffer("Samsung", root))
        assertTrue(ClonedAppStorage.shouldOffer("HONOR", root))
        assertFalse(ClonedAppStorage.shouldOffer("Google", root))

        File(root, "DualApp").mkdir()
        assertTrue(ClonedAppStorage.shouldOffer("Google", root))
    }
}
