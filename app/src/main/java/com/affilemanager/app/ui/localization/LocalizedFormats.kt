package com.affilemanager.app.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Formats interface dates with the locale selected for AF File Manager, not the device fallback. */
@Composable
fun rememberLocalizedDateTimeFormat(
    dateStyle: Int = DateFormat.MEDIUM,
    timeStyle: Int = DateFormat.SHORT,
): DateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, dateStyle, timeStyle) {
        DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
    }
}

@Composable
fun rememberLocalizedTimeFormat(style: Int = DateFormat.SHORT): DateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, style) { DateFormat.getTimeInstance(style, locale) }
}

fun localizedDateTime(
    millis: Long,
    locale: Locale,
    dateStyle: Int = DateFormat.MEDIUM,
    timeStyle: Int = DateFormat.SHORT,
): String = DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale).format(Date(millis))
