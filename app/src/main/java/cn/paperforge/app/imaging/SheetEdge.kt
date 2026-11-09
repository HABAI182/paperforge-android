package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * The top sheet of a stack that has no rules, from the shadow lines its own edges cast. A detector sees a
 * stack as one shape, and its corners then sit on the sheets below: the owner's exam paper came out with
 * the edges of the lower sheets along one side and its printed lines slanting. [RuleFrame] finds the top
 * sheet from its rules; this is for printed or blank pages.
 *
 * Each side of the given quad, with a margin around it, is searched for long straight thin dark lines, the
 * shadow a sheet's edge casts on the sheet under it. The innermost one is the top sheet's edge if it runs
 * most of the side and nothing is printed between it and the page's inner part. A side with no such line
 * keeps the quad's side. A plain paper edge against a desk is a step in brightness, not a thin line, so a
 * single sheet is left as it was.
 *
 * It fails closed: it returns null when no side moves, or when the page's area would change too much.
 * Known cost: a long ruled vertical line in a page's margin, with nothing written beyond it, can be taken
 * for an edge, and the margin beyond it is cut off.
 */
internal object SheetEdge {
    const val VERSION = "sheet-edge-1"
    private const val PAD = .06
    private const val BANDS = 48
    private const val STEP = 3

    fun enabled() = ScanConfig.int("sheetEdge", 1) != 0

    class Result(val quad: Quad, val sides: String)

