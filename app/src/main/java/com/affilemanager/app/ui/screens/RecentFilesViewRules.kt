package com.affilemanager.app.ui.screens

import com.affilemanager.app.data.RecentFileItem
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal enum class RecentFilesTab { ADDED, OPENED }
internal enum class RecentFilesSort { RECENT, NAME, SIZE, TYPE }
internal enum class RecentFilesDateRange { ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS }

internal object RecentFilesViewRules {
    fun apply(
        items: List<RecentFileItem>,
        query: String,
        sort: RecentFilesSort,
        ascending: Boolean,
        dateRange: RecentFilesDateRange,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<RecentFileItem> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val cutoff = cutoffMillis(dateRange, nowMillis, timeZone)
        val comparator = when (sort) {
            RecentFilesSort.RECENT -> compareBy<RecentFileItem> { it.recentAtMillis }
            RecentFilesSort.NAME -> compareBy { it.entry.name.lowercase(Locale.ROOT) }
            RecentFilesSort.SIZE -> compareBy { it.entry.sizeBytes }
            RecentFilesSort.TYPE -> compareBy { it.entry.extension.ifBlank { if (it.entry.isDirectory) "\u0000" else "~" } }
        }.thenBy { it.entry.name.lowercase(Locale.ROOT) }
        val ordered = items.asSequence()
            .filter { item -> item.recentAtMillis >= cutoff }
            .filter { item ->
                normalizedQuery.isEmpty() ||
                    normalizedQuery in item.entry.name.lowercase(Locale.ROOT) ||
                    normalizedQuery in item.entry.absolutePath.lowercase(Locale.ROOT)
            }
            .sortedWith(if (ascending) comparator else comparator.reversed())
            .toList()
        return ordered
    }

    private fun cutoffMillis(range: RecentFilesDateRange, nowMillis: Long, timeZone: TimeZone): Long = when (range) {
        RecentFilesDateRange.ALL -> 0L
        RecentFilesDateRange.TODAY -> Calendar.getInstance(timeZone).run {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        RecentFilesDateRange.LAST_7_DAYS -> (nowMillis - 7L * 24 * 60 * 60 * 1_000).coerceAtLeast(0L)
        RecentFilesDateRange.LAST_30_DAYS -> (nowMillis - 30L * 24 * 60 * 60 * 1_000).coerceAtLeast(0L)
    }
}
