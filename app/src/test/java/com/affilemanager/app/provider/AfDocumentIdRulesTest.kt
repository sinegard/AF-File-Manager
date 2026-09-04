package com.affilemanager.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AfDocumentIdRulesTest {
    @Test
    fun roundTripsUnicodeRelativePath() {
        val id = AfDocumentIdRules.encode("primary", "Pictures/Šeima/nuotrauka.jpg")
        assertEquals(AfDocumentId("primary", "Pictures/Šeima/nuotrauka.jpg"), AfDocumentIdRules.parse(id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversal() {
        AfDocumentIdRules.encode("primary", "../secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathsDeeperThanProviderLimit() {
        val path = List(AfDocumentIdRules.MAX_PATH_SEGMENTS + 1) { "folder" }.joinToString("/")
        AfDocumentIdRules.encode("primary", path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedEncodedDocumentIdBeforeDecoding() {
        AfDocumentIdRules.parse("primary:" + "A".repeat(13 * 1024))
    }

    @Test
    fun blocksProtectedAndroidTrees() {
        assertTrue(AfDocumentIdRules.isAllowedRelative("Android/media"))
        assertFalse(AfDocumentIdRules.isAllowedRelative("Android/data"))
        assertFalse(AfDocumentIdRules.isAllowedRelative("Android/obb/game"))
    }

    @Test
    fun resolvesOnlyInsideTheExportedRoot() {
        val root = createTempDirectory("af-provider-").toFile()
        try {
            val nested = File(root, "Documents/report.txt")
            nested.parentFile!!.mkdirs()
            nested.writeText("ok")
            val id = AfDocumentIdRules.encode("primary", "Documents/report.txt")
            assertEquals(nested.canonicalFile, AfDocumentIdRules.resolve(root, id))
        } finally {
            root.deleteRecursively()
        }
    }
}
