package com.affilemanager.app.ui.preview

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object PreviewZoomRules {
    const val MIN_SCALE = 1f
    const val IMAGE_MAX_SCALE = 5f
    const val PDF_MAX_SCALE = 4f
    private const val STEP = 0.25f

    fun clamp(scale: Float, maximum: Float): Float = scale.coerceIn(MIN_SCALE, maximum)
    fun zoomIn(scale: Float, maximum: Float): Float = clamp(scale + STEP, maximum)
    fun zoomOut(scale: Float, maximum: Float): Float = clamp(scale - STEP, maximum)
    fun percent(scale: Float): Int = (scale * 100).roundToInt()
}

internal object PdfRenderRules {
    const val MAX_PAGE_COUNT = 5_000
    internal const val MAX_WIDTH_PX = 4_096
    internal const val MAX_PIXELS = 12_000_000
    private const val MAX_UPSCALE = 8f

    fun pageSizeForViewport(
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidthPx: Int,
        zoom: Float,
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Netinkamas PDF puslapio dydis" }
        require(viewportWidthPx > 0) { "Netinkamas PDF peržiūros plotis" }
        require(zoom.isFinite() && zoom > 0f) { "Netinkamas PDF mastelis" }
        val requestedWidth = viewportWidthPx.toDouble() * zoom
        val requestedScale = requestedWidth / sourceWidth
        val widthScale = MAX_WIDTH_PX.toDouble() / sourceWidth
        val pixelScale = sqrt(MAX_PIXELS.toDouble() / (sourceWidth.toDouble() * sourceHeight))
        val scale = minOf(MAX_UPSCALE.toDouble(), requestedScale, widthScale, pixelScale)
        return (sourceWidth * scale).toInt().coerceAtLeast(1) to
            (sourceHeight * scale).toInt().coerceAtLeast(1)
    }
}
