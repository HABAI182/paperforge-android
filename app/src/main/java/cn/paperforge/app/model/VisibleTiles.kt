package cn.paperforge.app.model

import kotlin.math.ceil
import kotlin.math.floor

data class TileKey(val edge: Int, val column: Int, val row: Int)

object VisibleTiles {
    fun plan(width: Int, height: Int, zoom: Float, panX: Float, panY: Float): List<TileKey> {
        if (zoom <= 1.5f) return emptyList()
        val edge =
            when {
                zoom <= 2.5f -> 3200
                zoom <= 4.5f -> 6400
                else -> 9600
            }
        val scale = edge.toFloat() / maxOf(width, height)
        val w = ceil(width * scale).toInt()
        val h = ceil(height * scale).toInt()
        val left = (.5f + (-.5f - panX) / zoom).coerceIn(0f, 1f)
        val right = (.5f + (.5f - panX) / zoom).coerceIn(0f, 1f)
        val top = (.5f + (-.5f - panY) / zoom).coerceIn(0f, 1f)
        val bottom = (.5f + (.5f - panY) / zoom).coerceIn(0f, 1f)
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
