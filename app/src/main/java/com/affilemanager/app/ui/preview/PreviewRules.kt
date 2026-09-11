package com.affilemanager.app.ui.preview

import java.util.Locale
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

    fun anchoredScrollOffset(
        currentScroll: Float,
        focusInViewport: Float,
        oldScale: Float,
        newScale: Float,
    ): Float {
        require(currentScroll.isFinite() && currentScroll >= 0f) { "Invalid PDF scroll offset" }
        require(focusInViewport.isFinite() && focusInViewport >= 0f) { "Invalid PDF zoom focus" }
        require(oldScale.isFinite() && oldScale > 0f) { "Invalid previous PDF scale" }
        require(newScale.isFinite() && newScale > 0f) { "Invalid next PDF scale" }
        return (((currentScroll + focusInViewport) / oldScale) * newScale - focusInViewport)
            .coerceAtLeast(0f)
    }
}

internal object PdfTextRules {
    const val MAX_SOURCE_BYTES = 256L * 1_024L * 1_024L
    const val MAX_PAGE_TEXT_CHARS = 256 * 1_024
    const val MAX_SELECTION_RECTS = 8_192
    const val SCRATCH_ROOT_NAME = "pdf-text-selection"

    fun requireSourceSize(sizeBytes: Long?): Long? = sizeBytes?.also {
        require(it in 1..MAX_SOURCE_BYTES) { "Failas per didelis peržiūrai" }
    }

    fun requirePageIndex(pageIndex: Int, pageCount: Int): Int = pageIndex.also {
        require(pageCount in 1..PdfRenderRules.MAX_PAGE_COUNT && it in 0 until pageCount) {
            "PDF puslapis nepasiekiamas"
        }
    }

    fun requireTextLength(characters: Int): Int = characters.also {
        require(it in 0..MAX_PAGE_TEXT_CHARS) { "Failas per didelis peržiūrai" }
    }

    fun requireSelectionRectCount(rectangles: Int): Int = rectangles.also {
        require(it in 0..MAX_SELECTION_RECTS) { "Failas per didelis peržiūrai" }
    }

    fun pageClosestToViewportCenter(
        visiblePages: List<PdfVisiblePage>,
        viewportStart: Int,
        viewportEnd: Int,
    ): Int {
        require(visiblePages.isNotEmpty()) { "PDF puslapis nepasiekiamas" }
        require(viewportEnd > viewportStart) { "PDF puslapis nepasiekiamas" }
        val viewportCenter = (viewportStart.toLong() + viewportEnd.toLong()) / 2L
        return visiblePages.minBy { page ->
            val pageCenter = page.offset.toLong() + page.size.toLong() / 2L
            kotlin.math.abs(pageCenter - viewportCenter)
        }.index
    }

    fun pageAtViewportPosition(
        visiblePages: List<PdfVisiblePage>,
        position: Float,
    ): PdfVisiblePage {
        require(visiblePages.isNotEmpty() && position.isFinite()) { "PDF puslapis nepasiekiamas" }
        visiblePages.firstOrNull { page ->
            position >= page.offset && position < page.offset.toLong() + page.size.toLong()
        }?.let { return it }
        return visiblePages.minBy { page ->
            kotlin.math.abs(page.offset.toDouble() + page.size.toDouble() / 2.0 - position)
        }
    }
}

internal data class PdfPageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Netinkamas PDF puslapio dydis" }
    }

    val aspectRatio: Float get() = width.toFloat() / height
}

internal data class PdfPagePoint(val x: Float, val y: Float)
internal data class PdfDisplayPoint(val x: Float, val y: Float)
internal data class PdfDisplaySize(val width: Float, val height: Float)

internal enum class PdfSelectionHandle { START, STOP }

