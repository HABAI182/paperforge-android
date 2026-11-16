package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The bounds of a ruled top sheet, read from the sheet itself. On loose notes lying on an open book, beside and on
 * other sheets of the same paper, the page network sees one white surface: its quad takes in the printed page above,
 * the next sheet beside and the stack strips below (photo of 2026-11-16). A ruled sheet says where it is:
 * - its rules, evenly spaced, END on a common line at its left and right edges; past that line lie another sheet's
 *   rules at other heights, or a paper edge (a brightness step);
 * - above its first rule and below its last, at most a few rule spacings away, its own edge runs across the whole
 *   sheet as a continuous step in paper brightness or a cast shadow.
 * Only that evidence moves a side, inward only. A notebook margin (rules start at a margin line, blank paper beyond,
 * no edge) is left alone; so is any page whose rules are not an even lattice across most of the page.
 */
object RuledSheetBounds {
    const val VERSION = "ruled-bounds-1"
    private const val WORK = 1000.0

    fun enabled() = ScanConfig.int("ruledBounds", 1) != 0

    /** Why the last [refine] on this thread moved nothing (or what it moved), for diagnostics. */
    val notes: ThreadLocal<String> = ThreadLocal.withInitial { "" }

    data class Result(val quad: Quad, val sides: String)

