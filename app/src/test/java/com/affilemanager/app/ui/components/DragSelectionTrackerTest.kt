package com.affilemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragSelectionTrackerTest {
    @Test
    fun unselectedStartSelectsEveryCrossedIndexWithoutRetoggling() {
        val tracker = DragSelectionTracker { false }

        assertEquals(DragSelectionChange(2..2, selected = true), tracker.start(2))
        assertEquals(DragSelectionChange(2..5, selected = true), tracker.moveTo(5))
        assertNull(tracker.moveTo(5))
        assertEquals(DragSelectionChange(3..5, selected = true), tracker.moveTo(3))
    }

    @Test
    fun selectedStartKeepsDeselectModeForTheWholeGesture() {
        val tracker = DragSelectionTracker { index -> index == 4 }

        assertEquals(DragSelectionChange(4..4, selected = false), tracker.start(4))
        assertEquals(DragSelectionChange(1..4, selected = false), tracker.moveTo(1))
    }
}
