package com.affilemanager.app.ui.localization

import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.GregorianCalendar
import java.util.Locale

class LocalizedFormatsTest {
    @Test
    fun displayedDatesNeverUseExifColonSeparators() {
        val millis = GregorianCalendar(2025, 7, 9, 13, 5, 0).timeInMillis
        listOf(Locale.ENGLISH, Locale.forLanguageTag("lt")).forEach { locale ->
            val displayed = localizedDateTime(millis, locale)
            assertFalse("Raw EXIF date leaked for $locale: $displayed", Regex("\\d{4}:\\d{2}:\\d{2}").containsMatchIn(displayed))
        }
    }
}
