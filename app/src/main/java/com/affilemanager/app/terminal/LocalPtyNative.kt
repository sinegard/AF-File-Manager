package com.affilemanager.app.terminal

import androidx.annotation.Keep

@Keep
internal object LocalPtyNative {
    init {
        System.loadLibrary("afpty")
    }

    external fun spawn(
        shell: ByteArray,
        workingDirectory: ByteArray,
        environment: Array<ByteArray>,
        rows: Int,
        columns: Int,
    ): Long

    external fun read(handle: Long, destination: ByteArray): Int
    external fun write(handle: Long, source: ByteArray, offset: Int, length: Int): Int
    external fun resize(handle: Long, rows: Int, columns: Int)
    external fun close(handle: Long)
}
