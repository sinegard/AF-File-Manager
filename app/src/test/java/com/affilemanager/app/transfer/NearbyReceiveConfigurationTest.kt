package com.affilemanager.app.transfer

import com.affilemanager.app.model.EntryKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NearbyReceiveConfigurationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun approvedTypePathsAreBoundedAndMustExistInsideTheOwnersStorage() {
        val root = temporary.newFolder("allowed")
        val images = root.resolve("images").apply { mkdir() }
        val config = NearbyReceiveConfiguration(5, mapOf(EntryKind.IMAGE to images.path))
        assertEquals(images.canonicalFile, config.approvedDestinations(listOf(root))[EntryKind.IMAGE])
        assertTrue(runCatching { config.approvedDestinations(listOf(temporary.newFolder("other"))) }.isFailure)
        assertTrue(runCatching { NearbyReceiveConfiguration(6) }.isFailure)
        assertTrue(runCatching { NearbyReceiveConfiguration(1, mapOf(EntryKind.DIRECTORY to root.path)) }.isFailure)
    }
    @Test fun routingPreservesRelativePathsAndNeverGrantsParentAccess() {
        val base = temporary.newFolder("base")
        val images = temporary.newFolder("images")
        val router = NearbyReceiveDestinations(base, mapOf(EntryKind.IMAGE to images))
        val (root, directory) = router.directory("Album/nested", "photo.png")
        assertEquals(images.canonicalFile, root)
        assertEquals(images.resolve("Album/nested").canonicalFile, directory)
        assertTrue(directory.isDirectory)
        assertTrue(runCatching { router.directory("../escape", "photo.png") }.isFailure)
        assertEquals(base.canonicalFile, router.directory("", "unknown.bin").first)
    }
    @Test fun pathsRoundTripWithoutCredentialsOrUnknownKinds() {
        val config = NearbyReceiveConfiguration(3, mapOf(EntryKind.DOCUMENT to "/storage/emulated/0/Documents"))
        assertEquals(config, NearbyReceiveConfiguration.parse(3, config.pathsJson()))
        assertTrue(runCatching { NearbyReceiveConfiguration.parse(1, "{\"OTHER\":\"/\"}") }.isFailure)
    }
}
