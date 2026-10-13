package com.affilemanager.app.ui.components

internal data class ImageCropRegion(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun moved(dx: Float, dy: Float) = copy(x = (x + dx).coerceIn(0f, 1f - width), y = (y + dy).coerceIn(0f, 1f - height))
    fun resized(dx: Float, dy: Float, ratio: Float?): ImageCropRegion {
        val maximumWidth = 1f - x
        val maximumHeight = 1f - y
        if (ratio == null) return copy(width = (width + dx).coerceIn(minOf(.05f, maximumWidth), maximumWidth),
            height = (height + dy).coerceIn(minOf(.05f, maximumHeight), maximumHeight))
        val maximum = minOf(maximumWidth, maximumHeight * ratio)
        val minimum = minOf(.05f, maximum)
        val candidate = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy * ratio)) width + dx else width + dy * ratio
        val next = candidate.coerceIn(minimum, maximum)
        return copy(width = next, height = next / ratio)
    }
    companion object {
        fun centered(ratio: Float?): ImageCropRegion {
            require(ratio == null || ratio.isFinite() && ratio > 0)
            val width = if (ratio == null) .85f else minOf(.85f, .85f * ratio)
            val height = if (ratio == null) .85f else width / ratio
            return ImageCropRegion((1 - width) / 2, (1 - height) / 2, width, height)
        }
    }
}
