package com.affilemanager.app.network

/** Enforces an optional byte ceiling while a remote response is being streamed. */
internal class RemoteDownloadLimit(private val maximumBytes: Long?) {
    private var transferredBytes = 0L

    init {
        require(maximumBytes == null || maximumBytes >= 0) { "Download limit cannot be negative" }
    }

    fun checkExpected(expectedBytes: Long?) {
        if (expectedBytes != null && maximumBytes != null) {
            require(expectedBytes <= maximumBytes) { "Remote file exceeds the download limit" }
        }
    }

    fun record(chunkBytes: Int) {
        require(chunkBytes >= 0) { "Downloaded byte count cannot be negative" }
        val updated = transferredBytes + chunkBytes
        require(updated >= transferredBytes) { "Downloaded byte count overflowed" }
        if (maximumBytes != null) {
            require(updated <= maximumBytes) { "Remote file exceeds the download limit" }
        }
        transferredBytes = updated
    }
}