    /** [photo] is RGBA or RGB, at any size. */
    fun refine(photo: Mat, quad: Quad): Result? {
        if (!enabled() || photo.empty() || !quad.valid()) return null
        val rgb = Mat()
        val gray = Mat()
        val canvas = Mat()
        val valid = Mat()
        val filled = Mat()
        try {
            when (photo.channels()) {
                4 -> Imgproc.cvtColor(photo, rgb, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(photo, rgb, Imgproc.COLOR_RGB2GRAY)
                1 -> photo.copyTo(rgb)
                else -> return null
            }
            val pw = rgb.cols()
            val ph = rgb.rows()
            val given = quad.points.map { doubleArrayOf(it.x * (pw - 1.0), it.y * (ph - 1.0)) }
            val padded = pad(given)
            val wide = hypot(padded[1][0] - padded[0][0], padded[1][1] - padded[0][1])
            val tall = hypot(padded[3][0] - padded[0][0], padded[3][1] - padded[0][1])
            val edge = ScanConfig.int("sheetEdgeWork", 1200).toDouble()
            // Shrink first: a warp only samples, and thin shadow lines would alias away.
            val shrink = min(1.0, 1.25 * edge / max(pw, ph))
            if (shrink < 1.0) Imgproc.resize(rgb, gray, Size(), shrink, shrink, Imgproc.INTER_AREA) else rgb.copyTo(gray)
            val scale = edge / max(wide, tall)
            val w = (wide * scale).toInt()
            val h = (tall * scale).toInt()
            if (w < 300 || h < 300) return null
            val target = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w - 1.0, 0.0), doubleArrayOf(w - 1.0, h - 1.0), doubleArrayOf(0.0, h - 1.0))
            val matrix = perspective(padded.map { doubleArrayOf(it[0] * shrink, it[1] * shrink) }, target)
            warp(gray, canvas, matrix, w, h, Imgproc.INTER_LINEAR)
            filled.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            filled.setTo(Scalar.all(255.0))
            warp(filled, valid, matrix, w, h, Imgproc.INTER_NEAREST)
            // The photo's own border would read as a line, so pixels near it do not count.
            Imgproc.erode(valid, valid, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
            val pixels = ByteArray(w * h).also { canvas.get(0, 0, it) }
            val inside = ByteArray(w * h).also { valid.get(0, 0, it) }
            val padX = PAD / (1 + 2 * PAD) * w
            val padY = PAD / (1 + 2 * PAD) * h
            // Each side as the left side of a view: (x, y) in the view to an index in the canvas.
            val views =
                listOf<Triple<String, Pair<Int, Int>, (Int, Int) -> Int>>(
                    Triple("left", w to h) { x, y -> y * w + x },
                    Triple("right", w to h) { x, y -> y * w + (w - 1 - x) },
                    Triple("top", h to w) { x, y -> x * w + y },
                    Triple("bottom", h to w) { x, y -> (h - 1 - x) * w + y },
                )
            val lines = HashMap<String, DoubleArray>()
            for ((side, size, at) in views) {
                val (vw, vh) = size
                val across = if (side == "left" || side == "right") padX else padY
                val along = if (side == "left" || side == "right") padY else padX
                val band = (across + ScanConfig.float("sheetEdgeBand", .12f) * (vw - 2 * across)).toInt()
                val line = innermost(vw, vh, band, along, pixels, inside, at) ?: continue
                lines[side] = line
            }
            if (lines.isEmpty()) return null
            fun sideLine(side: String): Pair<DoubleArray, DoubleArray> {
                val l = lines[side]
                // Without a line the given quad's side stands, which in the canvas is the pad's inner edge.
                return when (side) {
                    "left" -> if (l != null) doubleArrayOf(l[1], 0.0) to doubleArrayOf(l[0] * h + l[1], h.toDouble()) else doubleArrayOf(padX, 0.0) to doubleArrayOf(padX, h.toDouble())
                    "right" -> if (l != null) doubleArrayOf(w - 1 - l[1], 0.0) to doubleArrayOf(w - 1 - (l[0] * h + l[1]), h.toDouble()) else doubleArrayOf(w - 1 - padX, 0.0) to doubleArrayOf(w - 1 - padX, h.toDouble())
                    "top" -> if (l != null) doubleArrayOf(0.0, l[1]) to doubleArrayOf(w.toDouble(), l[0] * w + l[1]) else doubleArrayOf(0.0, padY) to doubleArrayOf(w.toDouble(), padY)
                    else -> if (l != null) doubleArrayOf(0.0, h - 1 - l[1]) to doubleArrayOf(w.toDouble(), h - 1 - (l[0] * w + l[1])) else doubleArrayOf(0.0, h - 1 - padY) to doubleArrayOf(w.toDouble(), h - 1 - padY)
                }
            }
            val frame =
                listOf(
                    meet(sideLine("top"), sideLine("left")),
                    meet(sideLine("top"), sideLine("right")),
                    meet(sideLine("bottom"), sideLine("right")),
                    meet(sideLine("bottom"), sideLine("left")),
                )
            if (frame.any { it == null }) return null
            val inverse = invert(matrix)
            val points =
                frame.map { p ->
                    val q = apply(inverse, p!!)
                    PointN((q[0] / shrink / (pw - 1)).coerceIn(0.0, 1.0).toFloat(), (q[1] / shrink / (ph - 1)).coerceIn(0.0, 1.0).toFloat())
                }
            val refined = Quad(points)
            if (!refined.valid()) return null
            val ratio = refined.area / max(quad.area, 1e-6f)
            if (ratio < ScanConfig.float("sheetEdgeMinimumArea", .75f) || ratio > ScanConfig.float("sheetEdgeMaximumArea", 1.12f)) return null
            val moved = refined.points.zip(quad.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }
            if (moved < .01f) return null
            return Result(refined, lines.keys.sorted().joinToString(","))
        } finally {
            listOf(rgb, gray, canvas, valid, filled).forEach(Mat::release)
        }
    }

