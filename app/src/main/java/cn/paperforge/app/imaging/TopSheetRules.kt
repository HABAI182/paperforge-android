package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The top sheet of a pile of ruled pages. PageNet sees the pile as one sheet when the pages are the same paper, so
 * its quad takes in the strips of the pages below. On a ruled page the top sheet's own edge is where its rules
 * END: the page below shows rule fragments past it at other heights, while a printed margin line has the same
 * rules running straight across it, and blank paper beyond a margin shows no rules at all.
 *
 * The corner-ownership idea (an edge of the top sheet ends at its corner, a lower sheet's edge runs on) follows the
 * HED document-scanning tutorial (github.com/fengjian0106/hed-tutorial-for-document-scanning); no code was used.
 * Only the two sides across the rules move, inward only, and only with this evidence on both sides of the line.
 */
object TopSheetRules {
    const val VERSION = "rule-ends-1"
    private const val WORK = 750.0

    fun enabled() = ScanConfig.int("ruleEnds", 1) != 0

    /** Milliseconds per section of the last [refine] on this thread, for benchmarks. */
    val times: ThreadLocal<MutableMap<String, Double>> = ThreadLocal.withInitial { linkedMapOf() }

    /** [photo] RGBA or RGB; null when no top-sheet edge with rule evidence lies inside [quad]. */
    fun refine(photo: Mat, quad: Quad): Quad? {
        if (!enabled() || photo.empty() || !quad.valid()) return null
        val clock = times.get().apply { clear() }
        var mark = System.nanoTime()
        fun lap(name: String) {
            val now = System.nanoTime()
            clock[name] = (now - mark) / 1e6
            mark = now
        }
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val w = photo.cols()
            val h = photo.rows()
            val p = quad.points.map { Point(it.x * (w - 1.0), it.y * (h - 1.0)) }
            val wide = max(hypot(p[1].x - p[0].x, p[1].y - p[0].y), hypot(p[2].x - p[3].x, p[2].y - p[3].y))
            val tall = max(hypot(p[3].x - p[0].x, p[3].y - p[0].y), hypot(p[2].x - p[1].x, p[2].y - p[1].y))
            val s = WORK / max(wide, tall)
            val rw = (wide * s).toInt()
            val rh = (tall * s).toInt()
            if (rw < 200 || rh < 200) return null
            // One bilinear warp of the colour photo straight to the page frame, then grey at page size only: the
            // whole-photo conversions cost more than everything else here.
            val sx = 1.0
            val sy = 1.0
            val src = MatOfPoint2f(*p.toTypedArray()).also(owned::add)
            val dst =
                MatOfPoint2f(Point(0.0, 0.0), Point(rw - 1.0, 0.0), Point(rw - 1.0, rh - 1.0), Point(0.0, rh - 1.0))
                    .also(owned::add)
            val forward = Imgproc.getPerspectiveTransform(src, dst).also(owned::add)
            fun warped(width: Int, height: Int, transform: Mat): Mat {
                val colour = mat()
                Imgproc.warpPerspective(photo, colour, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR)
                return mat().also {
                    Imgproc.cvtColor(colour, it, if (photo.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
                }
            }
            // Which way the rules run, on a quarter-area page warped first: ruled paper shows one direction at least
            // four times as strongly. Hatched covers, drawings and printed text do not, and skip the full warp.
            val halfSize = Mat.eye(3, 3, CvType.CV_64F).also(owned::add).apply { put(0, 0, .5); put(1, 1, .5) }
            val preview = warped(rw / 2, rh / 2, mat().also { Core.gemm(halfSize, forward, 1.0, mat(), 0.0, it) })
            lap("warp")
            val across = Core.sumElems(ruleMap(preview, owned, 4.0, 10.0)).`val`[0]
            val down = Core.sumElems(ruleMap(mat().also { Core.transpose(preview, it) }, owned, 4.0, 10.0)).`val`[0]
            val dominance = ScanConfig.float("ruleEndsDominance", 4f)
            val transposed =
                when {
                    across >= dominance * down -> false
                    down >= dominance * across -> true
                    else -> return null
                }
            val page = warped(rw, rh, forward)
            val rules =
                if (transposed) ruleMap(mat().also { Core.transpose(page, it) }, owned) else ruleMap(page, owned)
            val W = rules.cols()
            val H = rules.rows()
            val values = ByteArray(W * H).also { rules.get(0, 0, it) }
            lap("rules")
            val tau = threshold(values, W, H) ?: return null
            lap("gate")
            // Left side as is, right side mirrored, so each search has its side at x = 0 and inward +x. A coarse pass
            // with looser limits comes first; ordinary pages fail it, so the full search runs only on likely piles.
            val needEnd = ScanConfig.float("ruleEndsShare", .7f).toDouble()
            val needOut = ScanConfig.int("ruleEndsOutside", 5)
            fun side(mirrored: Boolean): Pair<Int, Int>? {
                val sums = sums(values, W, H, mirrored)
                search(sums, W, H, tau, 6, 9, needEnd - .1, needOut - 1) ?: return null
                return search(sums, W, H, tau, 2, 3, needEnd, needOut)
            }
            val left = side(mirrored = false)
            val right = side(mirrored = true)
            lap("search")
            if (left == null && right == null) return null
            // Sides in the working (possibly transposed) frame, as point pairs.
            var sideLeft = doubleArrayOf(0.0, 0.0, 0.0, H - 1.0)
            var sideRight = doubleArrayOf(W - 1.0, 0.0, W - 1.0, H - 1.0)
            left?.let { sideLeft = doubleArrayOf(it.first.toDouble(), 0.0, it.second.toDouble(), H - 1.0) }
            right?.let { sideRight = doubleArrayOf(W - 1.0 - it.first, 0.0, W - 1.0 - it.second, H - 1.0) }
            fun back(l: DoubleArray) = if (transposed) doubleArrayOf(l[1], l[0], l[3], l[2]) else l
            val a = back(sideLeft)
            val b = back(sideRight)
            // In the page frame: with rules horizontal the moved sides are left/right, otherwise top/bottom.
            val top: DoubleArray
            val bottom: DoubleArray
            val lft: DoubleArray
            val rgt: DoubleArray
            if (!transposed) {
                lft = a; rgt = b
                top = doubleArrayOf(0.0, 0.0, rw - 1.0, 0.0)
                bottom = doubleArrayOf(0.0, rh - 1.0, rw - 1.0, rh - 1.0)
            } else {
                top = a; bottom = b
                lft = doubleArrayOf(0.0, 0.0, 0.0, rh - 1.0)
                rgt = doubleArrayOf(rw - 1.0, 0.0, rw - 1.0, rh - 1.0)
            }
            val corners = listOf(cross(lft, top), cross(top, rgt), cross(rgt, bottom), cross(bottom, lft))
            if (corners.any { it == null }) return null
            val inverse = mat()
            Core.invert(forward, inverse)
            val rect = MatOfPoint2f(*corners.map { it!! }.toTypedArray()).also(owned::add)
            val mapped = MatOfPoint2f().also(owned::add)
            Core.perspectiveTransform(rect, mapped, inverse)
            val out =
                Quad.ordered(
                    mapped.toArray().map {
                        PointN((it.x / sx / (w - 1.0)).toFloat().coerceIn(0f, 1f), (it.y / sy / (h - 1.0)).toFloat().coerceIn(0f, 1f))
                    }
                )
            return out.takeIf { it.valid() && it.area >= .5f * quad.area && it.area < quad.area }
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** Thin dark horizontal structure: a black-hat with a short vertical element, then a horizontal opening. */
    private fun ruleMap(page: Mat, owned: MutableList<Mat>, height: Double = 7.0, length: Double = 19.0): Mat {
        val hat = Mat().also(owned::add)
        val tall = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, height)).also(owned::add)
        val wide = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(length, 1.0)).also(owned::add)
        Imgproc.morphologyEx(page, hat, Imgproc.MORPH_BLACKHAT, tall)
        val out = Mat().also(owned::add)
        Imgproc.morphologyEx(hat, out, Imgproc.MORPH_OPEN, wide)
        return out
    }

    /** Half the 97th percentile of the rule map in the page's middle columns, or null when the page has no rules. */
    private fun threshold(values: ByteArray, w: Int, h: Int): Double? {
        val histogram = IntArray(256)
        var total = 0
        for (y in 0 until h) for (x in (.3 * w).toInt() until (.7 * w).toInt()) {
            histogram[values[y * w + x].toInt() and 255]++
            total++
        }
        var seen = 0
        var p97 = 0
        for (v in 0 until 256) {
            seen += histogram[v]
            if (seen >= .97 * total) { p97 = v; break }
        }
        val tau = max(4.0, p97 * .5)
        // A ruled page has many rows of rule energy well above tau in the middle; without them there is nothing to read.
        var rows = 0
        for (y in 0 until h) {
            var sum = 0
            for (x in (.4 * w).toInt() until (.6 * w).toInt()) sum += values[y * w + x].toInt() and 255
            if (sum.toDouble() / (.2 * w) > tau) rows++
        }
        return if (rows >= ScanConfig.int("ruleEndsMinimumRows", 8)) tau else null
    }

    /** Prefix sums per row (mirrored for the right side), so a window mean costs two lookups. */
    private fun sums(values: ByteArray, w: Int, h: Int, mirrored: Boolean): IntArray {
        val stride = w + 1
        val sums = IntArray(h * stride)
        for (y in 0 until h) {
            var acc = 0
            val row = y * stride
            for (x in 0 until w) {
                acc += values[y * w + (if (mirrored) w - 1 - x else x)].toInt() and 255
                sums[row + x + 1] = acc
            }
        }
        return sums
    }

    /** (x at top, x at bottom) of the innermost top-sheet edge from the side, or null. */
    private fun search(
        sums: IntArray,
        w: Int,
        h: Int,
        tau: Double,
        xStep: Int,
        dStep: Int,
        needEnd: Double,
        needOut: Int,
    ): Pair<Int, Int>? {
        val stride = w + 1
        val win = 9
        val gap = 2
        val y0 = (.04 * h).toInt()
        val y1 = (.96 * h).toInt()
        val n = y1 - y0
        val band = (ScanConfig.float("ruleEndsBand", .22f) * w).toInt()
        val slant = (.15 * w).toInt()
        val rin = DoubleArray(n)
        val rout = DoubleArray(n)
        val peak = BooleanArray(n)
        var bestKey = Double.NEGATIVE_INFINITY
        var bestEnds = 0.0
        var best: Pair<Int, Int>? = null
        var x0 = (.015 * w).toInt()
        while (x0 < band) {
            var d = -slant
            while (d <= slant) {
                val xa = x0
                val xb = x0 + d
                if (minOf(xa, xb) >= win + gap && maxOf(xa, xb) <= w - win - gap - 1) {
                    for (i in 0 until n) {
                        val x = (xa + d * (i.toDouble() / (n - 1))).roundToInt()
                        val row = (y0 + i) * stride
                        rin[i] = (sums[row + x + gap + win] - sums[row + x + gap]).toDouble() / win
                        rout[i] = (sums[row + x - gap] - sums[row + x - gap - win]).toDouble() / win
                    }
                    var peaks = 0
                    var ends = 0
                    for (i in 1 until n - 1) {
                        val p = rin[i] > tau && rin[i] >= rin[i - 1] && rin[i] >= rin[i + 1]
                        peak[i] = p
                        if (!p) continue
                        peaks++
                        val o = maxOf(rout[i - 1], rout[i], rout[i + 1])
                        if (o < .3 * rin[i]) ends++
                    }
                    if (peaks >= 8 && ends.toDouble() / peaks >= needEnd) {
                        var outside = 0
                        for (i in 3 until n - 3) {
                            if (rout[i] > tau && rout[i] >= rout[i - 1] && rout[i] >= rout[i + 1] &&
                                !peak[i - 2] && !peak[i - 1] && !peak[i] && !peak[i + 1] && !peak[i + 2]
                            ) outside++
                        }
                        if (outside >= needOut) {
                            val key = xa + d / 2.0
                            val share = ends.toDouble() / peaks
                            if (best == null || key > bestKey + 6 || (abs(key - bestKey) <= 6 && share > bestEnds)) {
                                bestKey = key
                                bestEnds = share
                                best = xa to xb
                            }
                        }
                    }
                }
                d += dStep
            }
            x0 += xStep
        }
        return best
    }

    private fun cross(p: DoubleArray, q: DoubleArray): Point? {
        val d1x = p[2] - p[0]; val d1y = p[3] - p[1]
        val d2x = q[2] - q[0]; val d2y = q[3] - q[1]
        val det = d1x * d2y - d1y * d2x
        if (abs(det) < 1e-9) return null
        val t = ((q[0] - p[0]) * d2y - (q[1] - p[1]) * d2x) / det
        return Point(p[0] + d1x * t, p[1] + d1y * t)
    }
}
