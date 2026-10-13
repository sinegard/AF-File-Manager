package com.affilemanager.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class ImageCropRulesTest {
    @Test fun initialCropAndMovesStayInsideTheImage() {
        listOf<Float?>(null, .01f, .5f, 1f, 2f, 100f).forEach { ratio ->
            val crop = ImageCropRegion.centered(ratio).moved(500f, -500f)
            assertTrue(crop.x >= 0 && crop.y >= 0 && crop.x + crop.width <= 1.00001f && crop.y + crop.height <= 1.00001f)
            ratio?.let { assertEquals(it, crop.width / crop.height, .0001f) }
        }
    }
    @Test fun cornerResizingMaintainsAspectAndBoundsEvenAtTheEdge() {
        val original = ImageCropRegion.centered(16f / 9f)
        val grown = original.resized(500f, 500f, 16f / 9f)
        val shrunk = grown.moved(10f, 10f).resized(-500f, -500f, 16f / 9f)
        assertEquals(16f / 9f, shrunk.width / shrunk.height, .0001f)
        assertTrue(shrunk.width > 0 && shrunk.height > 0 && grown.x + grown.width <= 1.0001f)
    }
    @Test fun freeCropCanBeResizedAfterBeingDraggedToTheBottomRight() {
        val crop = ImageCropRegion.centered(null).resized(-500f, -500f, null).moved(500f, 500f).resized(-500f, -500f, null)
        assertTrue(crop.width > 0 && crop.height > 0)
    }
}
