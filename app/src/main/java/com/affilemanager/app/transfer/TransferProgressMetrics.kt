package com.affilemanager.app.transfer

import kotlin.math.ceil

internal data class TransferProgressMetrics(
    val bytesPerSecond: Long = 0L,
    val remainingMillis: Long? = null,
)

/** Honest average-rate estimate. Short samples stay hidden instead of flashing implausible speeds. */
internal object TransferProgressEstimator {
    private const val MIN_SAMPLE_MILLIS = 750L

    fun calculate(transferredBytes: Long, totalBytes: Long, elapsedMillis: Long): TransferProgressMetrics {
        if (transferredBytes < 0L || totalBytes < 0L || elapsedMillis < MIN_SAMPLE_MILLIS) {
            return TransferProgressMetrics()
        }
        if (transferredBytes == 0L) return TransferProgressMetrics()
        val bytesPerSecond = ((transferredBytes.toDouble() * 1_000.0) / elapsedMillis.toDouble())
            .coerceIn(1.0, Long.MAX_VALUE.toDouble())
            .toLong()
        return TransferProgressMetrics(
            bytesPerSecond = bytesPerSecond,
            remainingMillis = remainingMillis((totalBytes - transferredBytes).coerceAtLeast(0L), bytesPerSecond),
        )
    }

    fun remainingMillis(remainingBytes: Long, bytesPerSecond: Long): Long? {
        if (remainingBytes < 0L || bytesPerSecond <= 0L) return null
        return ((remainingBytes.toDouble() * 1_000.0) / bytesPerSecond.toDouble())
            .coerceIn(0.0, Long.MAX_VALUE.toDouble())
            .let(::ceil)
            .toLong()
    }

    fun remainingSeconds(remainingMillis: Long): Long =
        ceil(remainingMillis.coerceAtLeast(0L) / 1_000.0).toLong()
}
