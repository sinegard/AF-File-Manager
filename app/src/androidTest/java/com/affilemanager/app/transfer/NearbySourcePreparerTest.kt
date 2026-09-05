package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NearbySourcePreparerTest {
    @Test
    fun emptyDirectoryRemainsARealTransferItem() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(application.cacheDir, "nearby-empty-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val prepared = application.graph.nearbySources.prepareLocalPaths(listOf(root.absolutePath)).getOrThrow()

            assertTrue(prepared.paths.isEmpty())
            assertEquals(listOf(root.name), prepared.directories)
            assertTrue(prepared.relativePaths.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
