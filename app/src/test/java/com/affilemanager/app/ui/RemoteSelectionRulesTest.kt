package com.affilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSelectionRulesTest {
    @Test
    fun toggleMatchesLocalSelectionBehavior() {
        val available = listOf("/one", "/two")

        val selected = RemoteSelectionRules.toggle(emptySet(), available, "/one", maximum = 1)
        assertEquals(setOf("/one"), selected.selectedPaths)
        assertFalse(selected.limitReached)

        val deselected = RemoteSelectionRules.toggle(selected.selectedPaths, available, "/one", maximum = 1)
        assertEquals(emptySet<String>(), deselected.selectedPaths)

        val ignored = RemoteSelectionRules.toggle(emptySet(), available, "/missing", maximum = 1)
        assertEquals(emptySet<String>(), ignored.selectedPaths)
    }

    @Test
    fun limitNeverDropsAnExistingSelection() {
        val result = RemoteSelectionRules.toggle(
            current = setOf("/one"),
            availablePaths = listOf("/one", "/two"),
            path = "/two",
            maximum = 1,
        )

        assertEquals(setOf("/one"), result.selectedPaths)
        assertTrue(result.limitReached)
    }

    @Test
    fun selectAllCapsAndRefreshRetainsOnlyVisiblePaths() {
        val result = RemoteSelectionRules.selectAll(listOf("/one", "/two", "/three"), maximum = 2)
        assertEquals(setOf("/one", "/two"), result.selectedPaths)
        assertTrue(result.limitReached)
        assertEquals(setOf("/two"), RemoteSelectionRules.retainAvailable(result.selectedPaths, listOf("/two", "/four")))
    }

    @Test
    fun rangeSetAddsAndRemovesOnlyAvailablePathsWithoutCrossingTheLimit() {
        val selected = RemoteSelectionRules.set(
            current = setOf("/one"),
            availablePaths = listOf("/one", "/two", "/three"),
            paths = listOf("/two", "/missing", "/three"),
            selected = true,
            maximum = 2,
        )

        assertEquals(setOf("/one", "/two"), selected.selectedPaths)
        assertTrue(selected.limitReached)

        val deselected = RemoteSelectionRules.set(
            current = selected.selectedPaths,
            availablePaths = listOf("/one", "/two", "/three"),
            paths = listOf("/one", "/missing"),
            selected = false,
            maximum = 2,
        )
        assertEquals(setOf("/two"), deselected.selectedPaths)
        assertFalse(deselected.limitReached)
    }
}
