package com.affilemanager.app.picker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FilePickerRulesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun filtersRespectTopLevelTypeAndRejectMalformedOrOversizedRequests() {
        val request = FilePickerRequest.parse("*/*", listOf("image/*", "APPLICATION/PDF"), true)!!
        assertTrue(request.accepts("image/png"))
        assertTrue(request.accepts("application/pdf"))
        assertFalse(request.accepts("text/plain"))
        assertEquals(100, request.selectionLimit)
        assertEquals(1, FilePickerRequest.parse("text/*", null, false)!!.selectionLimit)
        assertNull(FilePickerRequest.parse(null, null, false))
        assertNull(FilePickerRequest.parse("image/png", listOf("*/*"), false))
        assertNull(FilePickerRequest.parse("image/*", listOf("text/plain"), false))
        assertNull(FilePickerRequest.parse("*/pdf", null, false))
        assertNull(FilePickerRequest.parse("text/plain\n", null, false))
        assertNull(FilePickerRequest.parse("a/" + "b".repeat(128), null, false))
        assertNull(FilePickerRequest.parse("*/*", emptyList(), false))
        assertNull(FilePickerRequest.parse("*/*", List(33) { "text/plain" }, false))
    }

    @Test fun containmentRejectsSiblingsPrivateExternalDataDirectoriesAndChangedFiles() {
        val root = temporary.newFolder("shared")
        val sibling = temporary.newFolder("shared-not")
        val boundary = FilePickerBoundary(listOf(root))
        val request = FilePickerRequest.parse("text/*", null, true)!!
        val text = File(root, "hello.txt").apply { writeText("fixture") }
        val child = File(root, "folder").apply { mkdir() }
        val privateFile = File(root, "Android/data/example/files/private.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("private") }
        assertEquals(listOf(text.canonicalFile), boundary.selected(listOf(text.path), request) { "text/plain" })
        assertNull(boundary.permitted(sibling.path))
        assertNull(boundary.permitted(privateFile.path))
        assertNull(boundary.permitted(File(root, "Android/obb/example").path))
        assertEquals("", boundary.parent(root.path))
        assertEquals(root.canonicalPath, boundary.parent(child.path))
        assertNull(boundary.parent(""))
        assertThrows(IllegalArgumentException::class.java) { boundary.selected(listOf(child.path), request) { "text/plain" } }
        assertThrows(IllegalArgumentException::class.java) { boundary.selected(listOf(text.path), request) { "image/png" } }
        assertThrows(IllegalArgumentException::class.java) { boundary.selected(listOf(text.path, text.path), request) { "text/plain" } }
        assertThrows(IllegalArgumentException::class.java) { boundary.selected(List(101) { text.path }, request) { "text/plain" } }
        text.delete()
        assertThrows(IllegalArgumentException::class.java) { boundary.selected(listOf(text.path), request) { "text/plain" } }
    }

    @Test fun aSymlinkCannotEscapeTheStorageRoot() {
        val root = temporary.newFolder("shared")
        val outside = temporary.newFile("private.txt")
        val link = File(root, "link.txt")
        // Windows may require an ungranted OS symlink privilege; Android coverage always exercises this too.
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        catch (unavailable: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException(unavailable) }
        assertNull(FilePickerBoundary(listOf(root)).permitted(link.path))
    }
}
