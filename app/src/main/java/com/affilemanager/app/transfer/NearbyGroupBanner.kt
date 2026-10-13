package com.affilemanager.app.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal object NearbyGroupBanner {
    const val MAX_BYTES = 64 * 1024
    const val WIDTH = 256
    const val HEIGHT = 144

    fun encode(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        val ratio = WIDTH.toFloat() / HEIGHT
        val width = minOf(bitmap.width, (bitmap.height * ratio).toInt()).coerceAtLeast(1)
        val height = minOf(bitmap.height, (bitmap.width / ratio).toInt()).coerceAtLeast(1)
        val region = Bitmap.createBitmap(bitmap, (bitmap.width - width) / 2, (bitmap.height - height) / 2, width, height)
        val scaled = Bitmap.createScaledBitmap(region, WIDTH, HEIGHT, true)
        return try {
            val output = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 80, output))
            output.toByteArray().also(::validate)
        } finally { if (scaled !== region && scaled !== bitmap) scaled.recycle(); if (region !== bitmap) region.recycle() }
    }

    fun validate(bytes: ByteArray) {
        require(bytes.size in 4..MAX_BYTES && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "Netinkamas grupės paveikslėlis" }
    }

    fun decode(bytes: ByteArray): Bitmap {
        validate(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..WIDTH && bounds.outHeight in 1..HEIGHT) { "Grupės paveikslėlis per didelis" }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Grupės paveikslėlio perskaityti nepavyko" }
    }
}
