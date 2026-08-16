package com.affilemanager.app.ui.preview

import com.affilemanager.app.ui.localization.localizedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class ExifDateParserTest {
    @Test
    fun validExifDateIsConvertedBeforeDisplay() {
        val millis = parseExifDateTimeMillis(
            rawDateTime = "2025:08:09 13:05:06",
            rawOffset = "+02:00",
            fallbackZone = ZoneOffset.UTC,
        )

        assertEquals(Instant.parse("2025-08-09T11:05:06Z").toEpochMilli(), millis)
        listOf(Locale.ENGLISH, Locale.forLanguageTag("lt")).forEach { locale ->
            val displayed = localizedDateTime(requireNotNull(millis), locale)
            assertFalse(Regex("\\d{4}:\\d{2}:\\d{2}").containsMatchIn(displayed))
            assertTrue(displayed.contains("2025"))
        }
    }

    @Test
    fun invalidExifDateIsNotDisplayedAsRawText() {
        assertNull(parseExifDateTimeMillis("2025:99:99 13:05:06", null, ZoneOffset.UTC))
        assertNull(parseExifDateTimeMillis("2025-08-09 13:05:06", null, ZoneOffset.UTC))
        assertNull(parseExifDateTimeMillis(null, null, ZoneOffset.UTC))
    }
}
