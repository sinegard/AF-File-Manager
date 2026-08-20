package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AfPlanPreflightTest {
    @Test
    fun keepBothReservesUniqueNamesForSourcesFromDifferentServers() = runTest {
        val storage = FakeAfStorage()
        val first = storage.file("/one/report.txt", "one", "one")
        val second = storage.file("/two/report.txt", "two", "two")
        val destination = storage.directory("/inbox", "target")
        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(first, "report.txt"), source(second, "report.txt")),
                destinations = listOf(AfDestinationRef(destination)),
            ),
            storage,
        )

        assertTrue(preview.blockers.toString(), preview.canRun)
        assertEquals(2, preview.projections.size)
        assertNotEquals(preview.projections[0].resolvedRootName, preview.projections[1].resolvedRootName)
        assertEquals("report (1).txt", preview.projections[1].resolvedRootName)
    }

    @Test
    fun nestedDifferentFileBlocksFolderMergeDuringPreview() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/source", "one")
        storage.file("/source/readme.txt", "new", "one")
        val destination = storage.directory("/target", "two")
        storage.directory("/target/source", "two")
        storage.file("/target/source/readme.txt", "old", "two")

        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(sourceRoot, "source")),
                destinations = listOf(AfDestinationRef(destination)),
                conflict = ConflictPolicy.MERGE,
                verification = TransferVerification.SHA256,
            ),
            storage,
        )

        assertFalse(preview.canRun)
        assertTrue(preview.blockers.any { it.contains("Different file content") })
    }

    @Test
    fun overlappingSourceTreesAndDestinationTreesAreRejected() = runTest {
        val storage = FakeAfStorage()
        val parent = storage.directory("/source")
        val child = storage.directory("/source/child")
        storage.file("/source/child/a.txt", "a")
        val destination = storage.directory("/target")
        val nestedDestination = storage.directory("/target/nested")

        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(parent, "source"), source(child, "child")),
                destinations = listOf(AfDestinationRef(destination), AfDestinationRef(nestedDestination)),
            ),
            storage,
        )

        assertFalse(preview.canRun)
        assertTrue(preview.blockers.any { it.startsWith("Source selections overlap") })
        assertTrue(preview.blockers.any { it.startsWith("Destination folders overlap") })
    }

    @Test
    fun aFileAlreadyIdenticalAtEveryRequiredDestinationCanStillBeSafelyMoved() = runTest {
        val storage = FakeAfStorage()
        val source = storage.file("/out/report.txt", "same", "phone")
        val destination = storage.directory("/in", "server")
        storage.file("/in/report.txt", "same", "server")

        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(source, "report.txt")),
                destinations = listOf(AfDestinationRef(destination)),
                verification = TransferVerification.SHA256,
                deleteSources = true,
            ),
            storage,
        )

        assertTrue(preview.blockers.toString(), preview.canRun)
        assertEquals(0, preview.readyCopies)
        assertEquals(AfPreflightDisposition.VERIFIED_IDENTICAL, preview.projections.single().disposition)
    }

    @Test
    fun replacingDataCannotUseSkipAndContinue() {
        val invalid = plan(
            sources = listOf(AfSourceRef(FakeAfStorage().location("/a"), "a")),
            destinations = listOf(AfDestinationRef(FakeAfStorage().location("/b"))),
            conflict = ConflictPolicy.REPLACE,
            failure = TransferFailurePolicy.SKIP_AND_CONTINUE,
        )
        val error = runCatching { invalid.normalized() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("stop-on-error"))
    }

    @Test
    fun tenThousandFileFolderProducesABoundedRunnablePreview() = runTest {
        val storage = FakeAfStorage()
        val sourceRoot = storage.directory("/large", "phone")
        repeat(10_000) { index ->
            storage.file("/large/file-${index.toString().padStart(5, '0')}.txt", byteArrayOf((index % 251).toByte()), "phone")
        }
        val destination = storage.directory("/backup", "server")

        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(sourceRoot, "large")),
                destinations = listOf(AfDestinationRef(destination)),
            ),
            storage,
        )

        assertTrue(preview.blockers.toString(), preview.canRun)
        assertEquals(10_001, preview.entries.size)
        assertEquals(1, preview.projections.size)
        assertEquals(10_000, preview.totalSourceBytes)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun insufficientPrivateStagingSpaceBlocksBeforeCopyStarts() = runTest {
        val storage = FakeAfStorage().apply {
            stagingBytes = AfWorkflowLimits.MIN_STAGING_RESERVE_BYTES + 3L
        }
        val source = storage.file("/out/video.bin", ByteArray(4), "phone")
        val destination = storage.directory("/backup", "server")

        val preview = AfPlanPreflight().preview(
            plan(
                sources = listOf(source(source, "video.bin")),
                destinations = listOf(AfDestinationRef(destination)),
            ),
            storage,
        )

        assertFalse(preview.canRun)
        assertTrue(preview.blockers.any { it.contains("staging space") })
        assertTrue(storage.installed.isEmpty())
    }

    private fun plan(
        sources: List<AfSourceRef>,
        destinations: List<AfDestinationRef>,
        conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        verification: TransferVerification = TransferVerification.SIZE,
        failure: TransferFailurePolicy = TransferFailurePolicy.STOP,
        deleteSources: Boolean = false,
    ) = AfPlanDefinition(
        id = "test-plan",
        name = "Test",
        sources = sources,
        destinations = destinations,
        conflictPolicy = conflict,
        verification = verification,
        failurePolicy = failure,
        deleteSourcesAfterVerifiedCopies = deleteSources,
    )

    private fun source(location: AfLocationRef, name: String) = AfSourceRef(location, name)
}
