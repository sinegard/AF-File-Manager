package com.affilemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollMappingTest {
    @Test fun pointerMapsToBoundedListPositions() {
        assertEquals(0, FastScrollMapping.targetIndex(-100f, 1_000f, 100f, 1_000, 100))
        assertEquals(450, FastScrollMapping.targetIndex(500f, 1_000f, 100f, 1_000, 100))
        assertEquals(900, FastScrollMapping.targetIndex(2_000f, 1_000f, 100f, 1_000, 100))
        assertEquals(0, FastScrollMapping.targetIndex(500f, 1_000f, 100f, 10, 10))
    }

    @Test fun thumbPositionUsesScrollableRange() {
        assertEquals(0f, FastScrollMapping.thumbFraction(0, 100, 10))
        assertEquals(.5f, FastScrollMapping.thumbFraction(45, 100, 10))
        assertEquals(1f, FastScrollMapping.thumbFraction(90, 100, 10))
    }
}
