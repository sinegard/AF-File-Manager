package com.affilemanager.app.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SimilarImageGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

data class ImageFingerprint(
    val entry: FileEntry,
    val differenceHash: Long,
    val width: Int,
    val height: Int,
)

class SimilarImageEngine {
    companion object {
        const val MAX_CANDIDATES = 1_000
        const val MAX_GROUPS = 100
        const val MAX_FILE_BYTES = 128L * 1_024L * 1_024L
        const val MAX_HASH_DISTANCE = 6
        private const val HASH_WIDTH = 9
        private const val HASH_HEIGHT = 8
        private const val MIN_DIMENSION = 128
    }

    suspend fun find(candidates: List<FileEntry>): List<SimilarImageGroup> = withContext(Dispatchers.IO) {
        val fingerprints = candidates.asSequence()
            .filter { !it.isDirectory && it.sizeBytes in (32L * 1_024L)..MAX_FILE_BYTES }
            .distinctBy(FileEntry::absolutePath)
            .take(MAX_CANDIDATES)
            .mapNotNull { entry ->
                coroutineContext.ensureActive()
                fingerprint(entry)
            }
            .toList()
        group(fingerprints)
    }

    internal fun group(fingerprints: List<ImageFingerprint>): List<SimilarImageGroup> {
        val groups = mutableListOf<MutableList<ImageFingerprint>>()
        fingerprints.sortedBy { it.entry.absolutePath }.forEach { candidate ->
            val existing = groups.firstOrNull { group -> similar(group.first(), candidate) }
            if (existing == null) groups += mutableListOf(candidate) else existing += candidate
        }
        return groups.asSequence()
            .filter { it.size > 1 }
            .sortedByDescending { group -> group.sumOf { it.entry.sizeBytes } }
            .take(MAX_GROUPS)
            .map { group ->
                val files = group.map(ImageFingerprint::entry).sortedBy(FileEntry::absolutePath)
                SimilarImageGroup(id = files.first().absolutePath, files = files)
            }
            .toList()
    }

    private fun similar(left: ImageFingerprint, right: ImageFingerprint): Boolean {
        val leftRatio = left.width.toDouble() / left.height.coerceAtLeast(1)
        val rightRatio = right.width.toDouble() / right.height.coerceAtLeast(1)
        val ratioDifference = abs(leftRatio - rightRatio) / max(leftRatio, rightRatio).coerceAtLeast(0.0001)
        return ratioDifference <= 0.03 && java.lang.Long.bitCount(left.differenceHash xor right.differenceHash) <= MAX_HASH_DISTANCE
    }

    private fun fingerprint(entry: FileEntry): ImageFingerprint? {
        val file = File(entry.absolutePath)
        if (!file.isFile || !file.canRead() || file.length() !in (32L * 1_024L)..MAX_FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth < MIN_DIMENSION || bounds.outHeight < MIN_DIMENSION) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(decoded, HASH_WIDTH, HASH_HEIGHT, true)
            try {
                var hash = 0L
                var bit = 0
                for (y in 0 until HASH_HEIGHT) {
                    for (x in 0 until HASH_WIDTH - 1) {
                        if (luminance(scaled.getPixel(x, y)) > luminance(scaled.getPixel(x + 1, y))) {
                            hash = hash or (1L shl bit)
                        }
                        bit += 1
                    }
                }
                ImageFingerprint(entry, hash, bounds.outWidth, bounds.outHeight)
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114) / 1_000
    }
}
