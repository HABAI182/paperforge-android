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

    fun split(quad: Quad, topFraction: Float, bottomFraction: Float): Pair<Quad, Quad> {
        val p = quad.points
        fun point(a: PointN, b: PointN, fraction: Float): PointN {
            val t = fraction.coerceIn(.15f, .85f)
            return PointN(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        val top = point(p[0], p[1], topFraction)
        val bottom = point(p[3], p[2], bottomFraction)
        return Quad(listOf(p[0], top, bottom, p[3])) to Quad(listOf(top, p[1], p[2], bottom))
    }
}
