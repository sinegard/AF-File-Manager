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
