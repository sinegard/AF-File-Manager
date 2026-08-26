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
    private var clipboardCacheInitialized = false
    private var cachedClipboardText: String? = null
    private var cachedClipboardBytes: ByteArray? = null
    private var cachedClipboardPrefix: ByteArray? = null
    private var cachedClipboardCandidate: ClipboardCandidate? = null
    private var activeClipboardText: String? = null
    private var activeClipboardBytes: ByteArray? = null

    fun accept(data: ByteArray) {
        if (data.isEmpty()) return

        var needsSchedule = false
        var sendImmediately = false
        var rejectImmediately = false
        synchronized(lock) {
            if (closed) return

            if (!flushScheduled) {
                if (data.size > TerminalLimits.MAX_PASTE_BYTES) {
                    rejectImmediately = true
                } else {
                    val candidate = clipboardCandidateLocked(clipboardText())
                    if (candidate == null || !sharesPrefix(candidate.prefix, data)) {
                        sendImmediately = true
                    } else {
                        activeClipboardText = candidate.text
                        activeClipboardBytes = candidate.bytes
                        appendLocked(data)
                        flushScheduled = true
                        needsSchedule = true
                    }
                }
            } else {
                appendLocked(data)
            }
        }

        if (rejectImmediately) {
            onTooLarge()
            return
        }
        if (sendImmediately) {
            send(data)
            return
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
            clearClipboardCacheLocked()
            activeClipboardText = null
            activeClipboardBytes = null
        }
    }

    private fun flush() {
        val batch: ByteArray?
        val wasTooLarge: Boolean
        val clipboard: String?
        val encodedClipboard: ByteArray?
        synchronized(lock) {
            if (closed) {
                clearChunksLocked()
                return
            }

            flushScheduled = false
            wasTooLarge = overflowed
            overflowed = false
            batch = if (wasTooLarge) null else combineChunksLocked()
            clipboard = activeClipboardText
            encodedClipboard = activeClipboardBytes
            activeClipboardText = null
            activeClipboardBytes = null
            clearChunksLocked()
        }

        if (wasTooLarge) {
            onTooLarge()
            return
        }
        if (batch == null || batch.isEmpty()) return

        try {
            if (clipboard != null && encodedClipboard?.contentEquals(batch) == true) {
                onMultilinePaste(clipboard)
                return
            }

            send(batch)
        } finally {
            batch.fill(0)
        }
    }

    private fun appendLocked(data: ByteArray) {
        if (overflowed) return
        if (data.size > TerminalLimits.MAX_PASTE_BYTES - byteCount) {
            clearChunksLocked()
            overflowed = true
            return
        }
        chunks += data.copyOf()
        byteCount += data.size
    }

    private fun clipboardCandidateLocked(clipboard: String?): ClipboardCandidate? {
        if (!clipboardCacheInitialized || clipboard !== cachedClipboardText) {
            clearClipboardCacheLocked()
            clipboardCacheInitialized = true
            cachedClipboardText = clipboard
            if (clipboard != null && TerminalPasteRules.hasLineBreak(clipboard)) {
                if (clipboard.length <= TerminalLimits.MAX_PASTE_BYTES) {
                    val normalized = if (Normalizer.isNormalized(clipboard, Normalizer.Form.NFC)) {
                        clipboard
                    } else {
                        Normalizer.normalize(clipboard, Normalizer.Form.NFC)
                    }
                    val encoded = TerminalPasteRules.encode(normalized)
                    if (encoded != null && encoded.size > 1) {
                        cachedClipboardBytes = encoded
                        cachedClipboardPrefix = encoded
                    } else {
                        encoded?.fill(0)
                        if (encoded == null) cachedClipboardPrefix = encodeBoundedClipboardPrefix(clipboard)
                    }
                } else {
                    cachedClipboardPrefix = encodeBoundedClipboardPrefix(clipboard)
                }
                cachedClipboardPrefix?.takeIf { it.isNotEmpty() }?.let { prefix ->
                    cachedClipboardCandidate = ClipboardCandidate(clipboard, cachedClipboardBytes, prefix)
                }
            }
        }
        return cachedClipboardCandidate
    }

    private fun clearClipboardCacheLocked() {
        cachedClipboardBytes?.fill(0)
        if (cachedClipboardPrefix !== cachedClipboardBytes) cachedClipboardPrefix?.fill(0)
        cachedClipboardText = null
        cachedClipboardBytes = null
        cachedClipboardPrefix = null
        cachedClipboardCandidate = null
        clipboardCacheInitialized = false
    }

    private fun encodeBoundedClipboardPrefix(clipboard: String): ByteArray? {
        var end = clipboard.length.coerceAtMost(CLIPBOARD_PREFIX_UTF16_UNITS)
        if (end < clipboard.length && end > 0 && Character.isHighSurrogate(clipboard[end - 1])) end--
        if (end <= 0) return null
        val sample = Normalizer.normalize(clipboard.substring(0, end), Normalizer.Form.NFC)
        val encoded = TerminalPasteRules.encode(sample) ?: return null
        if (encoded.size <= CLIPBOARD_PREFIX_BYTES) return encoded
        val prefix = encoded.copyOf(CLIPBOARD_PREFIX_BYTES)
        encoded.fill(0)
        return prefix
    }

    private fun sharesPrefix(first: ByteArray, second: ByteArray): Boolean {
        val length = minOf(first.size, second.size)
        if (length == 0) return false
        for (index in 0 until length) {
            if (first[index] != second[index]) return false
        }
        return true
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
        const val CLIPBOARD_PREFIX_UTF16_UNITS = 64
        const val CLIPBOARD_PREFIX_BYTES = 32
    }

    private data class ClipboardCandidate(
        val text: String,
        val bytes: ByteArray?,
        val prefix: ByteArray,
    )
}
