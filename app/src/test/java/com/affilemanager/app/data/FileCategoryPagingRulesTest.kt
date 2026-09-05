package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileCategoryPagingRulesTest {
    @Test fun completeBrowserAdvancesPastOldLimitsWithoutIncreasingPageSize() {
        assertEquals(240, FileCategoryPagingRules.BROWSE_PAGE_ROWS)
        assertEquals(10_320, FileCategoryPagingRules.nextOffset(10_080, 240, true, Int.MAX_VALUE))
        assertNull(FileCategoryPagingRules.nextOffset(Int.MAX_VALUE - 100, 240, true, Int.MAX_VALUE))
        assertEquals("%a\\%\\_\\\\b%", FileCategoryPagingRules.literalSearchPattern(" a%_\\b "))
    }

    @Test
    fun firstPageIsSmallerThanFollowingPages() {
        assertEquals(160, FileCategoryPagingRules.resultLimit(offset = 0))
        assertEquals(240, FileCategoryPagingRules.resultLimit(offset = 160))
    }

    @Test
    fun nextOffsetAdvancesOnlyByRowsActuallyInspected() {
        assertEquals(
            557,
            FileCategoryPagingRules.nextOffset(
                offset = 160,
                scannedRows = 397,
                moreRowsAvailable = true,
                maxQueryRows = 10_000,
            ),
        )
        assertNull(
            FileCategoryPagingRules.nextOffset(
                offset = 160,
                scannedRows = 397,
                moreRowsAvailable = false,
                maxQueryRows = 10_000,
            ),
        )
    }

    @Test
    fun hardQueryBoundaryCannotScheduleAnotherPage() {
        assertNull(
            FileCategoryPagingRules.nextOffset(
                offset = 9_800,
                scannedRows = 200,
                moreRowsAvailable = true,
                maxQueryRows = 10_000,
            ),
        )
        assertNull(
            FileCategoryPagingRules.nextOffset(
                offset = 100,
                scannedRows = 0,
                moreRowsAvailable = true,
                maxQueryRows = 10_000,
            ),
        )
    }
}
