package com.affilemanager.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TerminalLimitsTest {
    @Test
    fun acceptsNormalDimensionsAndRejectsUnboundedValues() {
        TerminalLimits.requireDimensions(24, 80)
        assertThrows(IllegalArgumentException::class.java) { TerminalLimits.requireDimensions(1, 80) }
        assertThrows(IllegalArgumentException::class.java) { TerminalLimits.requireDimensions(24, 501) }
    }

    @Test
    fun boundsClipboardEncodingBeforeItReachesTheTerminalTransport() {
        val accepted = "a".repeat(TerminalLimits.MAX_PASTE_BYTES)
        assertArrayEquals(accepted.toByteArray(), TerminalPasteRules.encode(accepted))
        assertNull(TerminalPasteRules.encode("a".repeat(TerminalLimits.MAX_PASTE_BYTES + 1)))
        assertNull(TerminalPasteRules.encode("€".repeat(TerminalLimits.MAX_PASTE_BYTES / 2)))
    }

    @Test
    fun normalizesClipboardLineBreaksToTerminalEnter() {
        assertArrayEquals(
            "first\rsecond\rthird\rfourth".toByteArray(),
            TerminalPasteRules.encode("first\nsecond\r\nthird\rfourth"),
        )
    }

    @Test
    fun splitsAcceptedPasteIntoSmallTransportWrites() {
        assertEquals(0, TerminalPasteRules.nextWriteLength(0))
        assertEquals(1, TerminalPasteRules.nextWriteLength(1))
        assertEquals(
            TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES,
            TerminalPasteRules.nextWriteLength(TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES + 1),
        )
        assertThrows(IllegalArgumentException::class.java) { TerminalPasteRules.nextWriteLength(-1) }
    }

    @Test
    fun writesTheMaximumAcceptedPasteAsOrderedBoundedChunks() = runTest {
        val writes = mutableListOf<Pair<Int, Int>>()

        TerminalPasteRules.writeInChunks(TerminalLimits.MAX_PASTE_BYTES) { offset, length ->
            writes += offset to length
        }

        assertEquals(
            TerminalLimits.MAX_PASTE_BYTES / TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES,
            writes.size,
        )
        assertEquals(TerminalLimits.MAX_PASTE_BYTES, writes.sumOf { it.second })
        writes.forEachIndexed { index, (offset, length) ->
            assertEquals(index * TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES, offset)
            assertEquals(TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES, length)
        }
    }
}