    /** [photo] RGBA or RGB, [quad] normalised; null when nothing moves. */
    fun refine(photo: Mat, quad: Quad): Result? {
        notes.set("")
        if (!enabled() || photo.empty() || !quad.valid()) return null
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val w = photo.cols()
            val h = photo.rows()
            val p = quad.points.map { Point(it.x * (w - 1.0), it.y * (h - 1.0)) }
            val wide = max(hypot(p[1].x - p[0].x, p[1].y - p[0].y), hypot(p[2].x - p[3].x, p[2].y - p[3].y))
            val tall = max(hypot(p[3].x - p[0].x, p[3].y - p[0].y), hypot(p[2].x - p[1].x, p[2].y - p[1].y))
            // The working frame is the photo itself (the quad's bounding box, scaled, turned by the rules' angle): a
            // similarity keeps the rules straight and parallel as photographed. Warping by the quad would add the
            // quad's own perspective, which is wrong exactly when the quad is wrong.
            val bx0 = max(0.0, p.minOf { it.x })
            val by0 = max(0.0, p.minOf { it.y })
            val bx1 = min(w - 1.0, p.maxOf { it.x })
            val by1 = min(h - 1.0, p.maxOf { it.y })
            val s = WORK / max(bx1 - bx0, by1 - by0)
            val rw = ((bx1 - bx0) * s).roundToInt()
            val rh = ((by1 - by0) * s).roundToInt()
            if (rw < 300 || rh < 300) return null.also { notes.set("small") }
            val src = MatOfPoint2f(*p.toTypedArray()).also(owned::add)
            val toFrame = Mat.eye(3, 3, CvType.CV_64F).also(owned::add).apply {
                put(0, 0, s); put(1, 1, s); put(0, 2, -s * bx0); put(1, 2, -s * by0)
            }
            fun greyThrough(transform: Mat): Mat {
                val colour = mat()
                Imgproc.warpPerspective(photo, colour, transform, Size(rw.toDouble(), rh.toDouble()), Imgproc.INTER_AREA, Core.BORDER_REPLICATE)
                return mat().also { Imgproc.cvtColor(colour, it, if (photo.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY) }
            }
            // Turn the frame so the rules lie level: the angle at which line energy concentrates in rows or columns.
            val angle = ruleAngle(greyThrough(toFrame), owned)
            val turn = Imgproc.getRotationMatrix2D(Point(rw / 2.0, rh / 2.0), angle, 1.0).also(owned::add)
            val turn3 = Mat.eye(3, 3, CvType.CV_64F).also(owned::add)
            for (r in 0..1) for (c in 0..2) turn3.put(r, c, turn.get(r, c)[0])
            val forward = mat().also { Core.gemm(turn3, toFrame, 1.0, mat(), 0.0, it) }
            val grey = greyThrough(forward)
            // The original quad in the working frame: its sides stay where nothing moves them.
            val frameCorners = MatOfPoint2f().also(owned::add)
            Core.perspectiveTransform(src, frameCorners, forward)
            val fc = frameCorners.toArray()
            // Rules across (horizontal) or down the page: one direction must dominate clearly.
            val across = Core.sumElems(ruleMap(grey, owned)).`val`[0]
            val down = Core.sumElems(ruleMap(mat().also { Core.transpose(grey, it) }, owned)).`val`[0]
            val transposed =
                when {
                    across >= 3 * down -> false
                    down >= 3 * across -> true
                    else -> return null.also { notes.set("no dominant rule direction") }
                }
            val page = if (transposed) mat().also { Core.transpose(grey, it) } else grey
            val W = page.cols()
            val H = page.rows()
            // The original sides in the working (possibly transposed) frame: left/right as x(y), top/bottom as y(x).
            val q = if (transposed) fc.map { Point(it.y, it.x) }.let { listOf(it[0], it[3], it[2], it[1]) } else fc.toList()
            fun xLine(a: Point, b: Point): Line { val k = (b.x - a.x) / (b.y - a.y).let { if (abs(it) < 1e-6) 1e-6 else it }; return Line(a.x - k * a.y, k) }
            fun yLine(a: Point, b: Point): Line { val k = (b.y - a.y) / (b.x - a.x).let { if (abs(it) < 1e-6) 1e-6 else it }; return Line(a.y - k * a.x, k) }
            val dl = xLine(q[0], q[3])
            val dr = xLine(q[1], q[2])
            val dt = yLine(q[0], q[1])
            val db = yLine(q[3], q[2])
            val rules = ruleMap(page, owned)
            val rv = FloatArray(W * H).also { mat().also { m -> rules.convertTo(m, CvType.CV_32F) }.get(0, 0, it) }
            // Only what lies inside the original quad counts.
            for (y in 0 until H) {
                val xa = max(0, dl.at(y.toDouble()).roundToInt())
                val xb = min(W - 1, dr.at(y.toDouble()).roundToInt())
                for (x in 0 until W) if (x < xa || x > xb || y < dt.at(x.toDouble()) || y > db.at(x.toDouble())) rv[y * W + x] = 0f
            }
            // Paper brightness with handwriting closed away along the rows only, so that a sheet edge's thin shadow line
            // (horizontal, long) survives: the sheet edge is a step or a dark line in it.
            val paper = mat()
            Imgproc.dilate(page, paper, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 1.0)))
            val smooth = mat()
            paper.convertTo(smooth, CvType.CV_32F)
            Imgproc.GaussianBlur(smooth, smooth, Size(0.0, 0.0), 1.2)
            val pv = FloatArray(W * H).also { smooth.get(0, 0, it) }
            val tau = threshold(rv, W, H) ?: return null.also { notes.set("no rule energy") }
            val lattice = lattice(rv, W, H, tau) ?: return null.also { notes.set("no even rule lattice") }
            val spacing = lattice.spacing
            // A rule has the same paper on both sides; a sheet edge's shadow line (the top sheet's own bottom edge, the
            // strips of the sheets below) has a brightness step across it. Such lines at the ends are not rules.
            fun step(y: Int): Double {
                val d = (spacing * .3).roundToInt().coerceAtLeast(3)
                if (y - d < 0 || y + d >= H) return 99.0
                var sum = 0.0
                var n = 0
                var x = (dl.at(y.toDouble()) + .1 * (dr.at(y.toDouble()) - dl.at(y.toDouble()))).toInt()
                val end = (dr.at(y.toDouble()) - .1 * (dr.at(y.toDouble()) - dl.at(y.toDouble()))).toInt()
                while (x < end) {
                    if (x in 0 until W) { sum += abs(pv[(y - d) * W + x] - pv[(y + d) * W + x]); n++ }
                    x += 4
                }
                return if (n > 0) sum / n else 99.0
            }
            // Ruled paper, not a table or form: at most one long line crosses the rules (a margin line). Tables and forms
            // have several, and their cells must never be cut.
            run {
                val vertical = ruleMap(mat().also { Core.transpose(page, it) }, owned)
                val vv = FloatArray(W * H).also { mat().also { m -> vertical.convertTo(m, CvType.CV_32F) }.get(0, 0, it) }
                // In the transposed map, a page column x is row x, and page rows run along it.
                val y0 = lattice.rows.first()
                val y1 = lattice.rows.last()
                var columns = 0
                var lastHit = -10
                val x0 = max(0, dl.at(H / 2.0).roundToInt() + 4)
                val x1 = min(W - 1, dr.at(H / 2.0).roundToInt() - 4)
                for (x in x0..x1) {
                    var on = 0
                    for (y in y0..y1) if (vv[x * H + y] > tau) on++
                    if (on >= .5 * (y1 - y0 + 1)) {
                        if (x - lastHit > 4) columns++
                        lastHit = x
                    }
                }
                if (columns >= 2) return null.also { notes.set("table or form: $columns vertical lines") }
            }
            val ys = lattice.rows.toMutableList()
            while (ys.size > 8 && step(ys.last()) > 8.0) ys.removeAt(ys.lastIndex)
            while (ys.size > 8 && step(ys.first()) > 8.0) ys.removeAt(0)
            // Rule ends, traced from the lattice's column outward.
            val centre = lattice.column
            val leftTraces = ys.map { trace(rv, W, H, it.toDouble(), centre, -1, tau) }
            val rightTraces = ys.map { trace(rv, W, H, it.toDouble(), centre, +1, tau) }
            val lefts = leftTraces.map { it.first }
            val rights = rightTraces.map { it.first }
            // Rules, not rows of text: most lattice lines run continuously across at least half the frame.
            val frameWidth = dr.at(H / 2.0) - dl.at(H / 2.0)
            val longLines =
                ys.indices.count { i ->
                    val span = rights[i] - lefts[i]
                    span >= .5 * frameWidth && (leftTraces[i].second + rightTraces[i].second) >= .65 * span
                }
            if (longLines < .6 * ys.size) return null.also { notes.set("rules too short: $longLines of ${ys.size}") }
            var left: Line? = null
            var right: Line? = null
            var top: Line? = null
            var bottom: Line? = null
            val moved = StringBuilder()
            side(ys, lefts, W, H, spacing, rv, pv, tau, dl, mirrored = false)?.let { left = it; moved.append("L") }
            side(ys, rights.map { W - 1 - it }, W, H, spacing, rv, pv, tau, Line(W - 1 - dr.a, -dr.b), mirrored = true)?.let { right = it; moved.append("R") }
            val l = left ?: dl
            val r = right ?: dr
            val x0 = l.at(H / 2.0) + .02 * frameWidth
            val x1 = r.at(H / 2.0) - .02 * frameWidth
            edge(pv, W, H, ys.first() - 3.6 * spacing, ys.first() - .55 * spacing, x0, x1, dt, upward = true, spacing = spacing)?.let { top = it; moved.append("T") }
            edge(pv, W, H, ys.last() + .35 * spacing, ys.last() + 3.0 * spacing, x0, x1, db, upward = false, spacing = spacing)?.let { bottom = it; moved.append("B") }
            if (moved.isEmpty()) return null.also { notes.set("no side moved; lattice ${ys.size} spacing ${"%.1f".format(spacing)} angle ${"%.1f".format(angle)};" + notes.get()) }
            val t = top ?: dt
            val b = bottom ?: db
            fun corner(vertical: Line, horizontal: Line): Point {
                var y = horizontal.at(vertical.at(H / 2.0))
                var x = vertical.at(y)
                repeat(4) { y = horizontal.at(x); x = vertical.at(y) }
                return Point(x, y)
            }
            var corners = listOf(corner(l, t), corner(r, t), corner(r, b), corner(l, b))
            if (transposed) corners = corners.map { Point(it.y, it.x) }.let { listOf(it[0], it[3], it[2], it[1]) }
            val inverse = mat().also { Core.invert(forward, it) }
            val mapped = MatOfPoint2f().also(owned::add)
            Core.perspectiveTransform(MatOfPoint2f(*corners.toTypedArray()).also(owned::add), mapped, inverse)
            val out =
                Quad.ordered(mapped.toArray().map { PointN((it.x / (w - 1.0)).toFloat().coerceIn(0f, 1f), (it.y / (h - 1.0)).toFloat().coerceIn(0f, 1f)) })
            val sides = if (!transposed) moved.toString() else moved.toString().map { mapOf('L' to 'T', 'R' to 'B', 'T' to 'L', 'B' to 'R')[it] }.joinToString("")
            notes.set("moved $sides; angle ${"%.1f".format(angle)} rows ${ys.size} sp ${"%.1f".format(spacing)};" + notes.get())
            // Inward only: every new corner inside the original quad (1 % tolerance).
            val outline = org.opencv.core.MatOfPoint2f(*p.toTypedArray()).also(owned::add)
            val slack = .01 * max(wide, tall)
            if (out.points.any { Imgproc.pointPolygonTest(outline, Point(it.x * (w - 1.0), it.y * (h - 1.0)), true) < -slack })
                return null.also { notes.set(notes.get() + " REJECTED $sides: outside the original quad ${out.points.map { "%.3f,%.3f".format(it.x, it.y) }}") }
            return Result(out, sides).takeIf { out.valid() && out.area >= .4f * quad.area && out.area < .99f * quad.area }
                ?: null.also { notes.set("rejected $sides: area ${out.area / quad.area}") }
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /**
     * The turn (degrees, for getRotationMatrix2D) that levels the page's lines: on a 400 px copy, the angle within
     * +-30 degrees at which thin dark structure concentrates most in rows (or in columns).
     */
    private fun ruleAngle(grey: Mat, owned: MutableList<Mat>): Double {
        val scale = 400.0 / max(grey.cols(), grey.rows())
        val small = Mat().also(owned::add)
        Imgproc.resize(grey, small, Size(grey.cols() * scale, grey.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val hat = Mat().also(owned::add)
        Imgproc.morphologyEx(small, hat, Imgproc.MORPH_BLACKHAT, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)).also(owned::add))
        val f = Mat().also(owned::add)
        hat.convertTo(f, CvType.CV_32F)
        val turned = Mat().also(owned::add)
        val sums = Mat().also(owned::add)
        var best = 0.0
        var bestScore = -1.0
        var a = -30.0
        while (a <= 30.0) {
            val m = Imgproc.getRotationMatrix2D(Point(f.cols() / 2.0, f.rows() / 2.0), a, 1.0)
            Imgproc.warpAffine(f, turned, m, f.size())
            m.release()
            for (dim in 0..1) {
                Core.reduce(turned, sums, dim xor 1, Core.REDUCE_SUM, CvType.CV_32F)
                val v = FloatArray(sums.rows() * sums.cols()).also { sums.get(0, 0, it) }
                var sq = 0.0
                var tot = 0.0
                for (x in v) { sq += x.toDouble() * x; tot += x }
                val score = if (tot > 0) sq / (tot * tot) * v.size else 0.0
                if (score > bestScore) { bestScore = score; best = a }
            }
            a += .5
        }
        return best
    }

