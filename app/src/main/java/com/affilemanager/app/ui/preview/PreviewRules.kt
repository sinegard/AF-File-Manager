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
