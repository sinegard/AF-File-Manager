package com.affilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardMergeRulesTest {
    @Test
    fun appendsNewItemsWithoutReplacingOrDuplicatingExistingItems() {
        val result = ClipboardMergeRules.merge(
            existing = listOf("/a", "/b"),
            additional = listOf("/b", "/c", "/c"),
            maximum = 10,
            key = { it },
        )

        assertEquals(listOf("/a", "/b", "/c"), result.items)
        assertEquals(1, result.addedCount)
        assertEquals(2, result.duplicateCount)
        assertFalse(result.limitReached)
    }

    @Test
    fun preservesOrderAndReportsItemsRejectedByTheBound() {
        val result = ClipboardMergeRules.merge(
            existing = listOf("/a", "/b"),
            additional = listOf("/c", "/d"),
            maximum = 3,
            key = { it },
        )

        assertEquals(listOf("/a", "/b", "/c"), result.items)
        assertEquals(1, result.addedCount)
        assertTrue(result.limitReached)
    }
}
