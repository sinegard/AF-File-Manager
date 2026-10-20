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
        val pipe = File(app.cacheDir, "cancel-feed-${UUID.randomUUID()}.txt")
        android.system.Os.mkfifo(pipe.path, 384)
        val stageRoot = File(app.cacheDir, "nearby-send-staging")
        val before = stageRoot.listFiles().orEmpty().map(File::getName).toSet()
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", pipe)
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.put(block) }
        }
        var delivered = false
        var writer: Thread? = null
        val writerError = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            app.graph.nearbySources.prepareContentUris(listOf(uri)).getOrThrow()
            delivered = true
        }
        try {
            requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS)).run() // Start; IO preparation dispatches separately.
            // The FIFO has no writer until the caller has suspended. A tiny regular
            // file can finish on IO before withContext suspends and return inline,
            // leaving no queued return handoff for this exact-race test to cancel.
            writer = Thread {
                try { pipe.outputStream().use { it.write(source.readBytes()) } }
                catch (error: Throwable) { writerError.set(error) }
            }.apply {
                isDaemon = true
                start()
            }
            val returnToCaller = requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(stageRoot.listFiles().orEmpty().any { it.name !in before })
            assertTrue(stageRoot.listFiles().orEmpty().filter { it.name !in before }
                .flatMap { it.listFiles().orEmpty().toList() }.any { it.isFile && it.readText() == "cancel fixture" })
            job.cancel() // Exact race: a prepared result exists but has no caller owner.
            returnToCaller.run()
            val completionDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!job.isCompleted) {
                // Cleanup may finish on IO after the completion check without
                // scheduling another caller runnable. Null means recheck the job,
                // not that a completed cleanup failed to return a continuation.
                queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)?.run()
                check(job.isCompleted || android.os.SystemClock.elapsedRealtime() < completionDeadline) { "Cancellation cleanup did not complete" }
            }
            assertTrue(job.isCancelled)
            assertTrue(!delivered)
            assertTrue(stageRoot.listFiles().orEmpty().all { it.name in before })
            assertEquals("cancel fixture", source.readText())
            writer.join(10_000)
            assertTrue(!writer.isAlive)
            assertEquals(null, writerError.get())
        } finally {
            job.cancel()
            pipe.delete()
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

    @Test
    fun systemDocumentIsStreamedDirectlyWithoutASecondLargeCacheCopy() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val source = File(application.cacheDir, "direct-video-${UUID.randomUUID()}.mp4")
        val stageRoot = File(application.cacheDir, "nearby-send-staging")
        val before = stageRoot.listFiles().orEmpty().map(File::getName).toSet()
        val payload = ByteArray(4 * 1_024 * 1_024) { index -> (index and 0xff).toByte() }
        source.outputStream().use { it.write(payload) }
        payload.fill(0)
        val uri = androidx.core.content.FileProvider.getUriForFile(application, "${application.packageName}.files", source)
        try {
            val prepared = application.graph.nearbySources
                .prepareContentUris(listOf(uri), copyToPrivateStage = false)
                .getOrThrow()

            assertEquals(listOf(""), prepared.paths)
            assertEquals(listOf(uri.toString()), prepared.sourceUris)
            assertEquals(listOf(source.length()), prepared.fileSizes)
            assertEquals(null, prepared.cleanupRootPath)
            assertTrue(stageRoot.listFiles().orEmpty().all { it.name in before })
        } finally {
            source.delete()
        }
    }

    @Test
    fun selectedLocalFileAndInstalledAppRemainInOneTransferBasket() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val source = File(app.cacheDir, "selected-${UUID.randomUUID()}.txt").apply { writeText("local file") }
        val applicationInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
        val installed = app.graph.localFiles.toEntry(File(applicationInfo.sourceDir)).copy(
            name = "AF File Manager",
            packageName = app.packageName,
        )
        var prepared: PreparedNearbyTransfer? = null
        try {
            prepared = app.graph.nearbySources.prepareEntries(listOf(app.graph.localFiles.toEntry(source), installed)).getOrThrow()
            assertEquals(2, prepared.paths.size)
            assertEquals(2, prepared.relativePaths.distinct().size)
            assertTrue(prepared.relativePaths.contains(source.name))
            assertTrue(prepared.paths.any { it != source.canonicalPath && File(it).isFile })
        } finally {
            prepared?.let { app.graph.nearbySources.discard(it) }
            source.delete()
        }
    }
}
