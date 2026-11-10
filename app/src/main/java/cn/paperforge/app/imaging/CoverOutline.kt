package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The whole cover when PageNet stopped at a printed colour boundary. A textbook cover with a dark hatched band reads
 * to the mask as two things, and the quad ends at the band: nearly square, where pages and books are about 0.7.
 * Only then, look outward along the two sides that would make it page-shaped for one coherent outline: a straight
 * edge closing it, with both neighbouring sides continuing straight out to that edge (each may sit a little outside
 * the mask's side, which can stop at an inner panel too). Edges are measured in CIELAB, strongest channel, so a
 * blue cover against a dark or tan surface shows even where brightness alone barely changes.
 *
 * Scoring a whole outline by edge evidence along its perimeter follows Dropbox's document-detection write-up; the
 * shape prior is the angle and aspect checks common to open-source scanners. No code was taken from either.
 */
object CoverOutline {
    const val VERSION = "cover-outline-1"
    private const val WORK = 1000.0

    fun enabled() = ScanConfig.int("coverOutline", 1) != 0

    /** What the last [extend] on this thread saw, for benchmarks. */
    val notes: ThreadLocal<String> = ThreadLocal.withInitial { "" }

    private class Gradients(val w: Int, val h: Int, val gx: Array<FloatArray>, val gy: Array<FloatArray>)

    /** [photo] RGBA or RGB; a larger quad or null. */
    fun extend(photo: Mat, quad: Quad): Quad? {
        if (!enabled() || photo.empty() || !quad.valid()) return null
        val gate = ScanConfig.float("coverOutlineGate", .88f)
        val probe = shape(quad.points.map { doubleArrayOf(it.x * (photo.cols() - 1.0), it.y * (photo.rows() - 1.0)) })
        notes.set("shape %.2f".format(probe))
        if (probe < gate) return null
        val g = gradients(photo)
        var seenA = 0.0
        var seenB = 0.0
        var seenClose = 0.0
        val p = quad.points.map { doubleArrayOf(it.x * (g.w - 1.0), it.y * (g.h - 1.0)) }
        val thr = ScanConfig.float("coverOutlineEdge", 25f)
        val needClose = ScanConfig.float("coverOutlineClose", .6f)
        val needSides = ScanConfig.float("coverOutlineSides", .5f)
        val target = ScanConfig.float("coverOutlineShape", .85f)
        val grow = ScanConfig.float("coverOutlineGrow", 2.2f)
        val cx = p.sumOf { it[0] } / 4
        val cy = p.sumOf { it[1] } / 4
        val s = max(g.w, g.h).toDouble()
        val offs = (-8..8).map { it * .005 * s }
        val area = polygonArea(p)
        var bestScore = -1.0
        var best: List<DoubleArray>? = null
        // (moving side, the two neighbouring sides as (far corner, near corner) pairs)
        val plans =
            listOf(
                Triple("bottom", 0 to 3, 1 to 2),
                Triple("top", 3 to 0, 2 to 1),
                Triple("right", 0 to 1, 3 to 2),
                Triple("left", 1 to 0, 2 to 3),
            )
        for ((side, aPair, bPair) in plans) {
            val a0 = p[aPair.first]; val a1 = p[aPair.second]
            val b0 = p[bPair.first]; val b1 = p[bPair.second]
            val la = hypot(a1[0] - a0[0], a1[1] - a0[1])
            val lb = hypot(b1[0] - b0[0], b1[1] - b0[1])
            if (la < 10 || lb < 10) continue
            val ua = doubleArrayOf((a1[0] - a0[0]) / la, (a1[1] - a0[1]) / la)
            val ub = doubleArrayOf((b1[0] - b0[0]) / lb, (b1[1] - b0[1]) / lb)
            fun outward(u: DoubleArray, at: DoubleArray): DoubleArray {
                val n = doubleArrayOf(-u[1], u[0])
                return if (n[0] * (at[0] - cx) + n[1] * (at[1] - cy) < 0) doubleArrayOf(-n[0], -n[1]) else n
            }
            val na = outward(ua, a1)
            val nb = outward(ub, b1)
            // The best sideways shift of a neighbouring side over an extension of length e along it.
            fun bestShift(start: DoubleArray, n: DoubleArray, u: DoubleArray, e: Double): Pair<Double, Double> {
                var top = -1.0
                var at = 0.0
                for (o in offs) {
                    val x0 = start[0] + n[0] * o
                    val y0 = start[1] + n[1] * o
                    val v = support(g, x0, y0, x0 + u[0] * e, y0 + u[1] * e, thr, 0.0, 1.0)
                    if (v > top) { top = v; at = o }
                }
                return top to at
            }
            val shiftsA = HashMap<Int, Pair<Double, Double>>()
            val shiftsB = HashMap<Int, Pair<Double, Double>>()
            var dStep = 3
            while (dStep < 35) {
                val d = dStep * .02
                val sa = shiftsA.getOrPut(dStep) { bestShift(a1, na, ua, la * d) }
                if (side == "bottom") seenA = max(seenA, sa.first)
                if (sa.first >= needSides) {
                    for (k in -6..6) {
                        val db = d + k * .02
                        if (db <= .03) continue
                        val key = dStep + k
                        val sb = shiftsB.getOrPut(key) { bestShift(b1, nb, ub, lb * db) }
                        if (side == "bottom") seenB = max(seenB, sb.first)
                        if (sb.first < needSides) continue
                        val pa = doubleArrayOf(a1[0] + na[0] * sa.second + ua[0] * la * d, a1[1] + na[1] * sa.second + ua[1] * la * d)
                        val pb = doubleArrayOf(b1[0] + nb[0] * sb.second + ub[0] * lb * db, b1[1] + nb[1] * sb.second + ub[1] * lb * db)
                        val close = support(g, pa[0], pa[1], pb[0], pb[1], thr, .06, .94)
                        if (side == "bottom") seenClose = max(seenClose, close)
                        if (close < needClose) continue
                        val qa = doubleArrayOf(a0[0] + na[0] * sa.second, a0[1] + na[1] * sa.second)
                        val qb = doubleArrayOf(b0[0] + nb[0] * sb.second, b0[1] + nb[1] * sb.second)
                        val outline =
                            when (side) {
                                "bottom" -> listOf(qa, qb, pb, pa)
                                "top" -> listOf(pa, pb, qb, qa)
                                "right" -> listOf(qa, pa, pb, qb)
                                else -> listOf(pa, qa, qb, pb)
                            }
                        if (shape(outline) > target || polygonArea(outline) > grow * area) continue
                        val score = close + (sa.first + sb.first) / 2
                        if (score > bestScore) {
                            bestScore = score
                            best = outline
                        }
                    }
                }
                dStep += 1
            }
        }
        notes.set("shape %.2f bottom sides %.2f/%.2f close %.2f best %.2f work %dx%d".format(probe, seenA, seenB, seenClose, bestScore, g.w, g.h))
        val outline = best ?: return null
        val out =
            Quad.ordered(
                outline.map {
                    PointN((it[0] / (g.w - 1.0)).toFloat().coerceIn(0f, 1f), (it[1] / (g.h - 1.0)).toFloat().coerceIn(0f, 1f))
                }
            )
        return out.takeIf { it.valid() && it.area > quad.area }
    }

    /** Short over long, from the mean of opposite sides. */
    private fun shape(p: List<DoubleArray>): Double {
        fun len(i: Int, j: Int) = hypot(p[j][0] - p[i][0], p[j][1] - p[i][1])
        val across = (len(0, 1) + len(3, 2)) / 2
        val down = (len(0, 3) + len(1, 2)) / 2
        return min(across, down) / max(max(across, down), 1e-6)
    }

    private fun polygonArea(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a[0] * b[1] - b[0] * a[1]
        }
        return abs(s) / 2
    }

    /** Share of samples along a..b where some Lab channel changes strongly across the segment. */
    private fun support(g: Gradients, ax: Double, ay: Double, bx: Double, by: Double, thr: Float, lo: Double, hi: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val length = hypot(dx, dy)
        if (length < 5) return 0.0
        val nx = -dy / length
        val ny = dx / length
        val n = 60
        var inside = 0
        var strong = 0
        for (k in 0 until n) {
            val t = lo + (hi - lo) * k / (n - 1)
            val x = (ax + dx * t).toInt()
            val y = (ay + dy * t).toInt()
            if (x < 1 || y < 1 || x >= g.w - 1 || y >= g.h - 1) continue
            inside++
            val i = y * g.w + x
            var top = 0f
            for (c in 0..2) {
                val v = abs(g.gx[c][i] * nx.toFloat() + g.gy[c][i] * ny.toFloat())
                if (v > top) top = v
            }
            if (top > thr) strong++
        }
        if (inside < .7 * n) return 0.0
        return strong.toDouble() / inside * (inside.toDouble() / n)
    }

    private fun gradients(photo: Mat): Gradients {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val f = WORK / max(photo.cols(), photo.rows())
            val small = mat()
            Imgproc.resize(photo, small, Size(), f, f, Imgproc.INTER_AREA)
            val rgb = mat()
            Imgproc.cvtColor(small, rgb, if (small.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB)
            Imgproc.GaussianBlur(rgb, rgb, Size(0.0, 0.0), 1.2)
            val lab = mat()
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val channels = ArrayList<Mat>().also { Core.split(lab, it) }
            owned += channels
            val w = lab.cols()
            val h = lab.rows()
            val gx = Array(3) { FloatArray(w * h) }
            val gy = Array(3) { FloatArray(w * h) }
            for (c in 0..2) {
                val fl = mat()
                channels[c].convertTo(fl, CvType.CV_32F)
                val sx = mat()
                val sy = mat()
                Imgproc.Sobel(fl, sx, CvType.CV_32F, 1, 0, 3)
                Imgproc.Sobel(fl, sy, CvType.CV_32F, 0, 1, 3)
                sx.get(0, 0, gx[c])
                sy.get(0, 0, gy[c])
            }
            return Gradients(w, h, gx, gy)
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
