package com.affilemanager.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.data.TrashRepository
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.LocalFileOperator
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalFileIntegrationTest {
    @Test
    fun createAtomicEditCopyTrashAndRestoreOnAndroidStorage() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val base = requireNotNull(application.getExternalFilesDir("instrumentation"))
        val root = File(base, "run-${System.nanoTime()}")
        require(root.mkdirs())
        try {
            val repository = LocalFileRepository(application)
            val sourceDir = repository.createDirectory(root.absolutePath, "šaltinis").getOrThrow()
            val source = repository.createEmptyFile(sourceDir.absolutePath, "pastaba.txt").getOrThrow()
            repository.writeText(source.absolutePath, "Android atominis tekstas").getOrThrow()
            assertEquals("Android atominis tekstas", repository.readText(source.absolutePath).getOrThrow())

            val destination = repository.createDirectory(root.absolutePath, "kopija").getOrThrow()
            LocalFileOperator().copyOrMove(
                listOf(source.absolutePath),
                destination.absolutePath,
                move = false,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                context = OperationContext.background(),
            )
            val copied = File(destination.absolutePath, source.name)
            assertTrue(copied.isFile)
            assertEquals("Android atominis tekstas", copied.readText())

            val trash = TrashRepository(application)
            trash.moveToTrash(listOf(copied.absolutePath), OperationContext.background())
            assertFalse(copied.exists())
            val item = trash.list().first { it.originalPath == copied.absolutePath }
            val restored = File(trash.restore(item.id).getOrThrow())
            assertTrue(restored.isFile)
            assertEquals("Android atominis tekstas", restored.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
