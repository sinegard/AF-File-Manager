package com.affilemanager.app

import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.data.TrashRepository
import com.affilemanager.app.operations.LocalFileOperator
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** All link targets are our own private fixtures, never another app or a user folder. */
class DeletionLinkSafetyAndroidTest {
    @Test fun deletingAFolderDoesNotDeleteTheContentsOfItsLinkedOutsideFolder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "delete-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val selected = File(root, "selected").apply { check(mkdir()) }
        val outside = File(root, "not-selected").apply { check(mkdir()) }
        val payload = File(outside, "keep.txt").apply { writeText("unselected data must survive") }
        File(selected, "owned.txt").writeText("selected data")
        Files.createSymbolicLink(File(selected, "outside-link").toPath(), outside.toPath())
        try {
            LocalFileOperator().deletePermanently(listOf(selected.path), OperationContext.background())
            assertFalse(selected.exists())
            assertTrue("Deleting a link traversed into an unselected folder", payload.isFile)
            assertEquals("unselected data must survive", payload.readText())
        } finally {
            // An initial failing deletion might already have removed the payload.
            // This final fixture cleanup itself never follows a symbolic link.
            removeFixtureWithoutFollowingLinks(root)
        }
    }

    @Test fun deletingASelectedFileLinkUnlinksTheLinkAndKeepsTheReferencedFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "delete-file-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val payload = File(root, "not-selected.txt").apply { writeText("keep the actual file") }
        val selected = File(root, "selected-link")
        Files.createSymbolicLink(selected.toPath(), payload.toPath())
        try {
            LocalFileOperator().deletePermanently(listOf(selected.path), OperationContext.background())
            assertFalse("The selected link still exists", Files.exists(selected.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue("Deleting the link deleted its referenced file", payload.isFile)
            assertEquals("keep the actual file", payload.readText())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    @Test fun deletingABrokenLinkDoesNotFailOrSweepItsParent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "delete-broken-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val selected = File(root, "broken-link")
        Files.createSymbolicLink(selected.toPath(), File(root, "absent").toPath())
        try {
            LocalFileOperator().deletePermanently(listOf(selected.path), OperationContext.background())
            assertFalse(Files.exists(selected.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", unrelated.readText())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    @Test fun trashRejectsASelectedFileLinkWithoutMovingItsUnselectedTarget() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "trash-file-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val payload = File(root, "not-selected.txt").apply { writeText("keep in the original location") }
        val selected = File(root, "selected-link")
        Files.createSymbolicLink(selected.toPath(), payload.toPath())
        val trash = TrashRepository(app, File(root, "trash"), onCacheMutation = {})
        try {
            val failure = runCatching { trash.moveToTrash(listOf(selected.path), OperationContext.background()) }.exceptionOrNull()
            assertTrue("Trash moved an unselected link target", payload.isFile)
            assertEquals("keep in the original location", payload.readText())
            assertTrue(Files.isSymbolicLink(selected.toPath()))
            assertTrue("An unsupported selected link needs a visible error", !failure?.message.isNullOrBlank())
            assertTrue(trash.list().isEmpty())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    @Test fun trashRejectsABrokenLinkWithoutChangingOtherEntries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "trash-broken-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val payload = File(root, "keep.txt").apply { writeText("keep") }
        val selected = File(root, "selected-link")
        Files.createSymbolicLink(selected.toPath(), File(root, "absent").toPath())
        val trash = TrashRepository(app, File(root, "trash"), onCacheMutation = {})
        try {
            val failure = runCatching { trash.moveToTrash(listOf(selected.path), OperationContext.background()) }.exceptionOrNull()
            assertTrue(!failure?.message.isNullOrBlank())
            assertTrue(Files.isSymbolicLink(selected.toPath()))
            assertEquals("keep", payload.readText())
            assertTrue(trash.list().isEmpty())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    @Test fun emptyingTrashDoesNotTraverseLinksInsideARenamedFolder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "trash-directory-link-${UUID.randomUUID()}").apply { check(mkdir()) }
        val selected = File(root, "selected").apply { check(mkdir()) }
        val outside = File(root, "not-selected").apply { check(mkdir()) }
        val payload = File(outside, "keep.txt").apply { writeText("keep outside of the selected folder") }
        Files.createSymbolicLink(File(selected, "outside-link").toPath(), outside.toPath())
        val trash = TrashRepository(app, File(root, "trash"), onCacheMutation = {})
        try {
            assertEquals(1, trash.moveToTrash(listOf(selected.path), OperationContext.background()).movedItems)
            assertEquals(0, trash.emptyAll(OperationContext.background()).failedItems)
            assertEquals("keep outside of the selected folder", payload.readText())
        } finally { removeFixtureWithoutFollowingLinks(root) }
    }

    private fun removeFixtureWithoutFollowingLinks(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::removeFixtureWithoutFollowingLinks)
        check(file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
