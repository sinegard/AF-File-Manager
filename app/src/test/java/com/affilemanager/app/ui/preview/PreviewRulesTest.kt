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
}
