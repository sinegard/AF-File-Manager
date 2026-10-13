package com.affilemanager.app.transfer

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedParallelFilesTest {
    @Test fun atMostTheReceiversChosenNumberOfWorkersRunAndEveryFileFinishesOnce() = runTest {
        for (parallelism in 1..5) {
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val finished = mutableListOf<Int>()
            forEachFileBounded(30, parallelism, {}) { index ->
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                delay(10)
                finished += index
                active.decrementAndGet()
            }
            assertEquals(parallelism, maximum.get())
            assertEquals((0 until 30).toList(), finished.sorted())
        }
    }
    @Test fun cancellingTheOwnerReleasesEveryWorker() = runTest {
        val gate = CompletableDeferred<Unit>()
        var ended = 0
        val job = launch { forEachFileBounded(40, 5, {}) { try { gate.await() } finally { ended++ } } }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(5, ended)
        assertTrue(job.isCompleted)
    }
    @Test fun legacyReceiversRemainSequentialAndInvalidParallelismIsRejected() = runTest {
        assertTrue(runCatching { forEachFileBounded(1, 6, {}) { } }.isFailure)
        val connection = NearbyConnection { 1000 }
        val peer = NearbyPairing.create("192.168.0.2", 25001, "12345678")
        connection.remember(peer, "af_session=example")
        connection.setConcurrentUploads(peer, "5")
        assertEquals(1, connection.concurrentUploads(peer))
        connection.setQueueSupported(peer, true)
        assertEquals(5, connection.concurrentUploads(peer))
        connection.setConcurrentUploads(peer, "invalid")
        assertEquals(1, connection.concurrentUploads(peer))
        connection.clear()
        assertEquals(1, connection.concurrentUploads(peer))
    }
}
