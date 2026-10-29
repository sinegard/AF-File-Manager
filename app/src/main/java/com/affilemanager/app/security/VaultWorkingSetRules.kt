package com.affilemanager.app.security

internal data class VaultHeapSnapshot(val maximum: Long, val allocated: Long, val freeInAllocated: Long) {
    companion object {
        fun current(): VaultHeapSnapshot = Runtime.getRuntime().let {
            VaultHeapSnapshot(it.maxMemory(), it.totalMemory(), it.freeMemory())
        }
    }
}

/** Admission is conservative; it never changes heap limits or forces collection. */
internal object VaultWorkingSetRules {
    private const val FIXED_WORKING_BYTES = 8L * 1024 * 1024

    fun permitsStreaming(heap: VaultHeapSnapshot): Boolean = available(heap) >= FIXED_WORKING_BYTES

    // Legacy Android AEAD can retain ciphertext plus multiple full-size intermediate arrays.
    fun permitsLegacy(payloadBytes: Long, declaredBytes: Long, heap: VaultHeapSnapshot): Boolean {
        if (payloadBytes < 16 || declaredBytes < 0) return false
        val largest = maxOf(payloadBytes, declaredBytes)
        val budget = available(heap) - FIXED_WORKING_BYTES
        return budget >= 0 && largest <= budget / 6
    }

    private fun available(heap: VaultHeapSnapshot): Long {
        if (heap.maximum <= 0 || heap.allocated !in 0..heap.maximum ||
            heap.freeInAllocated !in 0..heap.allocated) return 0
        val used = heap.allocated - heap.freeInAllocated
        return (heap.maximum - heap.maximum / 4 - used).coerceAtLeast(0)
    }
}
