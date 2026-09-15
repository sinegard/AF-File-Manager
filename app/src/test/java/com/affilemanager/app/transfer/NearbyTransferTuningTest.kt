package com.affilemanager.app.transfer

import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyTransferTuningTest {
    @Test
    fun throughputBuffersStayInsideTheBoundedRequestBudget() {
        assertTrue(NearbyTransferTuning.IO_BUFFER_BYTES in 256 * 1_024..2 * 1_024 * 1_024)
        assertTrue(NearbyTransferTuning.SOCKET_BUFFER_BYTES in 256 * 1_024..2 * 1_024 * 1_024)
        assertTrue(
            NearbyTransferTuning.IO_BUFFER_BYTES.toLong() * LanHttpServer.MAX_CONCURRENT_REQUESTS <= 8L * 1_024 * 1_024,
        )
        assertTrue(NearbyTransferTuning.PROGRESS_INTERVAL_MILLIS in 100L..500L)
    }
}
