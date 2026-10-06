package com.affilemanager.app.transfer

import java.io.FileNotFoundException
import java.io.IOException

internal enum class NearbyUploadRecovery { COMPLETE, RETRY, CANCEL, WAIT, FAIL }

/** Bounded recovery policy for an upload whose receiver keeps authoritative batch state. */
internal object NearbyTransferRetry {
    const val MAX_ATTEMPTS = 3
    const val MAX_STATUS_CHECKS = 100
    const val RECOVERY_TIMEOUT_MILLIS = 135_000L
    const val UNAVAILABLE_TIMEOUT_MILLIS = 15_000L

    fun decide(
        failure: Throwable,
        remoteStatus: TransferFileStatus?,
        attempt: Int,
    ): NearbyUploadRecovery {
        if (failure !is IOException || failure is FileNotFoundException || failure is NearbySourceException) return NearbyUploadRecovery.FAIL
        if (failure is NearbyUploadException && failure.httpStatus !in setOf(408, 429, 500, 502, 503, 504)) return NearbyUploadRecovery.FAIL
        // An authenticated, exact-batch acknowledgement outranks a lost/error HTTP reply.
        if (remoteStatus == TransferFileStatus.COMPLETED) return NearbyUploadRecovery.COMPLETE
        if (remoteStatus == TransferFileStatus.CANCELLED) return NearbyUploadRecovery.CANCEL
        if (failure is NearbyUploadException && failure.failure != null && !failure.failure.retryable) return NearbyUploadRecovery.FAIL
        return when (remoteStatus) {
            TransferFileStatus.COMPLETED -> NearbyUploadRecovery.COMPLETE
            TransferFileStatus.CANCELLED -> NearbyUploadRecovery.CANCEL
            TransferFileStatus.WAITING, TransferFileStatus.FAILED ->
                if (attempt < MAX_ATTEMPTS) NearbyUploadRecovery.RETRY else NearbyUploadRecovery.FAIL
            TransferFileStatus.TRANSFERRING, null -> NearbyUploadRecovery.WAIT
        }
    }

    fun statusDelayMillis(check: Int): Long = (250L shl check.coerceIn(0, 3)).coerceAtMost(2_000L)

    fun retryDelayMillis(attempt: Int): Long = (500L * attempt.coerceIn(1, MAX_ATTEMPTS)).coerceAtMost(1_500L)
}

/** Monotonic hard deadline; a missing status must never authorise replaying an upload. */
internal class NearbyRecoveryWindow(private val startedAtMillis: Long) {
    private var lastAvailableAtMillis = startedAtMillis
    fun observe(nowMillis: Long, available: Boolean) { if (available) lastAvailableAtMillis = nowMillis }
    fun remainingMillis(nowMillis: Long): Long =
        minOf(
            NearbyTransferRetry.RECOVERY_TIMEOUT_MILLIS - (nowMillis - startedAtMillis).coerceAtLeast(0L),
            NearbyTransferRetry.UNAVAILABLE_TIMEOUT_MILLIS - (nowMillis - lastAvailableAtMillis).coerceAtLeast(0L),
        ).coerceAtLeast(0L)
    fun expired(nowMillis: Long): Boolean = remainingMillis(nowMillis) == 0L
}
