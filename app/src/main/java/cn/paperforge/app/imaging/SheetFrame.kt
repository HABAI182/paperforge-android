package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The whole sheet when PageNet's mask stopped at a box printed on it.
 *
 * On a page with a large table or framed panel, the mask often covers only the box and the crop loses
 * the title, margins and footer (public held-out photos: 35 of 53 bad crops were a quad inside the page).
 * The network's boundary map marks the box, not the page edge, so the evidence comes from the photo: each
 * side of the quad is followed outward to the nearest long, unbroken straight edge, and the quad grows to
 * that frame only when all four sides find one and the strip added is the same paper as just inside the
 * box. A top sheet on a different sheet, desk or shadow fails that second test and keeps its crop; a white
 * sheet on an identical white sheet can pass it, which is why every side must find a full straight edge.
 *
 * Works on the same 320 x 320 RGB the network saw, so the preview and the still agree.
 */
object SheetFrame {
    const val VERSION = "sheet-frame-1"
    private const val W = 320
    private const val M = 160

    /** [rgb] is the network input: [W] x [W] x 3 RGB bytes. Null keeps [quad]. */
    fun expand(quad: Quad, rgb: ByteArray): Quad? {
        if (ScanConfig.int("sheetFrame", 1) == 0 || rgb.size != W * W * 3) return null
        val need = ScanConfig.float("sheetFrameSupport", .6f).toDouble()
        val threshold = ScanConfig.float("sheetFrameEdge", 8f).toDouble()
        val far = ScanConfig.float("sheetFrameFar", .6f).toDouble()
        val colour = ScanConfig.float("sheetFrameColour", 2.5f).toDouble()
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val image = mat()
            image.create(W, W, CvType.CV_8UC3)
            image.put(0, 0, rgb)
            val float = mat()
            image.convertTo(float, CvType.CV_32FC3, 1.0 / 255)
            val blurred = mat()
            Imgproc.GaussianBlur(float, blurred, Size(0.0, 0.0), 1.2)
            val lab = mat()
            Imgproc.cvtColor(blurred, lab, Imgproc.COLOR_RGB2Lab)
            val outer = outerFrame(quad, lab, need, threshold, far) ?: return null
            if (!outer.valid() || quad.points.any { !inside(outer, it) }) return null
            return if (sameSheet(quad, outer, float, colour)) outer else null
        } catch (e: org.opencv.core.CvException) {
            android.util.Log.w("PaperForgeDetector", "Sheet frame unavailable", e)
            return null
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** The enclosing frame alone, for tests: [rgb] as in [expand]. */
    internal fun outerFrame(quad: Quad, rgb: ByteArray): Quad? {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val image = mat()
            image.create(W, W, CvType.CV_8UC3)
            image.put(0, 0, rgb)
            val float = mat()
            image.convertTo(float, CvType.CV_32FC3, 1.0 / 255)
            val blurred = mat()
            Imgproc.GaussianBlur(float, blurred, Size(0.0, 0.0), 1.2)
            val lab = mat()
            Imgproc.cvtColor(blurred, lab, Imgproc.COLOR_RGB2Lab)
            return outerFrame(
                quad, lab,
                ScanConfig.float("sheetFrameSupport", .6f).toDouble(),
                ScanConfig.float("sheetFrameEdge", 8f).toDouble(),
                ScanConfig.float("sheetFrameFar", .6f).toDouble(),
            )
        } finally {
            owned.forEach(Mat::release)
        }
    }

    private fun outerFrame(quad: Quad, lab: Mat, need: Double, threshold: Double, far: Double): Quad? {
        val gx = FloatArray(W * W * 3)
        val gy = FloatArray(W * W * 3)
        val sobel = Mat()
        try {
            Imgproc.Sobel(lab, sobel, CvType.CV_32F, 1, 0, 3)
            sobel.get(0, 0, gx)
            Imgproc.Sobel(lab, sobel, CvType.CV_32F, 0, 1, 3)
            sobel.get(0, 0, gy)
        } finally {
            sobel.release()
        }
        return frame(quad, gx, gy, need, threshold, far)
    }

    /** Share of samples along a..b where the photo's colour changes across the line by more than [threshold]. */
    private fun support(
        gx: FloatArray, gy: FloatArray,
        ax: Double, ay: Double, bx: Double, by: Double,
        nx: Double, ny: Double, threshold: Double,
    ): Double? {
        val k = 64
        var inside = 0
        var on = 0
        for (s in 0 until k) {
            val t = .08 + .84 * s / (k - 1)
            val x = ax + (bx - ax) * t
            val y = ay + (by - ay) * t
            if (x < 1 || y < 1 || x > W - 2 || y > W - 2) continue
            inside++
            val i = (Math.round(y).toInt() * W + Math.round(x).toInt()) * 3
            var best = 0.0
            for (c in 0..2) best = max(best, abs(gx[i + c] * nx + gy[i + c] * ny))
            if (best > threshold) on++
        }
        if (inside < k / 2) return null
        return on.toDouble() / inside
    }

