package com.affilemanager.app.ui.preview

import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

internal sealed interface PdfTextBoundary {
    data class AtPoint(val point: PdfPagePoint) : PdfTextBoundary
}

internal data class PdfSelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class PdfPageTextSelection(
    val pageIndex: Int,
    val text: String,
    val bounds: List<PdfSelectionRect>,
    val startHandle: PdfPagePoint,
    val stopHandle: PdfPagePoint,
)

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal fun selectPdfPageText(
    context: Context,
    source: PreviewSource,
    pageIndex: Int,
    start: PdfTextBoundary,
    stop: PdfTextBoundary,
): PdfPageTextSelection? {
    PdfTextRules.requireSourceSize(source.sizeBytes)
    return source.openFileDescriptor(context).use { descriptor ->
        val knownSize = descriptor.statSize.takeIf { it >= 0L } ?: source.sizeBytes
        PdfTextRules.requireSourceSize(requireNotNull(knownSize) { "PDF perskaityti nepavyko" })
        PdfRenderer(descriptor).use { renderer ->
            PdfTextRules.requirePageIndex(pageIndex, renderer.pageCount)
            renderer.openPage(pageIndex).use { page ->
                val pageSize = PdfPageSize(page.width, page.height)
                val selection = page.selectContent(
                    start.toNativeBoundary(pageSize),
                    stop.toNativeBoundary(pageSize),
                ) ?: return@use null
                val selectedContents = selection.selectedTextContents
                val textBuilder = StringBuilder()
                selectedContents.forEach { content ->
                    PdfTextRules.requireTextLength(Math.addExact(textBuilder.length, content.text.length))
                    textBuilder.append(content.text)
                }
                val text = textBuilder.toString().trim()
                if (text.isEmpty()) return@use null

                val safeBounds = ArrayList<PdfSelectionRect>()
                selectedContents.forEach { content ->
                    content.bounds.forEach { bounds ->
                        PdfTextRules.requireSelectionRectCount(Math.addExact(safeBounds.size, 1))
                        bounds.toSafeSelectionRect(pageSize)?.let(safeBounds::add)
                    }
                }
                if (safeBounds.isEmpty()) return@use null

                val startPoint = selection.start.point?.toPagePoint(pageSize)
                    ?: safeBounds.first().startHandle()
                val stopPoint = selection.stop.point?.toPagePoint(pageSize)
                    ?: safeBounds.last().stopHandle()
                PdfPageTextSelection(
                    pageIndex = pageIndex,
                    text = text,
                    bounds = safeBounds,
                    startHandle = startPoint,
                    stopHandle = stopPoint,
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun PdfTextBoundary.toNativeBoundary(pageSize: PdfPageSize): SelectionBoundary = when (this) {
    is PdfTextBoundary.AtPoint -> SelectionBoundary(
        Point(
            point.x.coerceIn(0f, pageSize.width.toFloat()).roundToInt(),
            point.y.coerceIn(0f, pageSize.height.toFloat()).roundToInt(),
        ),
    )
}

private fun RectF.toSafeSelectionRect(pageSize: PdfPageSize): PdfSelectionRect? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    val safeLeft = minOf(left, right).coerceIn(0f, pageSize.width.toFloat())
    val safeRight = maxOf(left, right).coerceIn(0f, pageSize.width.toFloat())
    val safeTop = minOf(top, bottom).coerceIn(0f, pageSize.height.toFloat())
    val safeBottom = maxOf(top, bottom).coerceIn(0f, pageSize.height.toFloat())
    if (safeRight <= safeLeft || safeBottom <= safeTop) return null
    return PdfSelectionRect(safeLeft, safeTop, safeRight, safeBottom)
}

private fun Point.toPagePoint(pageSize: PdfPageSize): PdfPagePoint = PdfPagePoint(
    x = x.toFloat().coerceIn(0f, pageSize.width.toFloat()),
    y = y.toFloat().coerceIn(0f, pageSize.height.toFloat()),
)

private fun PdfSelectionRect.startHandle(): PdfPagePoint = PdfPagePoint(left, bottom)
private fun PdfSelectionRect.stopHandle(): PdfPagePoint = PdfPagePoint(right, bottom)
