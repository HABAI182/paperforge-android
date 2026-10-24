package cn.paperforge.app.model

import kotlin.math.max
import kotlin.math.min

data class PageViewport(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    fun source(point: PointN) = PointN(left + point.x * width, top + point.y * height)

    fun view(point: PointN) = PointN((point.x - left) / width, (point.y - top) / height)

    companion object {
        val Full = PageViewport()
    }
}

data class ReadingFrame(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val pageWidth: Float,
    val pageHeight: Float,
) {
    fun limitX(zoom: Float) = max(0f, (pageWidth * zoom - viewportWidth) / (2 * pageWidth))

    fun limitY(zoom: Float) = max(0f, (pageHeight * zoom - viewportHeight) / (2 * pageHeight))

    fun point(x: Float, y: Float, zoom: Float, panX: Float, panY: Float) =
        PointN(
            .5f + (x - viewportWidth / 2 - panX * pageWidth) / (pageWidth * zoom),
            .5f + (y - viewportHeight / 2 - panY * pageHeight) / (pageHeight * zoom),
        )

    companion object {
        fun fit(width: Float, height: Float, pageRatio: Float, fitWidth: Boolean): ReadingFrame {
            val w = width.coerceAtLeast(1f)
            val h = height.coerceAtLeast(1f)
            val pageW = if (fitWidth) w else min(w, h * pageRatio)
            return ReadingFrame(w, h, pageW, pageW / pageRatio)
        }
    }
}
