package com.affilemanager.app.transfer

/** Optional login capability; an old receiver must not receive an oversized manifest. */
internal data class NearbyTransferCapacity(val files: Int = LEGACY_FILES) {
    init { require(files in 1..NearbySourcePreparer.MAX_FILES) }

    fun requireFiles(count: Int) {
        require(count <= files) { "Vienu kartu galima siųsti iki $files failų" }
    }

    companion object {
        const val LEGACY_FILES = 8_000
        const val HEADER = "X-AF-Max-Files"
        fun advertised(value: String?): NearbyTransferCapacity = NearbyTransferCapacity(
            value?.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(NearbySourcePreparer.MAX_FILES) ?: LEGACY_FILES,
        )
    }
}

/** Conservative metadata admission, not a reservation of Android or physical RAM. */
internal object NearbyTransferMemory {
    fun requireAvailable(files: Int, pathCharacters: Int, available: Long = availableHeap()) {
        require(files in 0..NearbySourcePreparer.MAX_FILES && pathCharacters in 0..NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS)
        val estimate = files * 1_024L + pathCharacters * 8L + NearbyTransferManifest.MAX_BYTES * 2L
        require(available > 0 && estimate <= available - available / 4) { "Siuntimo rinkinio kelių aprašas per didelis" }
    }

    private fun availableHeap(): Long = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
}
