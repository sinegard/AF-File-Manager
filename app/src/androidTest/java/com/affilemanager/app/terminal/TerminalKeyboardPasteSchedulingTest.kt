package com.affilemanager.app.terminal

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TerminalKeyboardPasteSchedulingTest {
    @Test
    fun ordinaryKeyboardInputBypassesTheMainLoopQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync {
            val sent = mutableListOf<ByteArray>()
            var scheduledCount = 0
            val guard = TerminalKeyboardPasteGuard(
                clipboardText = { "different\nclipboard" },
                onMultilinePaste = {},
                onTooLarge = {},
                send = { sent += it.copyOf() },
                scheduleFlush = {
                    scheduledCount++
                    Handler(Looper.getMainLooper()).postAfterQueuedTerminalKeyboardInput(it)
                },
            )

            guard.accept(byteArrayOf('x'.code.toByte()))

            assertEquals(0, scheduledCount)
            assertEquals(1, sent.size)
            assertTrue(sent.single().contentEquals(byteArrayOf('x'.code.toByte())))
            guard.cancel()
        }
    }

    @Test
    fun coalescesKeyboardChunksThatTermlibPostsAsSeparateMainLoopCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clipboard = "first command\nsecond command"
        val encoded = requireNotNull(TerminalPasteRules.encode(clipboard))
        val intercepted = AtomicReference<String?>()
        val sent = CopyOnWriteArrayList<ByteArray>()
        val completed = CountDownLatch(1)
        lateinit var guard: TerminalKeyboardPasteGuard

        instrumentation.runOnMainSync {
            val handler = Handler(Looper.getMainLooper())
            guard = TerminalKeyboardPasteGuard(
                clipboardText = { clipboard },
                onMultilinePaste = {
                    intercepted.set(it)
                    completed.countDown()
                },
                onTooLarge = { completed.countDown() },
                send = { sent += it.copyOf() },
                scheduleFlush = handler::postAfterQueuedTerminalKeyboardInput,
            )

            encoded.forEach { byte ->
                handler.post { guard.accept(byteArrayOf(byte)) }
            }
        }

        assertTrue("The queued multiline paste was not intercepted", completed.await(3, TimeUnit.SECONDS))
        assertEquals(clipboard, intercepted.get())
        assertTrue("No pasted bytes may reach the terminal before confirmation", sent.isEmpty())

        instrumentation.runOnMainSync { guard.cancel() }
        encoded.fill(0)
    }
}
