package com.affilemanager.app.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun pdfZoomKeepsTheDocumentPointUnderTheFingerMidpoint() {
        val oldScale = 1.5f
        val newScale = 3f
        val oldScroll = 240f
        val fingerMidpoint = 360f

        val newScroll = PreviewZoomRules.anchoredScrollOffset(
            currentScroll = oldScroll,
            focusInViewport = fingerMidpoint,
            oldScale = oldScale,
            newScale = newScale,
        )

        assertEquals(
            (oldScroll + fingerMidpoint) / oldScale,
            (newScroll + fingerMidpoint) / newScale,
            0.001f,
        )
        assertEquals(840f, newScroll, 0.001f)
        assertEquals(
            0f,
            PreviewZoomRules.anchoredScrollOffset(
                currentScroll = 0f,
                focusInViewport = 360f,
                oldScale = 3f,
                newScale = 1f,
            ),
            0.001f,
        )
    }

    @Test
    fun pdfSelectableTextLimitsRejectInvalidPagesAndUnboundedContent() {
        assertEquals(1, PdfTextRules.requirePageIndex(pageIndex = 1, pageCount = 3))
        assertEquals(128L, PdfTextRules.requireSourceSize(128L))
        assertEquals(null, PdfTextRules.requireSourceSize(null))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PdfTextRules.requirePageIndex(pageIndex = 3, pageCount = 3)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PdfTextRules.requireSourceSize(PdfTextRules.MAX_SOURCE_BYTES + 1L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PdfTextRules.requireTextLength(PdfTextRules.MAX_PAGE_TEXT_CHARS + 1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PdfTextRules.requireSelectionRectCount(PdfTextRules.MAX_SELECTION_RECTS + 1)
        }
    }

    @Test
    fun pdfTextSelectionUsesThePageNearestTheViewportCenter() {
        assertEquals(
            2,
            PdfTextRules.pageClosestToViewportCenter(
                visiblePages = listOf(
                    PdfVisiblePage(index = 1, offset = -760, size = 1_000),
                    PdfVisiblePage(index = 2, offset = 248, size = 1_000),
                ),
                viewportStart = 0,
                viewportEnd = 1_000,
            ),
        )
    }

    @Test
    fun pdfPinchAnchorUsesThePageDirectlyUnderTheFingers() {
        val visiblePages = listOf(
            PdfVisiblePage(index = 1, offset = -760, size = 1_000),
            PdfVisiblePage(index = 2, offset = 248, size = 1_000),
        )

        assertEquals(1, PdfTextRules.pageAtViewportPosition(visiblePages, position = 120f).index)
        assertEquals(2, PdfTextRules.pageAtViewportPosition(visiblePages, position = 500f).index)
        assertEquals(2, PdfTextRules.pageAtViewportPosition(visiblePages, position = 2_000f).index)
    }

    @Test
    fun pdfSelectionMapsDisplayedTouchIntoClampedTopLeftPagePoints() {
        val pageSize = PdfPageSize(width = 595, height = 842)
        val displaySize = PdfDisplaySize(width = 1_190f, height = 1_684f)

        assertEquals(
            PdfPagePoint(x = 297.5f, y = 421f),
            PdfSelectionRules.pagePoint(
                displayPoint = PdfDisplayPoint(x = 595f, y = 842f),
                displaySize = displaySize,
                pageSize = pageSize,
            ),
        )
        assertEquals(
            PdfPagePoint(x = 0f, y = 842f),
            PdfSelectionRules.pagePoint(
                displayPoint = PdfDisplayPoint(x = -80f, y = 2_000f),
                displaySize = displaySize,
                pageSize = pageSize,
            ),
        )
        assertEquals(
            PdfDisplayPoint(x = 595f, y = 842f),
            PdfSelectionRules.displayPoint(
                pagePoint = PdfPagePoint(x = 297.5f, y = 421f),
                displaySize = displaySize,
                pageSize = pageSize,
            ),
        )
    }

    @Test
    fun pdfSelectionHandleHitUsesTheNearestBoundedTouchTarget() {
        val start = PdfDisplayPoint(x = 100f, y = 180f)
        val stop = PdfDisplayPoint(x = 300f, y = 180f)

        assertEquals(
            PdfSelectionHandle.START,
            PdfSelectionRules.handleAt(PdfDisplayPoint(116f, 190f), start, stop, hitRadius = 32f),
        )
        assertEquals(
            PdfSelectionHandle.STOP,
            PdfSelectionRules.handleAt(PdfDisplayPoint(285f, 168f), start, stop, hitRadius = 32f),
        )
        assertNull(
            PdfSelectionRules.handleAt(PdfDisplayPoint(200f, 180f), start, stop, hitRadius = 32f),
        )
        assertEquals(
            PdfSelectionHandle.START,
            PdfSelectionRules.handleAt(
                touch = PdfDisplayPoint(105f, 100f),
                start = PdfDisplayPoint(100f, 100f),
                stop = PdfDisplayPoint(112f, 100f),
                hitRadius = 32f,
            ),
        )
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
