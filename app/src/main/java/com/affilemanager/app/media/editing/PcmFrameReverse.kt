package com.affilemanager.app.media.editing

import java.io.File
import java.io.RandomAccessFile

/** Constant memory, complete interleaved frames; no channel or byte order swap. */
internal object PcmFrameReverse {
    fun file(file: File, frameSize: Int, checkpoint: () -> Unit) = RandomAccessFile(file, "rw").use {
        data(it, 0, it.length(), frameSize, checkpoint); it.fd.sync()
    }
    fun data(file: RandomAccessFile, start: Long, size: Long, frameSize: Int, checkpoint: () -> Unit) {
        require(frameSize in 2..16 && size % frameSize == 0L)
        val leftBytes = ByteArray(64 * 1024 / frameSize * frameSize)
        val rightBytes = ByteArray(leftBytes.size)
        var left = start; var right = start + size
        while (right - left >= frameSize * 2L) {
            checkpoint()
            val count = minOf(leftBytes.size.toLong(), (right - left) / (2 * frameSize) * frameSize).toInt()
            file.seek(left); file.readFully(leftBytes, 0, count)
            file.seek(right - count); file.readFully(rightBytes, 0, count)
            reverse(leftBytes, count, frameSize); reverse(rightBytes, count, frameSize)
            file.seek(left); file.write(rightBytes, 0, count)
            file.seek(right - count); file.write(leftBytes, 0, count)
            left += count; right -= count
        }
    }
    private fun reverse(bytes: ByteArray, length: Int, frameSize: Int) {
        var left = 0; var right = length - frameSize
        while (left < right) {
            for (index in 0 until frameSize) { val saved = bytes[left + index]; bytes[left + index] = bytes[right + index]; bytes[right + index] = saved }
            left += frameSize; right -= frameSize
        }
    }
}
