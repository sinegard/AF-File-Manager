package com.affilemanager.app.transfer

/** Fixed memory budget for high-throughput local transfers. */
internal object NearbyTransferTuning {
    const val IO_BUFFER_BYTES = 1 * 1_024 * 1_024
    const val SOCKET_BUFFER_BYTES = 1 * 1_024 * 1_024
    const val PROGRESS_INTERVAL_MILLIS = 200L
}
