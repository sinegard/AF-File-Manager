package com.affilemanager.app.pdfsigning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfVisualSignatureRulesTest {
    @Test
    fun placementIsBoundedWhenMovedAndResized() {
        val initial = VisualSignatureRules.defaultPlacement(pageIndex = 2, pageAspectRatio = 0.75f)
        val resized = VisualSignatureRules.resize(initial, requestedWidth = 5f, pageAspectRatio = 0.75f)
        val moved = VisualSignatureRules.move(resized, deltaX = 5f, deltaY = -5f)

        assertEquals(0.90f, moved.width, 0.0001f)
        assertEquals(0.10f, moved.left, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        VisualSignatureRules.validatePlacement(moved, pageCount = 3)
    }

    @Test
    fun cornerResizeKeepsTheOppositeCornerFixedAndStaysOnThePage() {
        val initial = VisualSignatureRules.defaultPlacement(pageIndex = 0, pageAspectRatio = 0.75f)

        val enlarged = VisualSignatureRules.resizeFromBottomRight(initial, requestedWidth = 0.95f, pageAspectRatio = 0.75f)
        val reduced = VisualSignatureRules.resizeFromBottomRight(enlarged, requestedWidth = 0.01f, pageAspectRatio = 0.75f)

        assertEquals(initial.left, enlarged.left, 0.0001f)
        assertEquals(initial.top, enlarged.top, 0.0001f)
        assertTrue(enlarged.left + enlarged.width <= 1.0001f)
        assertTrue(enlarged.top + enlarged.height <= 1.0001f)
        assertEquals(0.20f, reduced.width, 0.0001f)
        VisualSignatureRules.validatePlacement(enlarged, pageCount = 1)
        VisualSignatureRules.validatePlacement(reduced, pageCount = 1)
    }

    @Test
    fun allRightAngleRotationsMapToTheSameVisibleRectangle() {
        val placement = PdfSignaturePlacement(
            pageIndex = 0,
            left = 0.20f,
            top = 0.30f,
            width = 0.40f,
            height = 0.15f,
        )

        listOf(0, 90, 180, 270).forEach { rotation ->
            val matrix = VisualSignatureRules.imageMatrix(
                cropLeft = 10f,
                cropBottom = 20f,
                cropWidth = 600f,
                cropHeight = 800f,
                pageRotation = rotation,
                placement = placement,
            )
            listOf(
                Triple(0f, 0f, 0f),
                Triple(1f, 0f, 0f),
                Triple(0f, 1f, 1f),
                Triple(1f, 1f, 1f),
            ).forEach { (imageX, imageY, verticalEnd) ->
                val pdfX = matrix.a * imageX + matrix.c * imageY + matrix.e
                val pdfY = matrix.b * imageX + matrix.d * imageY + matrix.f
                val (displayX, displayY) = pdfToDisplayed(pdfX, pdfY, rotation)
                assertEquals(placement.left + placement.width * imageX, displayX, 0.0001f)
                assertEquals(
                    1f - placement.top - placement.height + placement.height * verticalEnd,
                    displayY,
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun drawingLimitsAndCoordinatesAreEnforced() {
        val valid = SignatureDrawing(
            strokes = listOf(SignatureStroke(listOf(SignaturePoint(0f, 0f), SignaturePoint(1f, 1f)))),
        )
        assertEquals(valid, VisualSignatureRules.validate(valid))
        assertEquals(SignaturePoint(0f, 1f), VisualSignatureRules.point(Float.NaN, 9f))

        val oversized = SignatureDrawing(
            strokes = List(VisualSignatureRules.MAX_STROKES + 1) {
                SignatureStroke(listOf(SignaturePoint(0.5f, 0.5f)))
            },
        )
        assertTrue(runCatching { VisualSignatureRules.validate(oversized) }.isFailure)
    }

    private fun pdfToDisplayed(pdfX: Float, pdfY: Float, rotation: Int): Pair<Float, Float> {
        val x = (pdfX - 10f) / 600f
        val y = (pdfY - 20f) / 800f
        return when (rotation) {
            0 -> x to y
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            else -> (1f - y) to x
        }
    }
}
