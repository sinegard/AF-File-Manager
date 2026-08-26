package com.affilemanager.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyboardPasteGuardTest {
    @Test
    fun interceptsMultilineClipboardCommittedAsSeveralKeyboardChunks() {
        val clipboard = "printf one\nprintf two"
        val expected = requireNotNull(TerminalPasteRules.encode(clipboard))
        val harness = Harness(clipboard)

        harness.guard.accept(expected.copyOfRange(0, 5))
        harness.guard.accept(expected.copyOfRange(5, expected.size))

        assertTrue(harness.sent.isEmpty())
        harness.flush()

        assertEquals(listOf(clipboard), harness.multilinePastes)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun matchesCrLfClipboardAgainstTerminalEnterBytes() {
        val clipboard = "first\r\nsecond\rthird\nfourth"
        val harness = Harness(clipboard)

        harness.guard.accept(requireNotNull(TerminalPasteRules.encode(clipboard)))
        harness.flush()

        assertEquals(listOf(clipboard), harness.multilinePastes)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun matchesTheUnicodeNormalizationUsedByTheIme() {
        val clipboard = "cafe\u0301\nnext"
        val imeBytes = requireNotNull(TerminalPasteRules.encode("caf\u00e9\nnext"))
        val harness = Harness(clipboard)

        harness.guard.accept(imeBytes)
        harness.flush()

        assertEquals(listOf(clipboard), harness.multilinePastes)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun letsSingleLineClipboardInputPassImmediatelyWithoutScheduling() {
        val input = "echo one line".toByteArray()
        val harness = Harness("echo one line")

        harness.guard.accept(input.copyOfRange(0, 4))
        harness.guard.accept(input.copyOfRange(4, input.size))

        assertEquals(2, harness.sent.size)
        assertArrayEquals(input, harness.sent.flattenBytes())
        assertTrue(harness.multilinePastes.isEmpty())
        assertEquals(0, harness.scheduledCount)
    }

    @Test
    fun manualEnterIsNeverMistakenForPastingOneNewline() {
        val harness = Harness("\n")

        harness.guard.accept(byteArrayOf('\r'.code.toByte()))

        assertEquals(1, harness.sent.size)
        assertArrayEquals(byteArrayOf('\r'.code.toByte()), harness.sent.single())
        assertTrue(harness.multilinePastes.isEmpty())
        assertEquals(1, harness.clipboardReadCount)
        assertEquals(0, harness.scheduledCount)
    }

    @Test
    fun letsOrdinaryKeyboardInputPassWhenItDoesNotMatchTheClipboard() {
        val input = "typed\r".toByteArray()
        val harness = Harness("different\nclipboard")

        harness.guard.accept(input)

        assertEquals(1, harness.sent.size)
        assertArrayEquals(input, harness.sent.single())
        assertTrue(harness.multilinePastes.isEmpty())
        assertEquals(0, harness.scheduledCount)
    }

    @Test
    fun releasesAClipboardPrefixWhenTheRestOfTheImeActionDoesNotMatch() {
        val input = "typo".toByteArray()
        val harness = Harness("typed clipboard\nnext")

        input.forEach { byte -> harness.guard.accept(byteArrayOf(byte)) }

        assertTrue(harness.sent.isEmpty())
        assertEquals(1, harness.scheduledCount)
        harness.flush()

        assertArrayEquals(input, harness.sent.single())
        assertTrue(harness.multilinePastes.isEmpty())
    }

    @Test
    fun rejectsAnImeBatchAboveThePasteLimit() {
        val harness = Harness("x".repeat(TerminalLimits.MAX_PASTE_BYTES) + "\n")

        harness.guard.accept(ByteArray(TerminalLimits.MAX_PASTE_BYTES) { 'x'.code.toByte() })
        harness.guard.accept(byteArrayOf('\r'.code.toByte()))
        harness.flush()

        assertEquals(1, harness.tooLargeCount)
        assertTrue(harness.sent.isEmpty())
        assertTrue(harness.multilinePastes.isEmpty())
    }

    @Test
    fun cancelledGuardDoesNotDeliverItsPendingInput() {
        val harness = Harness("first\nsecond")

        harness.guard.accept("first\rsecond".toByteArray())
        harness.guard.cancel()
        harness.flush()

        assertTrue(harness.sent.isEmpty())
        assertTrue(harness.multilinePastes.isEmpty())
        assertEquals(0, harness.tooLargeCount)
    }

    private class Harness(private val clipboard: String?) {
        private val scheduled = ArrayDeque<() -> Unit>()
        val multilinePastes = mutableListOf<String>()
        val sent = mutableListOf<ByteArray>()
        var tooLargeCount = 0
        var clipboardReadCount = 0
        val scheduledCount: Int
            get() = scheduled.size

        val guard = TerminalKeyboardPasteGuard(
            clipboardText = {
                clipboardReadCount++
                clipboard
            },
            onMultilinePaste = { multilinePastes += it },
            onTooLarge = { tooLargeCount++ },
            send = { sent += it.copyOf() },
            scheduleFlush = { scheduled.addLast(it) },
        )

        fun flush() {
            scheduled.removeFirst()()
        }
    }

    private fun List<ByteArray>.flattenBytes(): ByteArray {
        val result = ByteArray(sumOf(ByteArray::size))
        var offset = 0
        forEach { bytes ->
            bytes.copyInto(result, destinationOffset = offset)
            offset += bytes.size
        }
        return result
    }
}
