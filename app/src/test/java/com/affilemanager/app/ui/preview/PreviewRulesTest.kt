package com.affilemanager.app.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRulesTest {
    @Test
    fun zoomScaleAndButtonsStayWithinDeclaredBounds() {
        assertEquals(1f, PreviewZoomRules.clamp(0.1f, PreviewZoomRules.IMAGE_MAX_SCALE))
        assertEquals(5f, PreviewZoomRules.clamp(8f, PreviewZoomRules.IMAGE_MAX_SCALE))
        assertEquals(1.25f, PreviewZoomRules.zoomIn(1f, PreviewZoomRules.IMAGE_MAX_SCALE))
        assertEquals(1f, PreviewZoomRules.zoomOut(1f, PreviewZoomRules.IMAGE_MAX_SCALE))
        assertEquals(125, PreviewZoomRules.percent(1.25f))
    }

    @Test
    fun pdfRenderDimensionsFollowZoomButStayBoundedAndPreserveAspectRatio() {
        val normal = PdfRenderRules.pageSizeForViewport(595, 842, viewportWidthPx = 1_080, zoom = 1f)
        val zoomed = PdfRenderRules.pageSizeForViewport(595, 842, viewportWidthPx = 1_080, zoom = 2f)
        val extreme = PdfRenderRules.pageSizeForViewport(595, 842, viewportWidthPx = 2_560, zoom = 4f)

        assertTrue(zoomed.first > normal.first)
        assertTrue(zoomed.second > normal.second)
        assertEquals(2.0, zoomed.first.toDouble() / normal.first, 0.01)
        assertTrue(extreme.first <= PdfRenderRules.MAX_WIDTH_PX)
        assertTrue(extreme.first.toLong() * extreme.second <= PdfRenderRules.MAX_PIXELS)
        assertEquals(595.0 / 842.0, extreme.first.toDouble() / extreme.second, 0.002)
    }

    @Test
    fun mediaSeekAndLabelsStayInsideTheKnownDuration() {
        assertEquals(0L, MediaPlaybackRules.skippedPosition(2_000L, 30_000L, -10_000L))
        assertEquals(30_000L, MediaPlaybackRules.skippedPosition(28_000L, 30_000L, 10_000L))
        assertEquals(15_000L, MediaPlaybackRules.positionForProgress(0.5f, 30_000L))
        assertEquals(0.5f, MediaPlaybackRules.progress(15_000L, 30_000L))
        assertEquals("1:05", MediaPlaybackRules.timeLabel(65_000L))
        assertEquals("1:01:05", MediaPlaybackRules.timeLabel(3_665_000L))
    }

    @Test
    fun mediaNavigationWrapsInBothDirections() {
        val items = listOf("a", "b", "c")
        assertEquals("b", MediaNavigationRules.next(items, "a", 1) { it })
        assertEquals("a", MediaNavigationRules.next(items, "c", 1) { it })
        assertEquals("c", MediaNavigationRules.next(items, "a", -1) { it })
        assertEquals(null, MediaNavigationRules.next(listOf("a"), "a", 1) { it })
    }
}
