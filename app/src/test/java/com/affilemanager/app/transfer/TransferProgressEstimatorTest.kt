package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferProgressEstimatorTest {
    @Test
    fun hidesRateUntilTheSampleIsStable() {
        val metrics = TransferProgressEstimator.calculate(10_000, 20_000, 500)
        assertEquals(0L, metrics.bytesPerSecond)
        assertNull(metrics.remainingMillis)
    }

    @Test
    fun calculatesRateAndRemainingTimeFromTransferredBytes() {
        val metrics = TransferProgressEstimator.calculate(10L * 1_024 * 1_024, 30L * 1_024 * 1_024, 2_000)
        assertEquals(5L * 1_024 * 1_024, metrics.bytesPerSecond)
        assertEquals(4_000L, metrics.remainingMillis)
        assertEquals(4L, TransferProgressEstimator.remainingSeconds(requireNotNull(metrics.remainingMillis)))
    }

    @Test
    fun completedTransferReportsZeroRemainingTime() {
        val metrics = TransferProgressEstimator.calculate(8_192, 8_192, 1_000)
        assertEquals(0L, metrics.remainingMillis)
    }

    @Test
    fun invalidRateNeverProducesAnEstimate() {
        assertNull(TransferProgressEstimator.remainingMillis(1_000, 0))
        assertNull(TransferProgressEstimator.remainingMillis(-1, 100))
    }
}
