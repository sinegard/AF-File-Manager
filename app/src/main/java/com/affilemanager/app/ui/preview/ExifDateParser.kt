package com.affilemanager.app.ui.preview

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

private val EXIF_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/** Converts the EXIF wire format to an instant without ever exposing that raw format in the UI. */
internal fun parseExifDateTimeMillis(
    rawDateTime: String?,
    rawOffset: String?,
    fallbackZone: ZoneId = ZoneId.systemDefault(),
): Long? {
    val value = rawDateTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        val localDateTime = LocalDateTime.parse(value, EXIF_DATE_TIME_FORMATTER)
        val offset = rawOffset
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(ZoneOffset::of)
        (offset?.let(localDateTime::toInstant) ?: localDateTime.atZone(fallbackZone).toInstant()).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    }
}
