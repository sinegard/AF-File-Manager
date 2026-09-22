package com.affilemanager.app.ui.screens

import com.affilemanager.app.data.RecentFileItem
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFilesViewRulesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L

    @Test
    fun filtersByNamePathAndDateBeforeSorting() {
        val recent = item("Invoice.pdf", "/Documents/Invoices/Invoice.pdf", 200, now - 1_000)
        val old = item("Invoice-old.pdf", "/Archive/Invoice-old.pdf", 100, now - 40L * 24 * 60 * 60 * 1_000)
        val unrelated = item("Photo.jpg", "/Pictures/Photo.jpg", 300, now - 2_000)

        val result = RecentFilesViewRules.apply(
            listOf(old, unrelated, recent),
            query = "documents/invoices",
            sort = RecentFilesSort.RECENT,
            ascending = false,
            dateRange = RecentFilesDateRange.LAST_30_DAYS,
            nowMillis = now,
            timeZone = utc,
        )

        assertEquals(listOf("Invoice.pdf"), result.map { it.entry.name })
    }

    @Test
    fun supportsBothSortDirectionsAndStableNameTieBreaks() {
        val items = listOf(
            item("b.txt", "/b.txt", 10, now - 1),
            item("a.txt", "/a.txt", 10, now - 2),
            item("large.bin", "/large.bin", 500, now - 3),
        )
        val ascending = RecentFilesViewRules.apply(items, "", RecentFilesSort.SIZE, true, RecentFilesDateRange.ALL, now, utc)
        val descending = RecentFilesViewRules.apply(items, "", RecentFilesSort.SIZE, false, RecentFilesDateRange.ALL, now, utc)
        assertEquals(listOf("a.txt", "b.txt", "large.bin"), ascending.map { it.entry.name })
        assertEquals(listOf("large.bin", "b.txt", "a.txt"), descending.map { it.entry.name })
    }

    private fun item(name: String, path: String, size: Long, time: Long) = RecentFileItem(
        entry = FileEntry(path, name, EntryKind.DOCUMENT, size, time, false, true, true),
        recentAtMillis = time,
    )
}
