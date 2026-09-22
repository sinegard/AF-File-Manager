package com.affilemanager.app.transfer

import java.io.FileNotFoundException
import java.io.IOException

internal enum class NearbyUploadRecovery { COMPLETE, RETRY, CANCEL, WAIT, FAIL }

/** Bounded recovery policy for an upload whose receiver keeps authoritative batch state. */
internal object NearbyTransferRetry {
    const val MAX_ATTEMPTS = 3
    const val MAX_STATUS_CHECKS = 4

    fun decide(
        failure: Throwable,
        remoteStatus: TransferFileStatus?,
        attempt: Int,
    ): NearbyUploadRecovery {
        if (failure !is IOException || failure is FileNotFoundException) return NearbyUploadRecovery.FAIL
        return when (remoteStatus) {
            TransferFileStatus.COMPLETED -> NearbyUploadRecovery.COMPLETE
            TransferFileStatus.CANCELLED -> NearbyUploadRecovery.CANCEL
            TransferFileStatus.WAITING, TransferFileStatus.FAILED ->
                if (attempt < MAX_ATTEMPTS) NearbyUploadRecovery.RETRY else NearbyUploadRecovery.FAIL
            TransferFileStatus.TRANSFERRING, null -> NearbyUploadRecovery.WAIT
        }
    }

    fun statusDelayMillis(check: Int): Long = (250L shl check.coerceIn(0, 2)).coerceAtMost(1_000L)

    fun retryDelayMillis(attempt: Int): Long = (500L * attempt.coerceIn(1, MAX_ATTEMPTS)).coerceAtMost(1_500L)
}