internal object PdfSelectionRules {
    fun pagePoint(
        displayPoint: PdfDisplayPoint,
        displaySize: PdfDisplaySize,
        pageSize: PdfPageSize,
    ): PdfPagePoint {
        require(displayPoint.x.isFinite() && displayPoint.y.isFinite()) { "PDF perskaityti nepavyko" }
        require(displaySize.width.isFinite() && displaySize.width > 0f) { "Netinkamas PDF peržiūros plotis" }
        require(displaySize.height.isFinite() && displaySize.height > 0f) { "PDF perskaityti nepavyko" }
        return PdfPagePoint(
            x = (displayPoint.x / displaySize.width * pageSize.width).coerceIn(0f, pageSize.width.toFloat()),
            y = (displayPoint.y / displaySize.height * pageSize.height).coerceIn(0f, pageSize.height.toFloat()),
        )
    }

    fun displayPoint(
        pagePoint: PdfPagePoint,
        displaySize: PdfDisplaySize,
        pageSize: PdfPageSize,
    ): PdfDisplayPoint {
        require(pagePoint.x.isFinite() && pagePoint.y.isFinite()) { "PDF perskaityti nepavyko" }
        require(displaySize.width.isFinite() && displaySize.width > 0f) { "Netinkamas PDF peržiūros plotis" }
        require(displaySize.height.isFinite() && displaySize.height > 0f) { "PDF perskaityti nepavyko" }
        return PdfDisplayPoint(
            x = pagePoint.x.coerceIn(0f, pageSize.width.toFloat()) / pageSize.width * displaySize.width,
            y = pagePoint.y.coerceIn(0f, pageSize.height.toFloat()) / pageSize.height * displaySize.height,
        )
    }

    fun handleAt(
        touch: PdfDisplayPoint,
        start: PdfDisplayPoint,
        stop: PdfDisplayPoint,
        hitRadius: Float,
    ): PdfSelectionHandle? {
        require(hitRadius.isFinite() && hitRadius > 0f) { "PDF perskaityti nepavyko" }
        val startDistance = distanceSquared(touch, start)
        val stopDistance = distanceSquared(touch, stop)
        val maximumDistance = hitRadius * hitRadius
        return when {
            startDistance <= maximumDistance && startDistance <= stopDistance -> PdfSelectionHandle.START
            stopDistance <= maximumDistance -> PdfSelectionHandle.STOP
            else -> null
        }
    }

    private fun distanceSquared(first: PdfDisplayPoint, second: PdfDisplayPoint): Float {
        require(
            first.x.isFinite() && first.y.isFinite() && second.x.isFinite() && second.y.isFinite(),
        ) { "PDF perskaityti nepavyko" }
        val x = first.x - second.x
        val y = first.y - second.y
        return x * x + y * y
    }
}

internal data class PdfVisiblePage(
    val index: Int,
    val offset: Int,
    val size: Int,
)

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

internal object MediaPlaybackRules {
    const val SKIP_MILLIS = 10_000L

    fun boundedPosition(positionMillis: Long, durationMillis: Long): Long =
        positionMillis.coerceIn(0L, durationMillis.coerceAtLeast(0L))

    fun skippedPosition(positionMillis: Long, durationMillis: Long, deltaMillis: Long): Long =
        boundedPosition(positionMillis + deltaMillis, durationMillis)

    fun positionForProgress(progress: Float, durationMillis: Long): Long {
        if (!progress.isFinite() || durationMillis <= 0L) return 0L
        return (progress.coerceIn(0f, 1f) * durationMillis).toLong().coerceIn(0L, durationMillis)
    }

    fun progress(positionMillis: Long, durationMillis: Long): Float =
        if (durationMillis <= 0L) 0f else boundedPosition(positionMillis, durationMillis).toFloat() / durationMillis

    fun timeLabel(positionMillis: Long): String {
        val totalSeconds = positionMillis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal object MediaNavigationRules {
    fun <T> next(items: List<T>, currentKey: String, delta: Int, key: (T) -> String): T? {
        if (items.size < 2 || delta == 0) return null
        val currentIndex = items.indexOfFirst { key(it) == currentKey }
        if (currentIndex < 0) return null
        return items[Math.floorMod(currentIndex + delta, items.size)]
    }
}
