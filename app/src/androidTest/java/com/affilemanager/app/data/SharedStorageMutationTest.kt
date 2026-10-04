package com.affilemanager.app.data

import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SharedStorageMutationTest {
    private val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    private fun indexedName(file: File): String? = app.contentResolver.query(
        MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath), null,
    )!!.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun digest(file: File): List<Byte> {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().toList()
    }

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AFIndex-${UUID.randomUUID()}")
        check(root.mkdirs()) { "Shared-storage test permission is missing" }
        try { block(root) } finally {
            root.deleteRecursively()
            app.graph.sharedStorageIndex.changed(listOf(root))
        }
    }

    @Test fun renameCopyMoveTrashAndRestoreAreVisibleInTheSystemProvider() = runBlocking {
        fixture { root ->
            val original = File(app.applicationInfo.sourceDir).copyTo(File(root, "old-name.apk"))
            val expected = digest(original)
            app.graph.sharedStorageIndex.changed(listOf(original))
            assertEquals(original.name, indexedName(original))
            val renamed = app.graph.localFiles.rename(original.path, "new-name.apk").getOrThrow().file
            assertEquals(expected, digest(renamed))
            assertNull(indexedName(original))
            assertEquals(renamed.name, indexedName(renamed))
            val copiedFolder = File(root, "copies").apply { mkdir() }
            app.graph.localFileOperator.copyOrMove(listOf(renamed.path), copiedFolder.path, false, ConflictPolicy.KEEP_BOTH, OperationContext.background())
            val copied = File(copiedFolder, renamed.name)
            assertEquals(renamed.name, indexedName(copied))
            assertTrue(renamed.isFile)
            val movedFolder = File(root, "moved").apply { mkdir() }
            app.graph.localFileOperator.copyOrMove(listOf(copiedFolder.path), movedFolder.path, true, ConflictPolicy.KEEP_BOTH, OperationContext.background())
            val moved = File(movedFolder, "copies/${copied.name}")
            assertEquals(renamed.name, indexedName(moved))
            assertNull(indexedName(copied))
            assertFalse(copied.exists())
            val before = app.graph.trash.list().map { it.id }.toSet()
            app.graph.trash.moveToTrash(listOf(moved.path), OperationContext.background())
            val item = app.graph.trash.list().single { it.id !in before }
            assertNull(indexedName(moved))
            val restored = File(app.graph.trash.restore(item.id).getOrThrow())
            assertEquals(restored.name, indexedName(restored))
            assertEquals(expected, digest(restored))
        }
    }

    @Test fun folderScopeIsAppliedBeforePagingAndDoesNotIncludeOtherKindsOrDescendants() = runBlocking {
        fixture { root ->
            fun image(parent: File, name: String): File {
                val file = File(parent, name)
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                return file
            }
            val photo = image(root, "photo.png")
            File(root, "unrelated.txt").writeText("not a photo")
            val child = File(root, "child").apply { mkdir() }
            image(child, "nested.png")
            app.graph.sharedStorageIndex.changed(listOf(root))
            val page = app.graph.fileCategories.loadPage(FileCategory.IMAGES, 0, SortMode.NAME, SortDirection.ASCENDING,
                forceRefresh = true, parentFolderPath = root.path)
            assertEquals(listOf(photo.path), page.entries.map { it.absolutePath })
            assertNull(page.nextOffset)
        }
    }
}
