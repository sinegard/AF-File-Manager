package com.affilemanager.app.widgets

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WidgetPublicationGateTest {
    @Test fun hidingPrivateContentPublishesAfterAnAlreadyStartedHostUpdate() {
        val executor = Executors.newFixedThreadPool(2)
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val lockEntered = CountDownLatch(1)
        val displayed = AtomicReference("empty")
        try {
            val old = executor.submit {
                WidgetPublicationGate.serialize {
                    oldEntered.countDown()
                    check(releaseOld.await(3, TimeUnit.SECONDS))
                    displayed.set("private old render")
                }
            }
            assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
            val locking = executor.submit {
                lockEntered.countDown()
                WidgetPublicationGate.serialize { displayed.set("locked") }
            }
            assertTrue(lockEntered.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { locking.get(100, TimeUnit.MILLISECONDS) }
            releaseOld.countDown()
            old.get(3, TimeUnit.SECONDS); locking.get(3, TimeUnit.SECONDS)
            assertEquals("locked", displayed.get())
        } finally { releaseOld.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    @Test fun aLateResultResolvesTheCurrentPrivacyStateInsideThePublicationGate() {
        val locked = AtomicBoolean(false)
        val oldRender = "private old render"
        val displayed = AtomicReference("empty")
        locked.set(true)
        WidgetPublicationGate.serialize { displayed.set(if (locked.get()) "locked" else oldRender) }
        assertEquals("locked", displayed.get())
    }
}
