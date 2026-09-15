package com.affilemanager.app.archive

enum class ArchiveFormat {
    ZIP,
    SEVEN_Z,
    TAR,
    TAR_GZ,
    RAR,
    GZIP,
}

object ArchiveCompressionRules {
    const val MIN_LEVEL = 0
    const val DEFAULT_LEVEL = 6
    const val MAX_LEVEL = 9

    fun validated(level: Int): Int {
        require(level in MIN_LEVEL..MAX_LEVEL) { "Netinkamas suspaudimo lygis" }
        return level
    }
}

/** Bounded user-facing target for formats whose compression strength AF controls. */
object ArchiveTargetSizeRules {
    const val MIN_MIB = 1L
    const val MAX_MIB = 8_192L
    private const val BYTES_PER_MIB = 1_024L * 1_024L

    fun parseMib(value: String): Long? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        require(normalized.all(Char::isDigit)) { "Netinkama archyvo dydžio riba" }
        val mib = normalized.toLongOrNull() ?: throw IllegalArgumentException("Netinkama archyvo dydžio riba")
        require(mib in MIN_MIB..MAX_MIB) { "Archyvo dydžio riba turi būti nuo 1 iki 8192 MiB" }
        return Math.multiplyExact(mib, BYTES_PER_MIB)
    }

    fun validatedBytes(value: Long, limits: ArchiveLimits): Long {
        require(value in BYTES_PER_MIB..limits.maxExpandedBytes) { "Netinkama archyvo dydžio riba" }
        return value
    }

    fun supported(format: ArchiveFormat): Boolean =
        format == ArchiveFormat.ZIP || format == ArchiveFormat.TAR_GZ
}

data class ArchiveEntryInfo(
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val compressedSizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
)

data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxExpandedBytes: Long = 8L * 1_024 * 1_024 * 1_024,
    val maxSingleEntryBytes: Long = 4L * 1_024 * 1_024 * 1_024,
    val maxDepth: Int = 64,
    val maxCompressionRatio: Long = 1_000,
)
