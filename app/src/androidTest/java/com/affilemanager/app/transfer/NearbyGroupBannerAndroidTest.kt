package com.affilemanager.app.transfer

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class NearbyGroupBannerAndroidTest {
    @Test fun bothProtocolVersionsProduceRealBoundedImagesAndLegacyRejectsLargerPixels() {
        val source = Bitmap.createBitmap(1400, 1800, Bitmap.Config.ARGB_8888)
        try {
            val random = java.util.Random(256L)
            val pixels = IntArray(1400 * 1800) { random.nextInt() or 0xff000000.toInt() }
            source.setPixels(pixels, 0, 1400, 0, 0, 1400, 1800)
            val legacy = requireNotNull(NearbyGroupBanner.encode(source))
            val high = requireNotNull(NearbyGroupBanner.encode(source, true))
            assertTrue(legacy.size <= 64 * 1024)
            assertTrue(high.size <= 256 * 1024)
            NearbyGroupBanner.decode(legacy).let { assertEquals(256, it.width); assertEquals(144, it.height); it.recycle() }
            NearbyGroupBanner.decode(high, true).let { assertEquals(1024, it.width); assertEquals(576, it.height); it.recycle() }
            assertThrows(IllegalArgumentException::class.java) { NearbyGroupBanner.decode(high) }
            assertThrows(IllegalArgumentException::class.java) { NearbyGroupBanner.decode(byteArrayOf(1, 2, 3, 4), true) }
            assertThrows(IllegalArgumentException::class.java) { NearbyGroupBanner.decode(high + ByteArray(256 * 1024), true) }
        } finally { source.recycle() }
    }
}
