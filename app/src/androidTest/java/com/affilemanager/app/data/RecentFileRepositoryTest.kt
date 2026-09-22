package com.affilemanager.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentFileRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File

    @Before
    fun setUp() {
        context.getSharedPreferences("recent_files_v1", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.cacheDir, "recent-files-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        context.getSharedPreferences("recent_files_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun keepsAddedAndOpenedItemsInSeparateBoundedLists() = runBlocking {
        val addedFolder = File(root, "new-folder").apply { check(mkdir()) }
        val openedFile = File(root, "opened.txt").apply { writeText("fixture") }
        val repository = RecentFileRepository(context, LocalFileRepository(context))

        repository.recordAdded(addedFolder.absolutePath, 100)
        repository.record(openedFile.absolutePath, 200)
        val snapshot = repository.snapshot()

        assertTrue(snapshot.added.any { it.entry.absolutePath == addedFolder.canonicalPath && it.entry.isDirectory })
        assertTrue(snapshot.opened.any { it.entry.absolutePath == openedFile.canonicalPath && !it.entry.isDirectory })
        assertTrue(snapshot.added.none { it.entry.absolutePath == openedFile.canonicalPath })
        assertTrue(snapshot.opened.none { it.entry.absolutePath == addedFolder.canonicalPath })
    }
}
