package com.affilemanager.app.network

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteDownloadStageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun eachDownloadOwnsItsStageAndPreservesAnUnrelatedHiddenFile() {
        val destination = File(temporary.root, "file.txt")
        val unrelated = File(temporary.root, ".file.txt.partial").apply { writeText("keep") }
        RemoteDownloadStage(destination).use { first ->
            RemoteDownloadStage(destination).use { second ->
                assertNotEquals(first.file, second.file)
                first.outputStream().use { it.write("remote".toByteArray()) }
                first.publish()
                assertEquals("remote", destination.readText())
                assertEquals("keep", unrelated.readText())
            }
        }
        assertEquals(setOf("file.txt", ".file.txt.partial"), temporary.root.list()!!.toSet())
    }

    @Test fun aFailedOrCancelledDownloadPreservesThePreviousDestination() {
        val destination = File(temporary.root, "file.txt").apply { writeText("original") }
        RemoteDownloadStage(destination).use { stage ->
            stage.outputStream().use { it.write("incomplete".toByteArray()) }
        }
        assertEquals("original", destination.readText())
        assertEquals(listOf("file.txt"), temporary.root.list()!!.toList())
    }

    @Test fun publishingReplacesThePreviousFileOnlyWhenReady() {
        val destination = File(temporary.root, "file.txt").apply { writeText("original") }
        RemoteDownloadStage(destination).use { stage ->
            stage.outputStream().use { it.write("verified".toByteArray()) }
            assertEquals("original", destination.readText())
            stage.publish()
        }
        assertEquals("verified", destination.readText())
    }

    @Test fun aDirectoryThatAppearsBeforePublicationSurvivesTheFailure() {
        val destination = File(temporary.root, "file.txt")
        RemoteDownloadStage(destination).use { stage ->
            stage.outputStream().use { it.write("verified".toByteArray()) }
            check(destination.mkdir())
            val unrelated = File(destination, "unselected.txt").apply { writeText("keep") }
            assertTrue(runCatching { stage.publish() }.isFailure)
            assertEquals("keep", unrelated.readText())
        }
    }

    @Test fun anExistingDirectoryIsNeverBorrowedAsAFileDestination() {
        val destination = temporary.newFolder("directory")
        assertTrue(runCatching { RemoteDownloadStage(destination) }.isFailure)
        assertTrue(destination.isDirectory)
        assertEquals(listOf("directory"), temporary.root.list()!!.toList())
    }
}
