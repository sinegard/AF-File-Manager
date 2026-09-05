package com.affilemanager.app.pdfsigning

import kotlin.math.max
import kotlin.math.min

data class SignaturePoint(
    val x: Float,
    val y: Float,
)

data class SignatureStroke(
    val points: List<SignaturePoint>,
)

data class SignatureDrawing(
    val strokes: List<SignatureStroke>,
) {
    val pointCount: Int get() = strokes.sumOf { it.points.size }
    val isEmpty: Boolean get() = strokes.none { it.points.isNotEmpty() }
}

data class PdfSignaturePlacement(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

data class PdfImageMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
)

object VisualSignatureRules {
    const val MAX_STROKES = 64
    const val MAX_POINTS = 4_096
    const val BITMAP_WIDTH = 1_200
    const val BITMAP_HEIGHT = 400
    const val ASPECT_RATIO = BITMAP_WIDTH.toFloat() / BITMAP_HEIGHT
    private const val MIN_WIDTH = 0.20f
    private const val MAX_WIDTH = 0.90f

    fun point(x: Float, y: Float): SignaturePoint = SignaturePoint(
        x = x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
        y = y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
    )

    fun validate(drawing: SignatureDrawing): SignatureDrawing {
        require(!drawing.isEmpty) { "Parašas tuščias" }
        require(drawing.strokes.size <= MAX_STROKES) { "Paraše per daug brūkšnių" }
        require(drawing.pointCount <= MAX_POINTS) { "Paraše per daug taškų" }
        drawing.strokes.forEach { stroke ->
            require(stroke.points.isNotEmpty()) { "Paraše yra tuščias brūkšnys" }
            stroke.points.forEach { point ->
                require(point.x.isFinite() && point.y.isFinite()) { "Parašo taškas netinkamas" }
                require(point.x in 0f..1f && point.y in 0f..1f) { "Parašo taškas yra už piešimo srities" }
            }
        }
        return drawing
    }

    fun defaultPlacement(pageIndex: Int, pageAspectRatio: Float): PdfSignaturePlacement {
        require(pageIndex >= 0) { "PDF puslapis nepasiekiamas" }
        val width = 0.55f
        val height = heightForWidth(width, pageAspectRatio)
        return PdfSignaturePlacement(
            pageIndex = pageIndex,
            left = (1f - width) / 2f,
            top = (0.86f - height).coerceAtLeast(0f),
            width = width,
            height = height,
        )
    }

    fun resize(
        placement: PdfSignaturePlacement,
        requestedWidth: Float,
        pageAspectRatio: Float,
    ): PdfSignaturePlacement {
        val width = requestedWidth.takeIf(Float::isFinite)?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: placement.width
        val height = heightForWidth(width, pageAspectRatio)
        return placement.copy(
            left = placement.left.coerceIn(0f, 1f - width),
            top = placement.top.coerceIn(0f, 1f - height),
            width = width,
            height = height,
        )
    }

    fun resizeFromBottomRight(
        placement: PdfSignaturePlacement,
        requestedWidth: Float,
        pageAspectRatio: Float,
    ): PdfSignaturePlacement {
        require(pageAspectRatio.isFinite() && pageAspectRatio > 0f) { "PDF puslapio geometrija netinkama" }
        val availableWidth = (1f - placement.left).coerceAtLeast(MIN_WIDTH)
        val availableHeightAsWidth = ((1f - placement.top) * ASPECT_RATIO / pageAspectRatio)
            .coerceAtLeast(MIN_WIDTH)
        val maximum = min(MAX_WIDTH, min(availableWidth, availableHeightAsWidth))
        val width = requestedWidth.takeIf(Float::isFinite)?.coerceIn(MIN_WIDTH, maximum) ?: placement.width
        return placement.copy(
            width = width,
            height = heightForWidth(width, pageAspectRatio),
        )
    }

    fun move(
        placement: PdfSignaturePlacement,
        deltaX: Float,
        deltaY: Float,
    ): PdfSignaturePlacement = placement.copy(
        left = (placement.left + deltaX.takeIf(Float::isFinite).orZero()).coerceIn(0f, 1f - placement.width),
        top = (placement.top + deltaY.takeIf(Float::isFinite).orZero()).coerceIn(0f, 1f - placement.height),
    )

    fun validatePlacement(placement: PdfSignaturePlacement, pageCount: Int): PdfSignaturePlacement {
        require(pageCount in 1..5_000) { "PDF puslapių skaičius nepalaikomas" }
        require(placement.pageIndex in 0 until pageCount) { "PDF puslapis nepasiekiamas" }
        val values = listOf(placement.left, placement.top, placement.width, placement.height)
        require(values.all(Float::isFinite)) { "Parašo vieta netinkama" }
        require(placement.width in MIN_WIDTH..MAX_WIDTH && placement.height > 0f) { "Parašo dydis netinkamas" }
        require(placement.left >= 0f && placement.top >= 0f) { "Parašo vieta netinkama" }
        require(placement.left + placement.width <= 1.0001f) { "Parašas netelpa PDF puslapyje" }
        require(placement.top + placement.height <= 1.0001f) { "Parašas netelpa PDF puslapyje" }
        return placement
    }

    /**
     * Maps a signature image's unit square into the unrotated PDF crop box.
     * [PdfSignaturePlacement] is expressed in the page orientation shown to the user.
     */
    fun imageMatrix(
        cropLeft: Float,
        cropBottom: Float,
        cropWidth: Float,
        cropHeight: Float,
        pageRotation: Int,
        placement: PdfSignaturePlacement,
    ): PdfImageMatrix {
        require(listOf(cropLeft, cropBottom, cropWidth, cropHeight).all(Float::isFinite)) { "PDF puslapio geometrija netinkama" }
        require(cropWidth > 0f && cropHeight > 0f) { "PDF puslapio geometrija netinkama" }
        val rotation = ((pageRotation % 360) + 360) % 360
        require(rotation % 90 == 0) { "PDF puslapio pasukimas nepalaikomas" }
        val p = validatePlacement(placement, max(placement.pageIndex + 1, 1))
        return when (rotation) {
            0 -> PdfImageMatrix(
                a = cropWidth * p.width,
                b = 0f,
                c = 0f,
                d = cropHeight * p.height,
                e = cropLeft + cropWidth * p.left,
                f = cropBottom + cropHeight * (1f - p.top - p.height),
            )
            90 -> PdfImageMatrix(
                a = 0f,
                b = cropHeight * p.width,
                c = -cropWidth * p.height,
                d = 0f,
                e = cropLeft + cropWidth * (p.top + p.height),
                f = cropBottom + cropHeight * p.left,
            )
            180 -> PdfImageMatrix(
                a = -cropWidth * p.width,
                b = 0f,
                c = 0f,
                d = -cropHeight * p.height,
                e = cropLeft + cropWidth * (1f - p.left),
                f = cropBottom + cropHeight * (p.top + p.height),
            )
            else -> PdfImageMatrix(
                a = 0f,
                b = -cropHeight * p.width,
                c = cropWidth * p.height,
                d = 0f,
                e = cropLeft + cropWidth * (1f - p.top - p.height),
                f = cropBottom + cropHeight * (1f - p.left),
            )
        }
    }

    private fun heightForWidth(width: Float, pageAspectRatio: Float): Float {
        require(pageAspectRatio.isFinite() && pageAspectRatio > 0f) { "PDF puslapio geometrija netinkama" }
        return (width * pageAspectRatio / ASPECT_RATIO).coerceIn(0.04f, 0.45f)
    }

    private fun Float?.orZero(): Float = this ?: 0f
}
