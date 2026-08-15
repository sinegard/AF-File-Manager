package com.affilemanager.app.terminal

interface TerminalBackend : AutoCloseable {
    suspend fun read(destination: ByteArray): Int
    suspend fun write(source: ByteArray)
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
    const val MAX_PASTE_BYTES = 64 * 1024
    const val MAX_CLIPBOARD_COPY_BYTES = 64 * 1024

    fun requireDimensions(rows: Int, columns: Int) {
        require(rows in MIN_DIMENSION..MAX_DIMENSION && columns in MIN_DIMENSION..MAX_DIMENSION) {
            "Invalid terminal dimensions"
        }
    }
}
