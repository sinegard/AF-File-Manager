package com.affilemanager.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecentFileRepositoryTest {
    @Test
    fun recordedReadableFileAppearsAndMissingFileIsFiltered() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val file = File(application.cacheDir, "recent-${System.nanoTime()}.txt")
        val repository = RecentFileRepository(application, LocalFileRepository(application))
        try {
            file.writeText("recent file")
            repository.record(file.absolutePath, System.currentTimeMillis() + 60_000L)

            assertTrue(repository.latest().any { it.entry.absolutePath == file.canonicalPath })

            assertTrue(file.delete())
            assertFalse(repository.latest().any { it.entry.absolutePath == file.canonicalPath })
        } finally {
            file.delete()
        }
    }
}
