package cn.paperforge.app.model

import kotlin.math.ceil
import kotlin.math.floor

data class TileKey(val edge: Int, val column: Int, val row: Int)

object VisibleTiles {
    fun plan(
        width: Int,
        height: Int,
        zoom: Float,
        panX: Float,
        panY: Float,
        viewport: PageViewport = PageViewport.Full,
        viewportRatioX: Float = 1f,
        viewportRatioY: Float = 1f,
    ): List<TileKey> {
        val detail = zoom / minOf(viewport.width, viewport.height)
        if (detail <= 1.5f) return emptyList()
        val edge =
            when {
                detail <= 2.5f -> 3200
                detail <= 4.5f -> 6400
                detail <= 6f -> 9600
                else -> 12800
            }
        val scale = edge.toFloat() / maxOf(width, height)
        val w = ceil(width * scale).toInt()
        val h = ceil(height * scale).toInt()
        val left =
            viewport.left +
                (.5f + (-.5f * viewportRatioX - panX) / zoom).coerceIn(0f, 1f) * viewport.width
        val right =
            viewport.left +
                (.5f + (.5f * viewportRatioX - panX) / zoom).coerceIn(0f, 1f) * viewport.width
        val top =
            viewport.top +
                (.5f + (-.5f * viewportRatioY - panY) / zoom).coerceIn(0f, 1f) * viewport.height
        val bottom =
            viewport.top +
                (.5f + (.5f * viewportRatioY - panY) / zoom).coerceIn(0f, 1f) * viewport.height
        if (right <= left || bottom <= top) return emptyList()
        val columns = ceil(w / 512f).toInt()
        val rows = ceil(h / 512f).toInt()
        val x0 = floor(left * w / 512).toInt().coerceIn(0, columns - 1)
        val x1 = (ceil(right * w / 512).toInt() - 1).coerceIn(0, columns - 1)
        val y0 = floor(top * h / 512).toInt().coerceIn(0, rows - 1)
        val y1 = (ceil(bottom * h / 512).toInt() - 1).coerceIn(0, rows - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileKey(edge, x, y) } }
    }
}
