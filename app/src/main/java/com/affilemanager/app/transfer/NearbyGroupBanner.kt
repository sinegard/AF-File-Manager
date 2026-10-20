package com.affilemanager.app.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal object NearbyGroupBanner {
    const val MAX_BYTES = 64 * 1024
    const val WIDTH = 256
    const val HEIGHT = 144
    const val HIGH_MAX_BYTES = 256 * 1024
    const val HIGH_WIDTH = 1024
    const val HIGH_HEIGHT = 576

    fun encode(bitmap: Bitmap?, highQuality: Boolean = false): ByteArray? {
        if (bitmap == null) return null
        val ratio = WIDTH.toFloat() / HEIGHT
        val width = minOf(bitmap.width, (bitmap.height * ratio).toInt()).coerceAtLeast(1)
        val height = minOf(bitmap.height, (bitmap.width / ratio).toInt()).coerceAtLeast(1)
        val region = Bitmap.createBitmap(bitmap, (bitmap.width - width) / 2, (bitmap.height - height) / 2, width, height)
        val scaled = Bitmap.createScaledBitmap(region, if (highQuality) HIGH_WIDTH else WIDTH, if (highQuality) HIGH_HEIGHT else HEIGHT, true)
        return try {
            val output = ByteArrayOutputStream()
            // Re-encoding strips EXIF. Bound even noisy images rather than growing the protocol.
            val limit = if (highQuality) HIGH_MAX_BYTES else MAX_BYTES
            for (quality in listOf(85, 70, 55, 40, 25)) {
                output.reset()
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= limit) break
            }
            output.toByteArray().also { validate(it, highQuality) }
        } finally { if (scaled !== region && scaled !== bitmap) scaled.recycle(); if (region !== bitmap) region.recycle() }
    }

    fun validate(bytes: ByteArray, highQuality: Boolean = false) {
        require(bytes.size in 4..(if (highQuality) HIGH_MAX_BYTES else MAX_BYTES) && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "Netinkamas grupės paveikslėlis" }
    }

    fun decode(bytes: ByteArray, highQuality: Boolean = false): Bitmap {
        validate(bytes, highQuality)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..(if (highQuality) HIGH_WIDTH else WIDTH) && bounds.outHeight in 1..(if (highQuality) HIGH_HEIGHT else HEIGHT)) { "Grupės paveikslėlis per didelis" }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Grupės paveikslėlio perskaityti nepavyko" }
    }
}
