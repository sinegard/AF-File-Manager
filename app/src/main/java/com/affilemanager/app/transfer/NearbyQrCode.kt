package com.affilemanager.app.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

object NearbyQrCode {
    private const val MAX_CAPTURE_BYTES = 32L * 1_024L * 1_024L
    private const val MAX_CAPTURE_DIMENSION = 2_048

    fun create(payload: String, sizePixels: Int = 720): Bitmap {
        require(payload.length in 1..NearbyPairing.MAX_PAYLOAD_LENGTH) { "Netinkamas QR turinys" }
        val size = sizePixels.coerceIn(240, 1_024)
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    fun decode(capture: File): String {
        require(capture.isFile && capture.length() in 1..MAX_CAPTURE_BYTES) { "QR nuotrauka nepasiekiama" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(capture.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "QR nuotraukos perskaityti nepavyko" }
        var sample = 1
        while (bounds.outWidth / sample > MAX_CAPTURE_DIMENSION || bounds.outHeight / sample > MAX_CAPTURE_DIMENSION) {
            sample *= 2
        }
        val decoded = requireNotNull(
            BitmapFactory.decodeFile(capture.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }),
        ) { "QR nuotraukos perskaityti nepavyko" }
        val rotation = runCatching { ExifInterface(capture).rotationDegrees }.getOrDefault(0)
        val bitmap = if (rotation == 0) decoded else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        }
        return try {
            val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            pixels.fill(0)
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true,
            )
            val value = sequenceOf(source, source.invert()).firstNotNullOfOrNull { luminance ->
                runCatching {
                    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(luminance)), hints).text
                }.getOrNull()
            }
            require(!value.isNullOrBlank() && value.length <= NearbyPairing.MAX_PAYLOAD_LENGTH) {
                "AF File Manager QR kodas nerastas"
            }
            value
        } finally {
            bitmap.recycle()
        }
    }
}
