package com.affilemanager.app.core

/** Android activity results have a Binder budget, unlike the in-process transfer basket. */
object ExternalShareRules {
    const val MAX_FILES = 1_000
    const val MAX_RESULT_BYTES = 384 * 1_024
    const val TOO_MANY_FILES = "Vienu metu galima pasirinkti iki 1000 failų ar aplankų"
    const val TOO_LARGE = "Siuntimo rinkinio kelių aprašas per didelis"

    fun <T> collect(sources: Sequence<T>, key: (T) -> String, supported: (T) -> Boolean): List<T> {
        val result = LinkedHashMap<String, T>()
        var estimatedBytes = 1_024L
        for (source in sources) {
            if (!supported(source)) continue
            val identity = key(source)
            if (identity in result) continue
            require(result.size < MAX_FILES) { TOO_MANY_FILES }
            // Conservative UTF-16 URI and per-item parcel overhead; check before retaining data.
            estimatedBytes += identity.length.toLong() * 2 + 128
            require(estimatedBytes <= MAX_RESULT_BYTES) { TOO_LARGE }
            result[identity] = source
        }
        return result.values.toList()
    }
}
