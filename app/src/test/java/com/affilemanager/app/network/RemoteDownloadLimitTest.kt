package com.affilemanager.app.network

import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteDownloadLimitTest {
    @Test
    fun rejectsExpectedResponseAboveLimitBeforeStreaming() {
        val limit = RemoteDownloadLimit(maximumBytes = 10)

        assertThrows(IllegalArgumentException::class.java) {
            limit.checkExpected(11)
        }
    }

    @Test
    fun rejectsChunkThatWouldCrossLimit() {
        val limit = RemoteDownloadLimit(maximumBytes = 10)
        limit.record(6)

        assertThrows(IllegalArgumentException::class.java) {
            limit.record(5)
        }
    }

    @Test
    fun unlimitedTransferAcceptsMultipleChunks() {
        val limit = RemoteDownloadLimit(maximumBytes = null)

        limit.checkExpected(Long.MAX_VALUE)
        limit.record(1_024)
        limit.record(2_048)
    }
}
