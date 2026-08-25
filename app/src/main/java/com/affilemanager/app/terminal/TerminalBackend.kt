package com.affilemanager.app.terminal

import kotlinx.coroutines.yield

interface TerminalBackend : AutoCloseable {
    suspend fun read(destination: ByteArray): Int
    suspend fun write(source: ByteArray, offset: Int = 0, length: Int = source.size)
    suspend fun resize(rows: Int, columns: Int)
    override fun close()
}

object TerminalLimits {
    const val INITIAL_ROWS = 24
    const val INITIAL_COLUMNS = 80
    const val MIN_DIMENSION = 2
    const val MAX_DIMENSION = 500
    const val IO_CHUNK_BYTES = 64 * 1024
    const val MAX_PENDING_INPUT_CHUNKS = 64
    const val MAX_INPUT_CHUNK_BYTES = 64 * 1024
    const val TRANSPORT_WRITE_CHUNK_BYTES = 4 * 1024
    const val MAX_PASTE_BYTES = 64 * 1024
    const val MAX_CLIPBOARD_COPY_BYTES = 64 * 1024

    fun requireDimensions(rows: Int, columns: Int) {
        require(rows in MIN_DIMENSION..MAX_DIMENSION && columns in MIN_DIMENSION..MAX_DIMENSION) {
            "Invalid terminal dimensions"
        }
    }
}

enum class TerminalPasteResult {
    ACCEPTED,
    TOO_LARGE,
    BUSY,
}

object TerminalPasteRules {
    private const val CARRIAGE_RETURN: Byte = 0x0D
    private const val LINE_FEED: Byte = 0x0A

    fun encode(text: String): ByteArray? {
        // UTF-8 never uses fewer bytes than the number of UTF-16 code units.
        // Reject an enormous clipboard before allocating another enormous array.
        if (text.length > TerminalLimits.MAX_PASTE_BYTES) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > TerminalLimits.MAX_PASTE_BYTES) {
            bytes.fill(0)
            return null
        }
        return normalizeTerminalLineBreaks(bytes)
    }

    private fun normalizeTerminalLineBreaks(bytes: ByteArray): ByteArray {
        var readIndex = 0
        var writeIndex = 0
        while (readIndex < bytes.size) {
            when (val value = bytes[readIndex]) {
                CARRIAGE_RETURN -> {
                    bytes[writeIndex++] = CARRIAGE_RETURN
                    if (readIndex + 1 < bytes.size && bytes[readIndex + 1] == LINE_FEED) {
                        readIndex++
                    }
                }
                LINE_FEED -> bytes[writeIndex++] = CARRIAGE_RETURN
                else -> bytes[writeIndex++] = value
            }
            readIndex++
        }

        if (writeIndex == bytes.size) return bytes
        val normalized = bytes.copyOf(writeIndex)
        bytes.fill(0)
        return normalized
    }

    fun nextWriteLength(remainingBytes: Int): Int {
        require(remainingBytes >= 0) { "Remaining terminal input cannot be negative" }
        return remainingBytes.coerceAtMost(TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES)
    }

    suspend fun writeInChunks(
        byteCount: Int,
        write: suspend (offset: Int, length: Int) -> Unit,
    ) {
        require(byteCount >= 0) { "Terminal input size cannot be negative" }
        var offset = 0
        while (offset < byteCount) {
            val length = nextWriteLength(byteCount - offset)
            write(offset, length)
            offset += length
            if (offset < byteCount) yield()
        }
    }
}
