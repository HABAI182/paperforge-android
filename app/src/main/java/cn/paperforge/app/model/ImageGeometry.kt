package cn.paperforge.app.model

import kotlin.math.*

/** Pure math; no Bitmap/OpenCV objects, so these decisions can be tested on the JVM. */
object ImageGeometry {
    data class DecodePlan(val sample: Int, val sourceLong: Float, val requestedLong: Int, val memoryLimited: Boolean)

    fun decodePlan(width: Int, height: Int, cropLong: Float, target: Int, maxPixels: Long = 18_000_000): DecodePlan {
        require(width > 0 && height > 0 && cropLong > 0 && target > 0 && maxPixels > 0)
        var sample = 1
        while (cropLong / (sample * 2) >= target) sample *= 2
        var limited = false
        while (width.toLong() * height / sample / sample > maxPixels) {
            sample *= 2
            limited = true
        }
        return DecodePlan(sample, cropLong, target, limited)
    }

    fun exif(point: PointN, orientation: Int, inverse: Boolean = false): PointN {
        val mode = if (inverse) when (orientation) { 6 -> 8; 8 -> 6; else -> orientation } else orientation
        val (x, y) = point
        return when (mode) {
            2 -> PointN(1 - x, y)
            3 -> PointN(1 - x, 1 - y)
            4 -> PointN(x, 1 - y)
            5 -> PointN(y, x)
            6 -> PointN(1 - y, x)
            7 -> PointN(1 - y, 1 - x)
            8 -> PointN(y, 1 - x)
            else -> point
        }
    }

    fun pixelSides(quad: Quad, width: Int, height: Int): Pair<Float, Float> {
        fun distance(a: PointN, b: PointN) = hypot((a.x - b.x) * (width - 1), (a.y - b.y) * (height - 1))
        return max(distance(quad.points[0], quad.points[1]), distance(quad.points[3], quad.points[2])) to
            max(distance(quad.points[0], quad.points[3]), distance(quad.points[1], quad.points[2]))
    }

    /** Homography columns + centered pinhole camera. Reject ill-conditioned focal estimates. */
    fun aspect(quad: Quad, width: Int, height: Int): Float {
        val p = quad.points.map { doubleArrayOf(it.x.toDouble() * (width - 1), it.y.toDouble() * (height - 1)) }
        val sides = pixelSides(quad, width, height)
        val fallback = sides.first / sides.second
        val dx1 = p[1][0] - p[2][0]; val dx2 = p[3][0] - p[2][0]
        val dy1 = p[1][1] - p[2][1]; val dy2 = p[3][1] - p[2][1]
        val dx3 = p[0][0] - p[1][0] + p[2][0] - p[3][0]
        val dy3 = p[0][1] - p[1][1] + p[2][1] - p[3][1]
        val det = dx1 * dy2 - dx2 * dy1
        if (abs(det) < 1e-6) return fallback
        val g = (dx3 * dy2 - dx2 * dy3) / det
        val h = (dx1 * dy3 - dx3 * dy1) / det
        if (abs(g * h) < 1e-5) return fallback
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        val ax = p[1][0] - p[0][0] + g * (p[1][0] - cx)
        val ay = p[1][1] - p[0][1] + g * (p[1][1] - cy)
        val bx = p[3][0] - p[0][0] + h * (p[3][0] - cx)
        val by = p[3][1] - p[0][1] + h * (p[3][1] - cy)
        val focal2 = -(ax * bx + ay * by) / (g * h)
        val diagonal = hypot(width.toDouble(), height.toDouble())
        if (!focal2.isFinite() || focal2 !in (diagonal * .3).pow(2)..(diagonal * 5).pow(2)) return fallback
        val ratio = sqrt((ax * ax + ay * ay + g * g * focal2) / (bx * bx + by * by + h * h * focal2)).toFloat()
        if (!ratio.isFinite() || ratio !in .1f..10f || ratio / fallback !in .5f..2f) return fallback
        val paperRatios = listOf(1.41421356f, 1.29411765f, 1.58518519f).flatMap { listOf(it, 1 / it) }
        return paperRatios.minByOrNull { abs(it / ratio - 1) }?.takeIf { abs(it / ratio - 1) < .03f } ?: ratio
    }
}