    /**
     * The innermost long thin dark line within [band] of a view's left side, as x = a*y + b, or null.
     * The line must hold in most row bands and lean at most `sheetEdgeSlope`. Just inside it there must be
     * blank paper, `sheetEdgeClear` of the width, judged against the paper inside the band: the top sheet
     * has a margin there, while a lower sheet's edge has the next sheet's edge right beside it. That is
     * checked only along the page's own length, [along] in from each end, since beyond the page lie the
     * desk and the sheets below.
     */
    private fun innermost(vw: Int, vh: Int, band: Int, along: Double, pixels: ByteArray, inside: ByteArray, at: (Int, Int) -> Int): DoubleArray? {
        if (band < 4 * STEP + 8 || band + 60 >= vw) return null
        fun value(x: Int, y: Int) = pixels[at(x, y)].toInt() and 255
        fun valid(x: Int, y: Int) = inside[at(x, y)] != 0.toByte()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val minimum = ScanConfig.float("sheetEdgeResponse", 4f)
        for (k in 0 until BANDS) {
            val y0 = k * vh / BANDS
            val y1 = (k + 1) * vh / BANDS
            val column = FloatArray(band)
            for (x in STEP until band - STEP) {
                var sum = 0f
                var count = 0
                for (y in y0 until y1) {
                    if (!valid(x - STEP, y) || !valid(x + STEP, y)) continue
                    sum += max(0f, (value(x - STEP, y) + value(x + STEP, y)) / 2f - value(x, y))
                    count++
                }
                if (count > (y1 - y0) / 2) column[x] = sum / count
            }
            val smooth = FloatArray(band) { x -> if (x in 2 until band - 2) (column[x - 2] + 2 * column[x - 1] + 3 * column[x] + 2 * column[x + 1] + column[x + 2]) / 9f else 0f }
            for (x in STEP + 1 until band - STEP - 1) {
                if (smooth[x] < minimum) continue
                var peak = true
                for (d in -4..4) if (x + d in 0 until band && smooth[x + d] > smooth[x]) peak = false
                if (peak) {
                    xs += x.toDouble()
                    ys += (y0 + y1) / 2.0
                }
            }
        }
        if (xs.size < BANDS * .4) return null
        val maxSlope = ScanConfig.float("sheetEdgeSlope", .15f).toDouble()
        val need = (ScanConfig.float("sheetEdgeSupport", .55f) * BANDS).toInt()
        val found = ArrayList<DoubleArray>()
        val used = BooleanArray(xs.size)
        val random = java.util.Random(7)
        repeat(6) {
            var best: DoubleArray? = null
            var bestSupport = 0
            var bestInliers: List<Int> = emptyList()
            val free = xs.indices.filter { !used[it] }
            if (free.size < need) return@repeat
            repeat(600) {
                val i = free[random.nextInt(free.size)]
                val j = free[random.nextInt(free.size)]
                if (abs(ys[j] - ys[i]) < vh * .2) return@repeat
                val a = (xs[j] - xs[i]) / (ys[j] - ys[i])
                if (abs(a) > maxSlope) return@repeat
                val b = xs[i] - a * ys[i]
                val inliers = free.filter { abs(xs[it] - (a * ys[it] + b)) < 2.5 }
                // One point per row band at most, so a cluster cannot pass as a long line.
                val support = inliers.map { ys[it] }.distinct().size
                if (support > bestSupport) {
                    bestSupport = support
                    best = doubleArrayOf(a, b)
                    bestInliers = inliers
                }
            }
            if (best == null || bestSupport < need) return@repeat
            // Refit on the inliers.
            val n = bestInliers.size.toDouble()
            val my = bestInliers.sumOf { ys[it] } / n
            val mx = bestInliers.sumOf { xs[it] } / n
            val sxy = bestInliers.sumOf { (ys[it] - my) * (xs[it] - mx) }
            val syy = bestInliers.sumOf { (ys[it] - my) * (ys[it] - my) }
            val a = if (syy > 1e-6) sxy / syy else 0.0
            found += doubleArrayOf(a, mx - a * my)
            bestInliers.forEach { used[it] = true }
        }
        found.sortByDescending { it[0] * vh / 2 + it[1] }
        val clear = ScanConfig.float("sheetEdgeInk", .012f)
        val strip = (ScanConfig.float("sheetEdgeClear", .025f) * vw).toInt()
        val span = vh - 2 * along
        for (line in found) {
            var dark = 0
            var total = 0
            var y = (along + .08 * span).toInt()
            while (y < vh - along - .08 * span) {
                val x0 = (line[0] * y + line[1]).toInt() + 4
                var paperSum = 0
                var paperCount = 0
                for (x in band until min(vw, band + 60)) if (valid(x, y)) { paperSum += value(x, y); paperCount++ }
                if (paperCount > 0) {
                    val paper = paperSum.toFloat() / paperCount
                    for (x in max(0, x0) until min(band, x0 + strip)) {
                        if (!valid(x, y)) continue
                        if (value(x, y) < .7f * paper) dark++
                        total++
                    }
                }
                y += 4
            }
            if (total > 0 && dark.toFloat() / total < clear) return line
        }
        return null
    }

