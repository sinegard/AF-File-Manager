package com.affilemanager.app.transfer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NearbySourcePreparerTest {
    @Test fun cancellationDuringTheReturnHandoffRemovesTheUndeliveredCopy() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val source = File(app.cacheDir, "cancel-share-${UUID.randomUUID()}.txt").apply { writeText("cancel fixture") }
        val stageRoot = File(app.cacheDir, "nearby-send-staging")
        val before = stageRoot.listFiles().orEmpty().map(File::getName).toSet()
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", source)
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.put(block) }
        }
        var delivered = false
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            app.graph.nearbySources.prepareContentUris(listOf(uri)).getOrThrow()
            delivered = true
        }
        try {
            requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS)).run() // Start; IO preparation dispatches separately.
            val returnToCaller = requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(stageRoot.listFiles().orEmpty().any { it.name !in before })
            job.cancel() // Exact race: a prepared result exists but has no caller owner.
            returnToCaller.run()
            while (!job.isCompleted) requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS)).run()
            assertTrue(job.isCancelled)
            assertTrue(!delivered)
            assertTrue(stageRoot.listFiles().orEmpty().all { it.name in before })
            assertEquals("cancel fixture", source.readText())
        } finally {
            job.cancel()
            source.delete()
        }
    }
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
