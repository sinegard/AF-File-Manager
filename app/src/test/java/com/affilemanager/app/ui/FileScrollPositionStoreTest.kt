package com.affilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileScrollPositionStoreTest {
    @Test
    fun progressiveUpdatesPersistTheTopUntilTheInitialListingFinishes() {
        val initial = FileScrollPosition()

        assertEquals(true, ProgressiveScrollRules.startsPinnedToTop(initial))
        assertEquals(
            FileScrollPosition(),
            ProgressiveScrollRules.positionToPersist(
                pinnedToTop = true,
                firstVisibleItemIndex = 9,
                firstVisibleItemScrollOffset = 12,
            ),
        )
    }

    @Test
    fun restoredAndUserMovedPositionsAreNotForcedToTheTop() {
        assertEquals(false, ProgressiveScrollRules.startsPinnedToTop(FileScrollPosition(7, 3)))
        assertEquals(
            FileScrollPosition(9, 12),
            ProgressiveScrollRules.positionToPersist(
                pinnedToTop = false,
                firstVisibleItemIndex = 9,
                firstVisibleItemScrollOffset = 12,
            ),
        )
    }

    @Test
    fun unseenLocationStartsAtTheTop() {
        val store = FileScrollPositionStore()

        assertEquals(FileScrollPosition(), store.read(FileScrollKey("tab-a", "/new", grid = false)))
    }

    @Test
    fun positionsAreIndependentByTabPathAndViewMode() {
        val store = FileScrollPositionStore()
        val list = FileScrollKey("tab-a", "/photos", grid = false)
        val grid = list.copy(grid = true)
        val otherTab = list.copy(tabId = "tab-b")
        val otherPath = list.copy(path = "/documents")

        store.write(list, 24, 17)
        store.write(grid, 8, 3)
        store.write(otherTab, 11, 5)
        store.write(otherPath, 4, 1)

        assertEquals(FileScrollPosition(24, 17), store.read(list))
        assertEquals(FileScrollPosition(8, 3), store.read(grid))
        assertEquals(FileScrollPosition(11, 5), store.read(otherTab))
        assertEquals(FileScrollPosition(4, 1), store.read(otherPath))
    }

    @Test
    fun directNavigationResetClearsBothViewsOnlyForTheTargetLocation() {
        val store = FileScrollPositionStore()
        val list = FileScrollKey("tab-a", "/photos", grid = false)
        val grid = list.copy(grid = true)
        val other = list.copy(path = "/documents")
        store.write(list, 20, 2)
        store.write(grid, 6, 4)
        store.write(other, 9, 1)

        store.reset("tab-a", "/photos")

        assertEquals(FileScrollPosition(), store.read(list))
        assertEquals(FileScrollPosition(), store.read(grid))
        assertEquals(FileScrollPosition(9, 1), store.read(other))
    }

    @Test
    fun oldestPositionIsEvictedAtTheConfiguredLimit() {
        val store = FileScrollPositionStore(maxEntries = 2)
        val first = FileScrollKey("tab", "/first", grid = false)
        val second = FileScrollKey("tab", "/second", grid = false)
        val third = FileScrollKey("tab", "/third", grid = false)
        store.write(first, 1, 0)
        store.write(second, 2, 0)

        store.write(third, 3, 0)

        assertEquals(2, store.size())
        assertEquals(FileScrollPosition(), store.read(first))
        assertEquals(FileScrollPosition(2, 0), store.read(second))
        assertEquals(FileScrollPosition(3, 0), store.read(third))
    }
}