    /** x = a + b * y in the working frame (or y = a + b * x for top/bottom). */
    private data class Line(val a: Double, val b: Double) {
        fun at(t: Double) = a + b * t
    }

    private class Lattice(val rows: List<Int>, val spacing: Double, val column: Int)

    private fun ruleMap(page: Mat, owned: MutableList<Mat>): Mat {
        val hat = Mat().also(owned::add)
        Imgproc.morphologyEx(page, hat, Imgproc.MORPH_BLACKHAT, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 7.0)).also(owned::add))
        val out = Mat().also(owned::add)
        Imgproc.morphologyEx(hat, out, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(19.0, 1.0)).also(owned::add))
        return out
    }

    /** Half the 97th percentile of rule energy in the middle columns (at least 4). */
    private fun threshold(v: FloatArray, w: Int, h: Int): Double? {
        val sample = ArrayList<Float>()
        for (y in 0 until h step 2) for (x in (.3 * w).toInt() until (.7 * w).toInt() step 2) sample += v[y * w + x]
        if (sample.isEmpty()) return null
        sample.sort()
        return max(4.0, sample[(sample.size * .97).toInt().coerceAtMost(sample.size - 1)] * .5)
    }

    /**
     * The longest run of rule rows (sampled at three columns, merged) whose gaps stay within 22 % of their median:
     * the sheet's own ruling. A printed line on the page beneath, several spacings away, falls outside the run.
     */
    private fun lattice(v: FloatArray, w: Int, h: Int, tau: Double): Lattice? =
        listOf(.5, .35, .65, .25, .75).mapNotNull { latticeAt(v, w, h, tau, (it * w).toInt()) }.maxByOrNull { it.rows.size }

    private fun latticeAt(v: FloatArray, w: Int, h: Int, tau: Double, cx: Int): Lattice? {
        val band = FloatArray(h) { y -> var m = 0f; for (x in max(0, cx - 6) until min(w, cx + 6)) m = max(m, v[y * w + x]); m }
        val rows = ArrayList<Int>()
        for (y in 2 until h - 2) {
            if (band[y] > tau && band[y] >= band[y - 1] && band[y] >= band[y + 1] && (rows.isEmpty() || y - rows.last() > 3)) rows += y
        }
        if (rows.size < 8) return null
        val gaps = rows.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        // The spacing is the commonest gap: the median of the middle half of the sorted gaps.
        val core = gaps.subList(gaps.size / 4, max(gaps.size / 4 + 1, 3 * gaps.size / 4))
        val spacing = core[core.size / 2]
        // Notebook ruling is 6-10 mm on a page of 150-300 mm: at least 2.2 % of the frame. Closer, evenly spaced rows are
        // print (datasheets, forms), never ruling.
        if (spacing < .022 * max(w, h)) return null
        var best = emptyList<Int>()
        var run = mutableListOf(rows[0])
        for (i in 1 until rows.size) {
            val gap = rows[i] - run.last()
            // A doubled header rule (two lines a few pixels apart) belongs to the run.
            if (gap < .35 * spacing) continue
            val k = (gap / spacing).roundToInt()
            if (k in 1..2 && abs(gap - k * spacing) <= .22 * spacing) run.add(rows[i])
            else {
                if (run.size > best.size) best = run
                run = mutableListOf(rows[i])
            }
        }
        if (run.size > best.size) best = run
        if (best.size < 8 || (best.last() - best.first()) < .35 * h) return null
        return Lattice(best, spacing, cx)
    }

    /**
     * Follows a rule from (x0, y0) along [step]; returns the last x where it was found and how many columns on the
     * way had rule energy. Gaps of ink are allowed.
     */
    private fun trace(v: FloatArray, w: Int, h: Int, y0: Double, x0: Int, step: Int, tau: Double): Pair<Int, Int> {
        var hits = 0
        var y = y0
        var x = x0
        var last = x0
        var missed = 0
        var pinned = 0
        var pinnedFrom = x0
        val gap = max(10, w / 70)
        while (x + step in 0 until w) {
            x += step
            val cy = y.roundToInt()
            var bestY = -1
            var bestV = 0f
            for (yy in max(0, cy - 2)..min(h - 1, cy + 2)) {
                val value = v[yy * w + x]
                if (value > bestV) { bestV = value; bestY = yy }
            }
            if (bestV > tau) {
                // Stepping onto another sheet's rule at a slightly different height shows as the best match pinned to
                // the edge of the search window for several columns: the rule ended where that began.
                if (abs(bestY - cy) >= 2) {
                    if (++pinned >= 3) { last = pinnedFrom; break }
                    if (pinned == 1) pinnedFrom = last
                } else pinned = 0
                y = .7 * y + .3 * bestY
                last = x
                missed = 0
                hits++
            } else if (++missed > gap) break
        }
        return last to hits
    }

    /**
     * The sheet's left edge (right edge mirrored) from rule ends: a line that at least 45 % of the rules (and 5) end
     * on, at least 2.5 % inside the frame, with another sheet's rules past it or a paper-brightness step along it.
     */
    private fun side(ys: List<Int>, ends: List<Int>, w: Int, h: Int, spacing: Double, v: FloatArray, paper: FloatArray, tau: Double, original: Line, mirrored: Boolean): Line? {
        fun no(why: String): Line? { notes.set(notes.get() + " ${if (mirrored) "right" else "left"}:$why"); return null }
        fun xAt(x: Int) = if (mirrored) w - 1 - x else x
        val n = ys.size
        val tolerance = max(6.0, .012 * w)
        var best: Pair<Line, List<Int>>? = null
        for (i in 0 until n) for (j in i + 1 until n) {
            if (ys[j] - ys[i] < 3 * spacing) continue
            val b = (ends[j] - ends[i]).toDouble() / (ys[j] - ys[i])
            if (abs(b) > .25) continue
            val a = ends[i] - b * ys[i]
            val inliers = (0 until n).filter { abs(ends[it] - (a + b * ys[it])) <= tolerance }
            if (best == null || inliers.size > best.second.size) best = Line(a, b) to inliers
        }
        val (line0, inliers) = best ?: return no("no line")
        if (inliers.size < max(5, (.45 * n).toInt())) return no("ends ${inliers.size}/$n")
        // Least squares on the inliers.
        val my = inliers.map { ys[it].toDouble() }.average()
        val mx = inliers.map { ends[it].toDouble() }.average()
        var sxy = 0.0
        var syy = 0.0
        for (k in inliers) { sxy += (ys[k] - my) * (ends[k] - mx); syy += (ys[k] - my) * (ys[k] - my) }
        val b = if (syy > 0) (sxy / syy).coerceIn(-.25, .25) else line0.b
        val line = Line(mx - b * my, b)
        val inside = line.at(h / 2.0) - original.at(h / 2.0)
        if (inside < .025 * w || inside > .4 * w) return no("at ${"%.0f".format(inside)}/$w")
        // Evidence beyond the line: rule energy of another sheet in the strip between the frame and the line, at
        // heights where this sheet has no rule, or a brightness step along the line.
        var foreign = 0
        var checked = 0
        for (y in (ys.first() + 2) until ys.last() step 2) {
            if (ys.any { abs(it - y) <= 3 }) continue
            val from = (original.at(y.toDouble()) + 2).toInt().coerceAtLeast(2)
            val reach = (line.at(y.toDouble()) - 6).toInt()
            if (reach - from < 6) continue
            checked++
            var hit = 0
            for (x in from until reach) if (v[y * w + xAt(x)] > tau) hit++
            if (hit > .5 * (reach - from)) foreign++
        }
        var step = 0.0
        var count = 0
        for (y in ys.first() until ys.last() step 4) {
            val x = line.at(y.toDouble()).roundToInt()
            if (x - 8 < 0 || x + 8 >= w) continue
            val outside = paper[y * w + xAt(x - 8)]
            val insideV = paper[y * w + xAt(x + 8)]
            step += insideV - outside
            count++
        }
        val brightnessStep = if (count > 0) step / count else 0.0
        val rulesBeyond = checked > 0 && foreign >= 3
        if (!rulesBeyond && abs(brightnessStep) < 10.0) return no("no evidence beyond (rules $foreign/$checked, step ${"%.1f".format(brightnessStep)})")
        return if (mirrored) Line(w - 1 - line.a, -line.b) else line
    }

    /**
     * The sheet's top edge (bottom edge with [upward] false) between rows [from] and [to]: per column, the strongest
     * step in paper brightness in the direction that enters the sheet; the steps must form one smooth curve across at
     * least 65 % of the columns between [x0] and [x1]. The line keeps the whole curve on the sheet side.
     */
    private fun edge(paper: FloatArray, w: Int, h: Int, from: Double, to: Double, x0: Double, x1: Double, original: Line, upward: Boolean, spacing: Double): Line? {
        fun no(why: String): Line? { notes.set(notes.get() + " ${if (upward) "top" else "bottom"}:$why"); return null }
        val y0 = from.roundToInt().coerceIn(2, h - 3)
        val y1 = to.roundToInt().coerceIn(2, h - 3)
        // The window is clamped to the frame (rows 2..h-3 hold the 5-row step); an edge must still lie inside it.
        if (y1 - y0 < 6) return no("window")
        val columns = ArrayList<Pair<Int, Int>>()
        val strengths = ArrayList<Float>()
        var x = x0.toInt() + 4
        val stepX = max(4, w / 120)
        // Scanning outward from the sheet's rules, the first clear step where brightness falls leaving the sheet (into
        // its edge shadow or onto darker paper): the innermost edge, so stack strips further out never win.
        while (x < x1 - 4) {
            // Inside the original side only (3 px clear of it).
            val limit = original.at(x.toDouble())
            val ya = if (upward) max(y0, (limit + 3).toInt()) else y0
            val yb = if (upward) y1 else min(y1, (limit - 3).toInt())
            if (yb - ya < 4) { x += stepX; continue }
            val outward = if (upward) (yb downTo ya).toList() else (ya..yb).toList()
            var colMax = 0f
            val d = FloatArray(outward.size) { k ->
                val y = outward[k]
                // > 0 where moving outward goes from bright (sheet side) to dark.
                val v = if (upward) paper[(y + 2) * w + x] - paper[(y - 2) * w + x] else paper[(y - 2) * w + x] - paper[(y + 2) * w + x]
                colMax = max(colMax, v)
                v
            }
            val need = max(8f, .5f * colMax)
            var k = d.indices.firstOrNull { d[it] >= need } ?: -1
            if (k >= 0) {
                while (k + 1 < d.size && d[k + 1] >= d[k]) k++  // to the peak of that step
                columns += x to outward[k]
                strengths += d[k]
            }
            x += stepX
        }
        if (columns.size < 10) return no("columns")
        val sorted = strengths.sorted()
        val median = sorted[sorted.size / 2]
        if (median < 8f) return no("weak ${"%.1f".format(median)}")
        // Smoothness: neighbouring columns within 2.5 px per step along the chosen path.
        var smooth = 0
        for (i in 1 until columns.size) if (abs(columns[i].second - columns[i - 1].second) <= max(3, stepX / 2)) smooth++
        if (smooth < .65 * (columns.size - 1)) return no("not smooth $smooth/${columns.size - 1}")
        val strong = columns.filterIndexed { i, _ -> strengths[i] > .5f * median }
        if (strong.size < .65 * columns.size) return no("patchy")
        // A straight side that keeps the curve on the sheet: fit, then shift to the outermost 10 % of the points.
        val mx = strong.map { it.first.toDouble() }.average()
        val my = strong.map { it.second.toDouble() }.average()
        var sxy = 0.0
        var sxx = 0.0
        for ((cx, cy) in strong) { sxy += (cx - mx) * (cy - my); sxx += (cx - mx) * (cx - mx) }
        val b = if (sxx > 0) (sxy / sxx).coerceIn(-.25, .25) else 0.0
        val residuals = strong.map { it.second - (my + b * (it.first - mx)) }.sorted()
        // A quarter of the way out from the middle of the trace: columns that caught a lower strip or the wave of a
        // curled edge do not drag the side out.
        val shift = if (upward) residuals[(residuals.size * .25).toInt()] else residuals[(residuals.size * .75).toInt().coerceAtMost(residuals.size - 1)]
        val a = my - b * mx + shift
        val at = a + b * (w / 2.0)
        // Only an inward move of at least 1.5 % of the frame.
        // A different surface beyond: the paper past the edge (a book page, a shadow, the strips below) is darker
        // than the sheet's own. A step inside one sheet (a header, a lighting gradient) has the same paper both sides.
        run {
            val near = (.25 * spacing).roundToInt().coerceAtLeast(3)
            val far = (.6 * spacing).roundToInt().coerceAtLeast(near + 3)
            val diffs = ArrayList<Float>()
            var cx = x0.toInt() + 4
            while (cx < x1 - 4) {
                val ey = a + b * cx
                val sheet = if (upward) ey + (near + far) / 2.0 else ey - (near + far) / 2.0
                val beyond = if (upward) ey - (near + far) / 2.0 else ey + (near + far) / 2.0
                val limit = original.at(cx.toDouble())
                val inFrame = if (upward) beyond > limit + 1 else beyond < limit - 1
                if (inFrame && sheet.toInt() in 0 until h && beyond.toInt() in 0 until h)
                    diffs += paper[sheet.toInt() * w + cx] - paper[beyond.toInt() * w + cx]
                cx += stepX
            }
            if (diffs.size < 8) return no("too little beyond the edge")
            diffs.sort()
            val contrast = diffs[diffs.size / 2]
            if (contrast < 5f) return no("same paper beyond (%.1f)".format(contrast))
        }
        val was = original.at(w / 2.0)
        if (upward && at - was < .015 * h || !upward && was - at < .015 * h) return no("no inward move (at %.0f, was %.0f, rows %d..%d, cols %d)".format(at, was, y0, y1, strong.size))
        return Line(a, b)
    }
}
