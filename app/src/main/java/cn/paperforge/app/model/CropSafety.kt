package cn.paperforge.app.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Safety ring around a crop quad (#21).
 *
 * A straight-edge quad that approximates a curled receipt lies inside the sheet on the bent side:
 * the detected edge can sit ~40 px (3024 px photo) inside the paper, which shaves the last glyph
 * column. Every side is therefore pushed outward by a small fraction of the shorter image side
 * before pixels are read. The expansion is clamped to the frame and rejected as a whole when it
 * would leave the frame, cut into the original quad or degenerate; callers then keep the detected
 * quad unchanged.
 *
 * Pure math on normalized [Quad] coordinates, so it is unit-testable without bitmaps.
 */
object CropSafety {
    /** Default ring width as a fraction of the shorter image side. */
    const val Margin = .01f

    fun expand(quad: Quad, width: Int, height: Int, margin: Float = Margin): Quad {
        if (width < 2 || height < 2 || !margin.isFinite() || margin <= 0f) return quad
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        val distance = margin * min(width, height).toDouble()
        val points = quad.points.map { doubleArrayOf(it.x * maxX, it.y * maxY) }
        val lines = ArrayList<DoubleArray>(4)
        for (i in 0..3) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val length = hypot(dx, dy)
            if (length < 1e-9) return quad
            // Corners are ordered consistently, so (dy, -dx) points away from the interior.
            lines.add(
                doubleArrayOf(a[0] + dy / length * distance, a[1] - dx / length * distance, dx, dy)
            )
        }
        val corners = ArrayList<PointN>(4)
        for (i in 0..3) {
            val hit = intersect(lines[(i + 3) % 4], lines[i]) ?: return quad
            corners.add(
                PointN(
                    (hit[0] / maxX).coerceIn(0.0, 1.0).toFloat(),
                    (hit[1] / maxY).coerceIn(0.0, 1.0).toFloat(),
                )
            )
        }
        val expanded = Quad(corners)
        if (!expanded.valid() || expanded.area < quad.area || !contains(expanded, quad)) return quad
        return expanded
    }

    /** True when every corner of [inner] is inside (or on the border of) [outer]. */
    fun contains(outer: Quad, inner: Quad): Boolean {
        for (i in 0..3) {
            val a = outer.points[i]
            val b = outer.points[(i + 1) % 4]
            for (p in inner.points) {
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                if (cross < -1e-5f) return false
            }
        }
        return true
    }

    private fun intersect(first: DoubleArray, second: DoubleArray): DoubleArray? {
        val denominator = first[2] * second[3] - first[3] * second[2]
        if (abs(denominator) < 1e-9) return null
        val t =
            ((second[0] - first[0]) * second[3] - (second[1] - first[1]) * second[2]) / denominator
        return doubleArrayOf(first[0] + first[2] * t, first[1] + first[3] * t)
    }
}