    /** For each side, the nearest strong straight photo edge outside it; null when a side has none. */
    private fun frame(quad: Quad, gx: FloatArray, gy: FloatArray, need: Double, threshold: Double, far: Double): Quad? {
        val pts = quad.points.map { doubleArrayOf(it.x * W.toDouble(), it.y * W.toDouble()) }
        val cx = pts.sumOf { it[0] } / 4
        val cy = pts.sumOf { it[1] } / 4
        val reach = far * W
        val lines = ArrayList<DoubleArray>(4)
        for (i in 0..3) {
            val a = pts[i]
            val e = pts[(i + 1) % 4]
            val length = hypot(e[0] - a[0], e[1] - a[1]).coerceAtLeast(1e-6)
            val dx = (e[0] - a[0]) / length
            val dy = (e[1] - a[1]) / length
            var nx = -dy
            var ny = dx
            val mx = (a[0] + e[0]) / 2
            val my = (a[1] + e[1]) / 2
            if ((cx - mx) * nx + (cy - my) * ny > 0) { nx = -nx; ny = -ny }
            // The strongest line at this offset over small turns: support, then its end points.
            fun bestAt(off: Double): DoubleArray? {
                var best: DoubleArray? = null
                for (deg in -6..6 step 2) {
                    val r = Math.toRadians(deg.toDouble())
                    val ux = dx * cos(r) - dy * sin(r)
                    val uy = dx * sin(r) + dy * cos(r)
                    val half = length / 2 + off * 1.1
                    val px = mx + nx * off
                    val py = my + ny * off
                    val ax = px - ux * half
                    val ay = py - uy * half
                    val bx = px + ux * half
                    val by = py + uy * half
                    val s = support(gx, gy, ax, ay, bx, by, -uy, ux, threshold) ?: continue
                    if (best == null || s > best[0]) best = doubleArrayOf(s, ax, ay, bx, by)
                }
                return best
            }
            var off = 2.0
            // Leave the side's own edge first.
            while (off < reach) {
                val here = bestAt(off)
                if (here == null || here[0] < need * .6) break
                off += 1.0
            }
            var found: DoubleArray? = null
            while (off < reach) {
                val here = bestAt(off) ?: break
                if (here[0] >= need) {
                    var peak = here
                    var next = off + 1
                    while (next < reach) {
                        val candidate = bestAt(next)
                        if (candidate == null || candidate[0] < peak[0]) break
                        peak = candidate
                        next += 1.0
                    }
                    found = peak
                    break
                }
                off += 1.0
            }
            lines += found ?: return null
        }
        val corners =
            (0..3).map { i ->
                val p = lines[(i + 3) % 4]
                val q = lines[i]
                val d1x = p[3] - p[1]; val d1y = p[4] - p[2]
                val d2x = q[3] - q[1]; val d2y = q[4] - q[2]
                val det = d1x * d2y - d1y * d2x
                if (abs(det) < 1e-9) return null
                val t = ((q[1] - p[1]) * d2y - (q[2] - p[2]) * d2x) / det
                PointN(
                    ((p[1] + d1x * t) / W).toFloat().coerceIn(0f, 1f),
                    ((p[2] + d1y * t) / W).toFloat().coerceIn(0f, 1f),
                )
            }
        return Quad.ordered(corners)
    }

    private fun inside(quad: Quad, point: PointN): Boolean {
        val p = quad.points
        var sign = 0
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            val s = if (cross > 1e-6f) 1 else if (cross < -1e-6f) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /** The strip between [inner] and [outer] has the colour of the paper just inside [inner]. */
    private fun sameSheet(inner: Quad, outer: Quad, float: Mat, limit: Double): Boolean {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val small = mat()
            Imgproc.resize(float, small, Size(M.toDouble(), M.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val lab = mat()
            Imgproc.cvtColor(small, lab, Imgproc.COLOR_RGB2Lab)
            fun fill(quad: Quad): Mat {
                val m = mat()
                m.create(M, M, CvType.CV_8UC1)
                m.setTo(Scalar(0.0))
                val poly = MatOfPoint(*quad.points.map { Point((it.x * M - .5).roundToInt().toDouble(), (it.y * M - .5).roundToInt().toDouble()) }.toTypedArray())
                Imgproc.fillPoly(m, listOf(poly), Scalar(1.0))
                poly.release()
                return m
            }
            val i = fill(inner)
            val o = fill(outer)
            val eroded = mat()
            Imgproc.erode(i, eroded, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)).also(owned::add))
            val ring = mat()
            Core.subtract(i, eroded, ring)
            val band = mat()
            Core.subtract(o, i, band)
            if (Core.countNonZero(band) < 20 || Core.countNonZero(ring) == 0) return false
            val a = Core.mean(lab, band)
            val b = Core.mean(lab, ring)
            val distance = sqrt((0..2).sumOf { (a.`val`[it] - b.`val`[it]).let { d -> d * d } })
            return distance < limit
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