    private fun pad(q: List<DoubleArray>): List<DoubleArray> {
        fun minus(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1])
        val top = minus(q[1], q[0])
        val right = minus(q[2], q[1])
        val bottom = minus(q[2], q[3])
        val left = minus(q[3], q[0])
        fun at(p: DoubleArray, a: DoubleArray, sa: Double, b: DoubleArray, sb: Double) =
            doubleArrayOf(p[0] + a[0] * sa + b[0] * sb, p[1] + a[1] * sa + b[1] * sb)
        return listOf(at(q[0], top, -PAD, left, -PAD), at(q[1], top, PAD, right, -PAD), at(q[2], bottom, PAD, right, PAD), at(q[3], bottom, -PAD, left, PAD))
    }

    private fun meet(p: Pair<DoubleArray, DoubleArray>, q: Pair<DoubleArray, DoubleArray>): DoubleArray? {
        val (a, b) = p
        val (c, d) = q
        val den = (a[0] - b[0]) * (c[1] - d[1]) - (a[1] - b[1]) * (c[0] - d[0])
        if (abs(den) < 1e-9) return null
        val s = a[0] * b[1] - a[1] * b[0]
        val t = c[0] * d[1] - c[1] * d[0]
        return doubleArrayOf((s * (c[0] - d[0]) - (a[0] - b[0]) * t) / den, (s * (c[1] - d[1]) - (a[1] - b[1]) * t) / den)
    }

    private fun warp(source: Mat, destination: Mat, matrix: DoubleArray, w: Int, h: Int, flags: Int) {
        val m = Mat(3, 3, CvType.CV_64FC1)
        try {
            m.put(0, 0, *matrix)
            Imgproc.warpPerspective(source, destination, m, Size(w.toDouble(), h.toDouble()), flags, Core.BORDER_CONSTANT, Scalar.all(0.0))
        } finally {
            m.release()
        }
    }

    private fun perspective(from: List<DoubleArray>, to: List<DoubleArray>): DoubleArray {
        fun points(list: List<DoubleArray>) = MatOfPoint2f(*list.map { Point(it[0], it[1]) }.toTypedArray())
        val a = points(from)
        val b = points(to)
        val m = Imgproc.getPerspectiveTransform(a, b)
        try {
            return DoubleArray(9).also { m.get(0, 0, it) }
        } finally {
            listOf(a, b, m).forEach(Mat::release)
        }
    }

    private fun invert(a: DoubleArray): DoubleArray {
        val m = Mat(3, 3, CvType.CV_64FC1)
        val inverse = Mat()
        try {
            m.put(0, 0, *a)
            Core.invert(m, inverse)
            return DoubleArray(9).also { inverse.get(0, 0, it) }
        } finally {
            m.release()
            inverse.release()
        }
    }

    private fun apply(m: DoubleArray, p: DoubleArray): DoubleArray {
        val d = m[6] * p[0] + m[7] * p[1] + m[8]
        return doubleArrayOf((m[0] * p[0] + m[1] * p[1] + m[2]) / d, (m[3] * p[0] + m[4] * p[1] + m[5]) / d)
    }
}
