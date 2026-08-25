package com.affilemanager.app.terminal

import java.text.Normalizer

/**
 * Batches the raw bytes emitted synchronously by one IME action. Gboard's
 * clipboard chip uses commitText(), so it otherwise bypasses Terminal's
 * explicit paste callback and turns every pasted line break into Enter.
 */
internal class TerminalKeyboardPasteGuard(
    private val clipboardText: () -> String?,
    private val onMultilinePaste: (String) -> Unit,
    private val onTooLarge: () -> Unit,
    private val send: (ByteArray) -> Unit,
    private val scheduleFlush: ((() -> Unit) -> Unit),
) {
    private val lock = Any()
    private val chunks = ArrayList<ByteArray>()
    private var byteCount = 0
    private var flushScheduled = false
    private var overflowed = false
    private var closed = false

    fun accept(data: ByteArray) {
        if (data.isEmpty()) return

        var needsSchedule = false
        synchronized(lock) {
            if (closed) return

            if (!overflowed) {
                if (data.size > TerminalLimits.MAX_PASTE_BYTES - byteCount) {
                    clearChunksLocked()
                    overflowed = true
                } else {
                    chunks += data.copyOf()
                    byteCount += data.size
                }
            }

            if (!flushScheduled) {
                flushScheduled = true
                needsSchedule = true
            }
        }

        if (needsSchedule) {
            try {
                scheduleFlush(::flush)
            } catch (_: Throwable) {
                flush()
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            closed = true
            flushScheduled = false
            overflowed = false
            clearChunksLocked()
        }
    }

    private fun flush() {
        val batch: ByteArray?
        val wasTooLarge: Boolean
        synchronized(lock) {
            if (closed) {
                clearChunksLocked()
                return
            }

            flushScheduled = false
            wasTooLarge = overflowed
            overflowed = false
            batch = if (wasTooLarge) null else combineChunksLocked()
            clearChunksLocked()
        }

        if (wasTooLarge) {
            onTooLarge()
            return
        }
        if (batch == null || batch.isEmpty()) return

        try {
            val couldBeMultilinePaste = batch.size > 1 && batch.any { it == CARRIAGE_RETURN || it == LINE_FEED }
            val clipboard = if (couldBeMultilinePaste) clipboardText() else null
            if (clipboard != null && TerminalPasteRules.hasLineBreak(clipboard)) {
                val normalizedClipboard = Normalizer.normalize(clipboard, Normalizer.Form.NFC)
                val encodedClipboard = TerminalPasteRules.encode(normalizedClipboard)
                val matchesClipboard = encodedClipboard?.contentEquals(batch) == true
                encodedClipboard?.fill(0)
                if (matchesClipboard) {
                    onMultilinePaste(clipboard)
                    return
                }
            }

            send(batch)
        } finally {
            batch.fill(0)
        }
    }

    private fun combineChunksLocked(): ByteArray? {
        if (byteCount == 0) return null
        if (chunks.size == 1) return chunks[0].copyOf()

        val combined = ByteArray(byteCount)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(combined, destinationOffset = offset)
            offset += chunk.size
        }
        return combined
    }

    private fun clearChunksLocked() {
        chunks.forEach { it.fill(0) }
        chunks.clear()
        byteCount = 0
    }

    private companion object {
        const val CARRIAGE_RETURN: Byte = 0x0D
        const val LINE_FEED: Byte = 0x0A
    }
}
