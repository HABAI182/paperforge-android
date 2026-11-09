package cn.paperforge.app.model

import kotlin.math.*

object CropGeometry {
    fun hit(
        quad: Quad,
        width: Float,
        height: Float,
        x: Float,
        y: Float,
        cornerRadius: Float,
        edgeRadius: Float,
    ): Int {
        val points = quad.points.map { PointN(it.x * width, it.y * height) }
        val corner = points.indices.minBy { hypot(points[it].x - x, points[it].y - y) }
        if (hypot(points[corner].x - x, points[corner].y - y) <= cornerRadius) return corner
        fun distance(index: Int): Float {
            val a = points[index]
            val b = points[(index + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val t =
                (((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy).coerceAtLeast(.0001f))
                    .coerceIn(0f, 1f)
            return hypot(x - a.x - t * dx, y - a.y - t * dy)
        }
        val edge = points.indices.minBy(::distance)
        return if (distance(edge) <= edgeRadius) edge + 4 else -1
    }

    /** Move an entire edge as one segment, clamped as a unit at image bounds. */
    fun moveEdge(quad: Quad, edge: Int, dx: Float, dy: Float): Quad {
        val indices = listOf(edge.mod(4), (edge + 1).mod(4))
        val x =
            dx.coerceIn(
                -indices.minOf { quad.points[it].x },
                1f - indices.maxOf { quad.points[it].x },
            )
        val y =
            dy.coerceIn(
                -indices.minOf { quad.points[it].y },
                1f - indices.maxOf { quad.points[it].y },
            )
        val candidate =
            Quad(
                quad.points.mapIndexed { index, p ->
                    if (index in indices) PointN(p.x + x, p.y + y) else p
                }
            )
        return candidate.takeIf { it.valid() } ?: quad
    }

    fun expand(quad: Quad, margin: Float): Quad {
        val cx = quad.points.map { it.x }.average().toFloat()
        val cy = quad.points.map { it.y }.average().toFloat()
        return Quad(
                quad.points.map { p ->
                    PointN(
                        (cx + (p.x - cx) * (1 + margin)).coerceIn(0f, 1f),
                        (cy + (p.y - cy) * (1 + margin)).coerceIn(0f, 1f),
                    )
                }
            )
            .takeIf { it.valid() } ?: quad
    }

    fun split(quad: Quad, topFraction: Float, bottomFraction: Float): Pair<Quad, Quad> =
        split(quad, topFraction, bottomFraction, topFraction, bottomFraction)

    /**
     * Split into a left and a right page where each page can end on its own side of the gutter.
     *
     * [topFraction]/[bottomFraction] are the LEFT page's inner edge (the gutter's left side);
     * [rightTopFraction]/[rightBottomFraction] are the RIGHT page's inner edge (the gutter's right
     * side). Equal values keep the old single-line split: one seam, half of it on each page.
     * Passing two ordered boundaries instead drops the whole gutter band, which is what the auto
     * spine split uses so neither page keeps half of the shadow.
     *
     * Fractions are clamped to 0.15..0.85 exactly like the single-line version. Reversed boundaries
     * are rejected instead of producing two overlapping pages.
     */
    fun split(
        quad: Quad,
        topFraction: Float,
        bottomFraction: Float,
        rightTopFraction: Float,
        rightBottomFraction: Float,
    ): Pair<Quad, Quad> {
        // Clamp first: out-of-range input keeps the single-line behavior (clamped to .15...85), so
        // the ordering check has to look at the same values that are actually used.
        val leftTop = topFraction.coerceIn(.15f, .85f)
        val leftBottom = bottomFraction.coerceIn(.15f, .85f)
        val rightTop = rightTopFraction.coerceIn(.15f, .85f)
        val rightBottom = rightBottomFraction.coerceIn(.15f, .85f)
        require(rightTop >= leftTop && rightBottom >= leftBottom) {
            "书脊左右边界交叉：$leftTop/$leftBottom > $rightTop/$rightBottom"
        }
        val p = quad.points
        fun point(a: PointN, b: PointN, fraction: Float): PointN =
            PointN(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
        return Quad(
            listOf(p[0], point(p[0], p[1], leftTop), point(p[3], p[2], leftBottom), p[3])
        ) to Quad(listOf(point(p[0], p[1], rightTop), p[1], p[2], point(p[3], p[2], rightBottom)))
    }
}
