package com.affilemanager.app.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

class ReceivedFileCommitterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun normalCommitDoesNotCopyOrHashTheFileAgain() {
        val staging = temporary.newFile("normal.partial").apply { writeText("normal") }
        val target = temporary.root.resolve("normal.txt")
        ReceivedFileCommitter().commit(staging, target, staging.length(), {}, { throw AssertionError("Unexpected copy") })
        assertEquals("normal", target.readText())
        assertFalse(staging.exists())
    }

    @Test fun emptyFileCanUseTheCopyFallback() {
        val staging = temporary.newFile("empty.partial")
        val target = temporary.root.resolve("empty.txt")
        copyingCommitter().commit(staging, target, 0, {}, {})
        assertTrue(target.isFile)
        assertEquals(0L, target.length())
        assertTrue(staging.exists())
    }

    @Test fun existingDestinationIsNeverOverwrittenByEitherPath() {
        val staging = temporary.newFile("collision.partial").apply { writeText("received") }
        val target = temporary.newFile("collision.txt").apply { writeText("original") }
        listOf(ReceivedFileCommitter(), copyingCommitter()).forEach { committer ->
            expectFailure<IOException> {
                committer.commit(staging, target, staging.length(), {}, { throw AssertionError("Collision fallback") })
            }
            assertEquals("original", target.readText())
            assertEquals("received", staging.readText())
        }
    }

    @Test fun destinationCreatedDuringFallbackPlanningIsKept() {
        val staging = temporary.newFile("late.partial").apply { writeText("received") }
        val target = temporary.root.resolve("late.txt")
        expectFailure<FileAlreadyExistsException> {
            copyingCommitter().commit(staging, target, staging.length(), {}, { target.writeText("original") })
        }
        assertEquals("original", target.readText())
        assertEquals("received", staging.readText())
    }

    @Test fun ambiguousFailedMoveWithATargetIsNotAdoptedOrErased() {
        val staging = temporary.newFile("ambiguous.partial").apply { writeText("received") }
        val target = temporary.root.resolve("ambiguous.txt")
        expectFailure<IOException> {
            ReceivedFileCommitter { _, destination ->
                destination.writeText("incomple") // Even identical length is not proof of success.
                throw IOException("Move failed after creating a destination")
            }.commit(staging, target, staging.length(), {}, { throw AssertionError("Ambiguous fallback") })
        }
        assertEquals("incomple", target.readText())
        assertEquals("received", staging.readText())
    }

    @Test fun movedButFailedReturnIsNotAssumedToBeCommitted() {
        val staging = temporary.newFile("moved.partial").apply { writeText("received") }
        val target = temporary.root.resolve("moved.txt")
        expectFailure<IOException> {
            ReceivedFileCommitter { source, destination ->
                Files.move(source.toPath(), destination.toPath())
                throw IOException("Ambiguous move return")
            }.commit(staging, target, staging.length(), {}, { throw AssertionError("Ambiguous fallback") })
        }
        assertEquals("received", target.readText())
    }

    @Test fun cancellationBeforeCommitLeavesTheStageUntouched() {
        val staging = temporary.newFile("cancel.partial").apply { writeText("received") }
        val target = temporary.root.resolve("cancel.txt")
        expectFailure<CancellationException> {
            ReceivedFileCommitter { _, _ -> throw AssertionError("Cancelled move started") }
                .commit(staging, target, staging.length(), { throw CancellationException() }, {})
        }
        assertEquals("received", staging.readText())
        assertFalse(target.exists())
    }

    @Test fun cancellationDuringCopyRemovesOnlyTheNewIncompleteDestination() {
        val staging = temporary.newFile("mid-cancel.partial").apply { writeBytes(ByteArray(2_000_000) { 51 }) }
        val target = temporary.root.resolve("mid-cancel.txt")
        val original = temporary.newFile("unrelated.txt").apply { writeText("original") }
        expectFailure<CancellationException> {
            copyingCommitter().commit(staging, target, staging.length(), {
                if (target.length() > 0) throw CancellationException()
            }, {})
        }
        assertSafeIncompleteCleanup(target)
        assertEquals(2_000_000L, staging.length())
        assertEquals("original", original.readText())
    }

    @Test fun mismatchedStageSizeCannotBeCommitted() {
        val staging = temporary.newFile("short.partial").apply { writeText("short") }
        val target = temporary.root.resolve("short.txt")
        expectFailure<IOException> {
            ReceivedFileCommitter { _, _ -> throw AssertionError("Incomplete move started") }
                .commit(staging, target, 6, {}, {})
        }
        assertFalse(target.exists())
        assertEquals("short", staging.readText())
    }

    @Test fun equalSizeCorruptionIsRejectedByTheChecksum() {
        val staging = temporary.newFile("corrupt.partial").apply { writeText("received") }
        val target = temporary.root.resolve("corrupt.txt")
        var corrupted = false
        expectFailure<IOException> {
            copyingCommitter().commit(staging, target, staging.length(), {
                if (!corrupted && target.length() == staging.length()) {
                    target.writeText("modified")
                    corrupted = true
                }
            }, {})
        }
        assertTrue(corrupted)
        assertSafeIncompleteCleanup(target)
        assertEquals("received", staging.readText())
    }

    @Test fun concurrentReplacementIsNotDeletedDuringErrorCleanup() {
        val staging = temporary.newFile("replace.partial").apply { writeText("received") }
        val target = temporary.root.resolve("replace.txt")
        var replaced = false
        expectFailure<IOException> {
            copyingCommitter().commit(staging, target, staging.length(), {
                if (!replaced && target.length() > 0) {
                    val concurrent = temporary.newFile("other.tmp").apply { writeText("original") }
                    Files.move(concurrent.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    replaced = true
                }
            }, {})
        }
        assertTrue(replaced)
        assertEquals("original", target.readText())
        assertEquals("received", staging.readText())
    }

    @Test fun missingStageAfterFailedMoveCannotBeCopiedOrDeclaredComplete() {
        val staging = temporary.newFile("lost.partial").apply { writeText("received") }
        val target = temporary.root.resolve("lost.txt")
        expectFailure<IOException> {
            ReceivedFileCommitter { source, _ -> check(source.delete()); throw IOException("Move lost stage") }
                .commit(staging, target, 8, {}, { throw AssertionError("Missing stage fallback") })
        }
        assertFalse(target.exists())
    }

    private fun copyingCommitter() = ReceivedFileCommitter { _, _ -> throw IOException("Rename unavailable") }
    private fun assertSafeIncompleteCleanup(target: java.io.File) {
        if (!target.exists()) return
        // Windows' provider returns no fileKey. Keeping an unidentifiable inode
        // is safer than deleting a concurrent user's file. Android tests require
        // a stable key and assert removal without this portability branch.
        assertEquals(null, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey())
    }
    private inline fun <reified T : Exception> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Exception) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun renameDeniedStillCommitsCompleteBytesWithoutRepeatingTheUpload() {
        val staging = temporary.newFile(".upload.partial").apply {
            writeBytes(ByteArray(1_818_418) { (it * 31).toByte() })
        }
        val expected = staging.readBytes()
        val target = temporary.root.resolve("received.mp4")
        var fallbackCount = 0
        ReceivedFileCommitter { source, destination ->
            throw FileSystemException(source.path, destination.path, "Operation not permitted")
        }.commit(staging, target, expected.size.toLong(), {}, { fallbackCount++ })
        assertArrayEquals(expected, target.readBytes())
        assertEquals(1, fallbackCount)
        // The server, not the commit component, owns its temporary file lifecycle.
        assertArrayEquals(expected, staging.readBytes())
    }

    @Test fun failedCopyDoesNotEraseTheFullyReceivedStageOrClaimSuccess() {
        val staging = temporary.newFile(".second.partial").apply { writeBytes(ByteArray(4_509_722) { it.toByte() }) }
        val expected = staging.readBytes()
        val target = temporary.root.resolve("second.mp4")
        var fallback = false
        var copied = false
        try {
            ReceivedFileCommitter { _, _ -> throw IOException("Rename unavailable") }.commit(
                staging, target, expected.size.toLong(), checkpoint = {
                    if (fallback && target.length() > 0) {
                        copied = true
                        throw FileSystemException(null, null, "No space left on device")
                    }
                }, onFallback = { fallback = true },
            )
            throw AssertionError("Incomplete copy was accepted")
        } catch (_: FileSystemException) {
            assertTrue(copied)
            assertArrayEquals(expected, staging.readBytes())
            assertSafeIncompleteCleanup(target)
        }
    }
}
