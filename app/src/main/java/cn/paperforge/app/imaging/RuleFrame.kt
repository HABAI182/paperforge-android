package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Finds the frame of the top sheet of a ruled page from its own rules, in the photo, so one homography
 * makes the rules level and the sheet's sides vertical. A detector sees a stack or an open booklet as
 * one shape; the rules say which part is one sheet, because each sheet's rules stop at its own edge.
 *
 *  1. The rule fan gives the level frame: rules become horizontal, keeping a pad around the given quad.
 *  2. Each rule is traced outward in short runs that must stay on paper. Ends that agree form a line.
 *  3. A long vertical line counts only if the rules do not continue across it, so a margin line stays.
 *  4. A fold: rules printed across a spread turn sharply at one place, with a step in the light there.
 *  5. An inward edge needs proof that what lies beyond is not this sheet: the paper stops or darkens,
 *     or other lines lie at other heights. Blank paper beyond means a printed margin, so it is kept.
 *  6. Top and bottom are where the paper stops, otherwise the given quad's level.
 *
 * It fails closed: without enough rules, or if the frame would change the page's area too much, the
 * caller keeps its quad. Known cost: a sheet whose rules stop short of a dark margin loses that margin. A
 * coloured print under or over the rules (a cover's picture) no longer ends the sheet: see [Pass.printedBeyond].
 */
internal object RuleFrame {
    const val VERSION = "rule-frame-1"
    private const val RUN = 16
    private const val PAD = .08

    fun enabled() = ScanConfig.int("ruleFrame", 1) != 0

    class Result(val quad: Quad, val left: String, val right: String, val rules: Int)

    /** [photo] is RGBA or RGB. Returns null when this page has no usable rules. */
    fun refine(photo: Mat, quad: Quad): Result? {
        if (!enabled() || photo.empty() || !quad.valid()) return null
        val small = Mat()
        val rgb = Mat()
        try {
            when (photo.channels()) {
                4 -> Imgproc.cvtColor(photo, rgb, Imgproc.COLOR_RGBA2RGB)
                3 -> photo.copyTo(rgb)
                else -> return null
            }
            // A warp only samples, so shrink first or thin rules would alias away.
            val edge = ScanConfig.int("ruleFrameEdge", 1200).toDouble()
            val shrink = min(1.0, 1.25 * edge / max(rgb.cols(), rgb.rows()) * 1.0)
            if (shrink < 1.0) Imgproc.resize(rgb, small, Size(), shrink, shrink, Imgproc.INTER_AREA)
            else rgb.copyTo(small)
            var current = quad
            var last: Result? = null
            // A second pass if the first ran into the pad: the given quad was too small to see the edge.
            for (attempt in 0 until 2) {
                val pass = Pass(small, current, edge).run() ?: return last
                last = Result(pass.quad, pass.left, pass.right, pass.rules)
                current = pass.quad
                if (!pass.hitPad) break
            }
            val result = last ?: return null
            if (!result.quad.valid()) return null
            val ratio = result.quad.area / max(quad.area, 1e-6f)
            if (ratio < ScanConfig.float("ruleFrameMinimumArea", .5f) || ratio > ScanConfig.float("ruleFrameMaximumArea", 1.6f))
                return null
            return result
        } finally {
            rgb.release()
            small.release()
        }
    }

    private class Traced(val centre: Double, val strength: Float, val leftX: Int, val leftY: Int, val rightX: Int, val rightY: Int)

    private class Line(val kind: String, val a: Double, val b: Double, val why: String) {
        fun x(y: Double) = a * y + b
    }

    private class Outcome(val quad: Quad, val left: String, val right: String, val rules: Int, val hitPad: Boolean)

    private class Pass(private val photo: Mat, private val quad: Quad, private val edge: Double) {
        private val owned = mutableListOf<Mat>()

        private fun mat() = Mat().also(owned::add)

        private var w = 0
        private var h = 0
        private lateinit var response: ByteArray
        private lateinit var paper: BooleanArray
        private lateinit var printed: BooleanArray
        private lateinit var closedWide: ByteArray
        private var rows = emptyList<Traced>()
        private var verticals = emptyList<Line>()

        fun run(): Outcome? {
            try {
                return analyse()
            } finally {
                owned.forEach(Mat::release)
            }
        }

        private fun rect(width: Int, height: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(width.toDouble(), height.toDouble())).also(owned::add)

        private fun analyse(): Outcome? {
            val pw = photo.cols()
            val ph = photo.rows()
            val given = quad.points.map { doubleArrayOf(it.x.toDouble() * (pw - 1), it.y.toDouble() * (ph - 1)) }
            val padded = pad(given)
            val wide = dist(padded[0], padded[1])
            val tall = dist(padded[0], padded[3])
            val scale = edge / max(wide, tall)
            w = (wide * scale).toInt()
            h = (tall * scale).toInt()
            if (w < 200 || h < 200) return null
            val canvasMatrix = perspective(padded, corners(w.toDouble(), h.toDouble()))
            val canvas = mat()
            warp(photo, canvas, canvasMatrix, Imgproc.INTER_LINEAR)
            val gray = mat()
            Imgproc.cvtColor(canvas, gray, Imgproc.COLOR_RGB2GRAY)
            val found = RuleRectify.rules(gray, .25)
            if (found.size < ScanConfig.int("ruleFrameMinimumLines", 6)) return null
            val (p, q) = RuleRectify.fan(found) ?: return null
            val tolerance = ScanConfig.float("ruleRectifyTolerance", .008f).toDouble()
            val kept = found.filter { abs(it.slope - (p * it.centre + q)) < tolerance }
            if (kept.size < ScanConfig.int("ruleFrameMinimumLines", 6)) return null
            val first = kept.first().centre
            val final = kept.last().centre
            if (final - first < .3 * h) return null
            fun on(centre: Double, x: Double) = (p * centre + q) * (x - w / 2.0) + centre
            val levelMatrix =
                perspective(
                    listOf(
                        doubleArrayOf(0.0, on(first, 0.0)),
                        doubleArrayOf(w.toDouble(), on(first, w.toDouble())),
                        doubleArrayOf(w.toDouble(), on(final, w.toDouble())),
                        doubleArrayOf(0.0, on(final, 0.0)),
                    ),
                    listOf(
                        doubleArrayOf(0.0, first),
                        doubleArrayOf(w.toDouble(), first),
                        doubleArrayOf(w.toDouble(), final),
                        doubleArrayOf(0.0, final),
                    ),
                )
            val level = mat()
            warp(canvas, level, levelMatrix, Imgproc.INTER_LINEAR)
            val total = multiply(levelMatrix, canvasMatrix)
            val filled = mat()
            filled.create(ph, pw, CvType.CV_8UC1)
            filled.setTo(Scalar.all(255.0))
            val valid = mat()
            warp(filled, valid, total, Imgproc.INTER_NEAREST)
            prepare(level, valid)
            // A bent page's outer rules miss the fan, but once level they are plain to see.
            val levelGray = mat()
            Imgproc.cvtColor(level, levelGray, Imgproc.COLOR_RGB2GRAY)
            val centres = mutableListOf<Double>()
            for (centre in (kept.map { it.centre } + RuleRectify.rules(levelGray, .25).filter { abs(it.slope) < .06 }.map { it.centre }).sorted()) {
                if (centres.isEmpty() || centre - centres.last() > 6) centres += centre
            }
            rows = trace(centres)
            if (rows.size < ScanConfig.int("ruleFrameMinimumLines", 6)) return null
            val spans = rows.map { it.rightX - it.leftX }.sorted()
            val typical = spans[(spans.size * 3 / 4).coerceAtMost(spans.lastIndex)]
            val inQuad = apply(total, given[0])[1]..apply(total, given[3])[1]
            // Beyond the given quad only a full-length line counts: a short one may be the next sheet.
            rows =
                rows
                    .filter { it.rightX - it.leftX >= (if (it.centre in inQuad) .6 else .85) * typical }
                    .sortedBy { it.leftY }
            if (rows.size < ScanConfig.int("ruleFrameMinimumLines", 6)) return null
            verticals = verticalLines(level, valid)
            val mapped = given.map { apply(total, it) }
            fun quadLine(a: DoubleArray, b: DoubleArray): Line {
                val slope = if (abs(b[1] - a[1]) < 1) 0.0 else (b[0] - a[0]) / (b[1] - a[1])
                return Line("quad", slope, a[0] - slope * a[1], "")
            }
            val left = sideEdge(-1, quadLine(mapped[0], mapped[3]))
            val right = sideEdge(1, quadLine(mapped[1], mapped[2]))
            val middle = (rows.first().leftY + rows.last().leftY) / 2.0
            val xLeft = left.x(middle)
            val xRight = right.x(middle)
            if (xRight - xLeft < .3 * w) return null
            val bandStart = (xLeft + 20).toInt().coerceIn(0, w - 2)
            val bandEnd = (xRight - 20).toInt().coerceIn(bandStart + 1, w - 1)
            val firstRow = rows.first().leftY
            val lastRow = rows.last().leftY
            val gaps = rows.zipWithNext { a, b -> (b.leftY - a.leftY).toDouble() }.sorted()
            val spacing = gaps[gaps.size / 2]
            val quadTop = (mapped[0][1] + mapped[1][1]) / 2
            val quadBottom = (mapped[2][1] + mapped[3][1]) / 2
            val topBound = min(quadTop, firstRow - .5 * spacing)
            val bottomBound = max(quadBottom, lastRow + .5 * spacing)
            val topStep =
                endRow(bandStart, bandEnd, firstRow, -1)?.takeUnless {
                    it > quadTop + 4 && printedBeyond(bandStart, bandEnd, quadTop.toInt(), it)
                }
            val bottomStep =
                endRow(bandStart, bandEnd, lastRow, 1)?.takeUnless {
                    it < quadBottom - 4 && printedBeyond(bandStart, bandEnd, it, quadBottom.toInt())
                }
            val top = maxOf(topStep?.toDouble() ?: -1e9, topBound, 0.0)
            val bottom = minOf(bottomStep?.toDouble() ?: 1e9, bottomBound, h - 1.0)
            if (bottom - top < .3 * h) return null
            val inset = 3.0
            val frame =
                listOf(
                    doubleArrayOf(left.x(top) + inset, top + inset),
                    doubleArrayOf(right.x(top) - inset, top + inset),
                    doubleArrayOf(right.x(bottom) - inset, bottom - inset),
                    doubleArrayOf(left.x(bottom) + inset, bottom - inset),
                )
            val inverse = invert(total)
            val points =
                frame.map {
                    val point = apply(inverse, it)
                    PointN(
                        (point[0] / (pw - 1)).coerceIn(0.0, 1.0).toFloat(),
                        (point[1] / (ph - 1)).coerceIn(0.0, 1.0).toFloat(),
                    )
                }
            return Outcome(
                Quad(points),
                left.kind + if (left.why.isEmpty()) "" else ":" + left.why,
                right.kind + if (right.why.isEmpty()) "" else ":" + right.why,
                rows.size,
                xLeft < 12 || xRight > w - 12 || top < 6 || bottom > h - 7,
            )
        }

        /** Line response across the rules, paper mask and paper level, all in the level frame. */
        private fun prepare(level: Mat, valid: Mat) {
            val grey = mat()
            Imgproc.cvtColor(level, grey, Imgproc.COLOR_RGB2GRAY)
            val closed = mat()
            Imgproc.morphologyEx(grey, closed, Imgproc.MORPH_CLOSE, rect(1, 9))
            val across = mat()
            Core.subtract(closed, grey, across)
            response = ByteArray(w * h).also { across.get(0, 0, it) }
            val wide = mat()
            Imgproc.morphologyEx(grey, wide, Imgproc.MORPH_CLOSE, rect(15, 15))
            closedWide = ByteArray(w * h).also { wide.get(0, 0, it) }
            val hsv = mat()
            Imgproc.cvtColor(level, hsv, Imgproc.COLOR_RGB2HSV)
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            channels.forEach(owned::add)
            val centre = Rect(w / 4, h / 4, w / 2, h / 2)
            val paperSaturation = percentile(channels[1].submat(centre).also(owned::add), .5f)
            val paperValue = percentile(channels[2].submat(centre).also(owned::add), .8f)
            val blurred = mat()
            Imgproc.blur(channels[1], blurred, Size(9.0, 9.0))
            val saturation = ByteArray(w * h).also { blurred.get(0, 0, it) }
            val inside = ByteArray(w * h).also { valid.get(0, 0, it) }
            val value = ByteArray(w * h).also { channels[2].get(0, 0, it) }
            val printLimit = paperSaturation + ScanConfig.int("ruleFramePrintSaturation", 70)
            printed =
                BooleanArray(w * h) {
                    (saturation[it].toInt() and 255) >= printLimit && (value[it].toInt() and 255) > 60 && inside[it] != 0.toByte()
                }
            val saturationLimit = paperSaturation + ScanConfig.int("ruleFrameSaturation", 45)
            val lightLimit = ScanConfig.float("ruleFrameLight", .55f) * paperValue
            paper =
                BooleanArray(w * h) {
                    (saturation[it].toInt() and 255) < saturationLimit &&
                        (closedWide[it].toInt() and 255) > lightLimit &&
                        inside[it] != 0.toByte()
                }
        }

        private fun percentile(values: Mat, fraction: Float): Int {
            val bytes = ByteArray(values.rows() * values.cols())
            // A submat is not continuous, so read it row by row.
            val row = ByteArray(values.cols())
            for (y in 0 until values.rows()) {
                values.get(y, 0, row)
                System.arraycopy(row, 0, bytes, y * values.cols(), row.size)
            }
            val histogram = IntArray(256)
            for (value in bytes) histogram[value.toInt() and 255]++
            var sum = 0
            val target = (bytes.size * fraction).toInt()
            for (level in 0..255) {
                sum += histogram[level]
                if (sum >= target) return level
            }
            return 255
        }

        private fun runScore(from: Double, to: Double, y: Int): Float {
            if (y < 2 || y >= h - 2) return 0f
            val a = max(0, from.toInt())
            val b = min(w, to.toInt())
            if (b <= a) return 0f
            var sum = 0
            for (x in a until b) {
                val i = y * w + x
                sum +=
                    max(
                        response[i].toInt() and 255,
                        max(response[i - w].toInt() and 255, response[i + w].toInt() and 255),
                    )
            }
            return sum.toFloat() / (b - a)
        }

        /**
         * Whether the band between a stop found below (or above) the rules and the detector's edge is coloured print
         * on this same sheet: strongly coloured, with this sheet's paper showing through it. The blue skyline under
         * an exercise book's form lines stopped the paper there and the cover lost its bottom third. A desk beyond
         * the sheet shows no paper, and a white sheet underneath is not coloured, so both still end the page.
         */
        private fun printedBeyond(x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
            if (ScanConfig.int("ruleFramePrinted", 1) == 0) return false
            var count = 0
            var coloured = 0
            var onPaper = 0
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
                count++
                val i = y * w + x
                if (printed[i]) coloured++ else if (paper[i]) onPaper++
            }
            return count > 0 &&
                coloured >= ScanConfig.float("ruleFramePrintShare", .15f) * count &&
                onPaper >= ScanConfig.float("ruleFramePrintPaper", .2f) * count
        }

        private fun paperShare(x0: Int, x1: Int, y0: Int, y1: Int): Float {
            var on = 0
            var count = 0
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
                count++
                if (paper[y * w + x]) on++
            }
            return if (count == 0) 0f else on.toFloat() / count
        }

        private fun trace(centres: List<Double>): List<Traced> {
            val result = mutableListOf<Traced>()
            for (centre in centres) {
                val c = centre.roundToInt()
                if (c < 6 || c >= h - 6) continue
                val samples = mutableListOf<Float>()
                var x = (.2 * w).toInt()
                while (x < .8 * w) {
                    samples += runScore(x.toDouble(), (x + RUN).toDouble(), c)
                    x += RUN
                }
                samples.sort()
                val strength = samples[(samples.size * 6 / 10).coerceAtMost(samples.lastIndex)]
                var bestScore = -1f
                var bestX = w / 2
                var bestY = c
                x = (.4 * w).toInt()
                while (x < .6 * w) {
                    for (d in -2..2) {
                        val score = runScore(x.toDouble(), (x + RUN).toDouble(), c + d)
                        if (score > bestScore) {
                            bestScore = score
                            bestX = x
                            bestY = c + d
                        }
                    }
                    x += RUN
                }
                val (leftX, leftY) = follow(bestX, bestY, strength, -1)
                val (rightX, rightY) = follow(bestX + RUN, bestY, strength, 1)
                result += Traced(centre, strength, leftX, leftY, rightX, rightY)
            }
            return result
        }

        /** Walks a rule outward run by run; it may drift one pixel per run. Returns the last point on it. */
        private fun follow(startX: Int, startY: Int, strength: Float, direction: Int): Pair<Int, Int> {
            var x = startX
            var y = startY
            var lastX = startX
            var lastY = startY
            var gap = 0
            val level = max(5f, .35f * strength)
            while (true) {
                val a = if (direction > 0) x else x - RUN
                val b = a + RUN
                if (a < 0 || b > w || y < 5 || y >= h - 5) return lastX to lastY
                if (paperShare(a, b, y - 3, y + 4) < .5f) return lastX to lastY
                var bestD = 0
                var best = -1f
                for (d in -1..1) {
                    val score = runScore(a.toDouble(), b.toDouble(), y + d)
                    if (score > best) {
                        best = score
                        bestD = d
                    }
                }
                if (best >= level) {
                    y += bestD
                    lastX = if (direction > 0) b else a
                    lastY = y
                    gap = 0
                } else if (++gap > 2) return lastX to lastY
                x += direction * RUN
            }
        }

        /** x = a*y + b over (x, y): the line most points agree on, refitted on them. */
        private fun consensus(points: List<Pair<Int, Int>>, tolerance: Double): Triple<Double, Double, Int>? {
            var best: Pair<Double, Double>? = null
            var bestCount = 0
            var bestError = Double.MAX_VALUE
            for (i in points.indices) for (j in i + 1 until points.size) {
                val dy = (points[j].second - points[i].second).toDouble()
                if (abs(dy) < 60) continue
                val a = (points[j].first - points[i].first) / dy
                if (abs(a) > .12) continue
                val b = points[i].first - a * points[i].second
                var count = 0
                var error = 0.0
                for (point in points) {
                    val e = abs(point.first - (a * point.second + b))
                    if (e < tolerance) {
                        count++
                        error += e
                    }
                }
                if (count > bestCount || (count == bestCount && error < bestError)) {
                    best = a to b
                    bestCount = count
                    bestError = error
                }
            }
            val (a0, b0) = best ?: return null
            val kept = points.filter { abs(it.first - (a0 * it.second + b0)) < tolerance }
            val meanY = kept.sumOf { it.second.toDouble() } / kept.size
            val meanX = kept.sumOf { it.first.toDouble() } / kept.size
            val spread = kept.sumOf { (it.second - meanY).pow(2) }
            if (spread < 1e-6) return Triple(0.0, meanX, kept.size)
            val a = kept.sumOf { (it.second - meanY) * (it.first - meanX) } / spread
            return Triple(a, meanX - a * meanY, kept.size)
        }

        private fun verticalLines(level: Mat, valid: Mat): List<Line> {
            val grey = mat()
            Imgproc.cvtColor(level, grey, Imgproc.COLOR_RGB2GRAY)
            val closed = mat()
            Imgproc.morphologyEx(grey, closed, Imgproc.MORPH_CLOSE, rect(9, 1))
            val along = mat()
            Core.subtract(closed, grey, along)
            // The edge of the warped photo is not a line on the page.
            val eroded = mat()
            Imgproc.erode(valid, eroded, rect(9, 9))
            val outside = mat()
            Core.compare(eroded, Scalar.all(0.0), outside, Core.CMP_EQ)
            along.setTo(Scalar.all(0.0), outside)
            val opened = mat()
            Imgproc.morphologyEx(along, opened, Imgproc.MORPH_OPEN, rect(1, 25))
            Imgproc.threshold(opened, opened, 10.0, 255.0, Imgproc.THRESH_BINARY)
            val found = mat()
            Imgproc.HoughLinesP(opened, found, 1.0, PI / 1800, (.12 * h).toInt(), .12 * h, .05 * h)
            class Piece(val a: Double, val mid: Double, val start: Double, val end: Double, val length: Double)
            val pieces = mutableListOf<Piece>()
            val segment = IntArray(4)
            for (i in 0 until found.rows()) {
                found.get(i, 0, segment)
                val dy = (segment[3] - segment[1]).toDouble()
                val dx = (segment[2] - segment[0]).toDouble()
                if (dy == 0.0 || abs(dx / dy) > .12) continue
                val a = dx / dy
                pieces +=
                    Piece(
                        a,
                        segment[0] + a * (h / 2.0 - segment[1]),
                        min(segment[1], segment[3]).toDouble(),
                        max(segment[1], segment[3]).toDouble(),
                        abs(dy),
                    )
            }
            val groups = mutableListOf<MutableList<Piece>>()
            for (piece in pieces.sortedByDescending { it.length }) {
                val group = groups.firstOrNull { abs(it[0].a - piece.a) < .02 && abs(it[0].mid - piece.mid) < 8 }
                if (group == null) groups += mutableListOf(piece) else group += piece
            }
            return groups.mapNotNull { group ->
                val span = group.maxOf { it.end } - group.minOf { it.start }
                if (span < .35 * h) return@mapNotNull null
                val total = group.sumOf { it.length }
                val a = group.sumOf { it.a * it.length } / total
                val mid = group.sumOf { it.mid * it.length } / total
                Line("line", a, mid - a * h / 2.0, "")
            }
        }

        private fun medianOf(x0: Int, x1: Int, y0: Int, y1: Int): Int {
            val histogram = IntArray(256)
            var count = 0
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
                histogram[closedWide[y * w + x].toInt() and 255]++
                count++
            }
            var sum = 0
            for (level in 0..255) {
                sum += histogram[level]
                if (sum * 2 >= count) return level
            }
            return 255
        }

        /** Why what lies beyond this line is not this sheet, or null if it is blank paper: a margin. */
        private fun beyond(a: Double, b: Double, side: Int): String? {
            val y0 = rows.first().leftY
            val y1 = rows.last().leftY
            val mid = a * (y0 + y1) / 2.0 + b
            val x0 = (if (side < 0) mid - 60 else mid + 8).toInt().coerceAtLeast(0)
            val x1 = (if (side < 0) mid - 8 else mid + 60).toInt().coerceAtMost(w)
            if (x1 - x0 < 16) return null
            if (paperShare(x0, x1, y0, y1) < .6f) return "paper-stops"
            val inside =
                if (side < 0) medianOf((mid + 8).toInt(), (mid + 60).toInt(), y0, y1)
                else medianOf((mid - 60).toInt(), (mid - 8).toInt(), y0, y1)
            if (medianOf(x0, x1, y0, y1) < .88f * inside) return "darker"
            val ours = rows.map { it.leftY }
            var others = 0
            var y = y0
            while (y < y1) {
                if (ours.minOf { abs(y - it) } > 4 && runScore(x0.toDouble(), x1.toDouble(), y) >= 8f) {
                    others++
                    y += 6
                } else y++
            }
            return if (others >= 3) "other-rules" else null
        }

        private fun sideEdge(side: Int, quadLine: Line): Line {
            val n = rows.size
            val candidates = mutableListOf<Line>()
            val ends = rows.map { (if (side < 0) it.leftX else it.rightX) to (if (side < 0) it.leftY else it.rightY) }
            consensus(ends, 6.0)?.let { (a, b, count) ->
                if (count >= max(5.0, .45 * n) && abs(a) <= .06)
                    beyond(a, b, side)?.let { candidates += Line("rule-ends", a, b, it) }
            }
            for (line in verticals) {
                val mid = line.x(h / 2.0)
                if ((side < 0 && mid > .3 * w) || (side > 0 && mid < .7 * w)) continue
                var inner = 0
                var outer = 0
                for (row in rows) {
                    val x = line.x(row.leftY.toDouble())
                    if (x < 30 || x > w - 30) continue
                    val level = max(5f, .35f * row.strength)
                    val inside =
                        if (side < 0) runScore(x + 6, x + 26, row.leftY) else runScore(x - 26, x - 6, row.leftY)
                    if (inside < level) continue
                    inner++
                    val across =
                        (-2..2).maxOf { d ->
                            if (side < 0) runScore(x - 26, x - 6, row.leftY + d)
                            else runScore(x + 6, x + 26, row.leftY + d)
                        }
                    if (across >= level) outer++
                }
                if (inner >= max(5.0, .4 * n) && outer <= .3 * inner)
                    beyond(line.a, line.b, side)?.let { candidates += Line("line", line.a, line.b, it) }
            }
            fold(side, ends)?.let(candidates::add)
            fun at(line: Line) = line.x(h / 2.0)
            if (candidates.isEmpty()) {
                // The rules run on past the given quad: it was too small, so the page goes to where they end.
                val (a, b, count) = consensus(ends, 6.0) ?: return quadLine
                val reach = Line("rule-reach", a, b, "")
                val beyondQuad = if (side < 0) at(reach) < at(quadLine) - 4 else at(reach) > at(quadLine) + 4
                return if (count >= max(5.0, .45 * n) && abs(a) <= .06 && beyondQuad) reach else quadLine
            }
            candidates.sortBy { if (side < 0) -at(it) else at(it) }
            val pick = candidates.first()
            // A visible line this close to the innermost evidence is the edge itself.
            val chosen = candidates.firstOrNull { it.kind == "line" && abs(at(it) - at(pick)) <= 16 } ?: pick
            return if (chosen.why == "paper-stops") paperEdge(chosen, side) else chosen
        }

        /** Traced runs overshoot by up to a run. Moves the line to where the paper itself stops. */
        private fun paperEdge(line: Line, side: Int): Line {
            val y0 = rows.first().leftY
            val y1 = rows.last().leftY
            for (shift in -RUN..RUN) {
                // From just outside the line, inward: left side rightward, right side leftward.
                val offset = if (side < 0) shift.toDouble() else -shift.toDouble()
                var on = 0
                var count = 0
                var y = y0
                while (y < y1) {
                    val x = (line.x(y.toDouble()) + offset).toInt()
                    if (x in 0 until w) {
                        count++
                        if (paper[y * w + x]) on++
                    }
                    y += 3
                }
                if (count > 0 && on >= .85f * count) return Line(line.kind, line.a, line.b + offset, line.why)
            }
            return line
        }

        /** Rules printed across a spread turn sharply at the fold, and the two pages catch the light differently. */
        private fun fold(side: Int, ends: List<Pair<Int, Int>>): Line? {
            val n = rows.size
            val minimum = max(4.0, .2 * n)
            // Measured on the rows that can show a bend at all: rules far below a fold often stop short.
            val points = mutableListOf<Pair<Int, Int>>()
            val minimumTurn = ScanConfig.float("ruleFrameFoldTurn", .12f)
            for (row in rows) {
                var y = row.centre.roundToInt()
                val track = mutableListOf<Pair<Int, Int>>()
                var x = if (side < 0) (.40 * w).toInt() else (.60 * w).toInt()
                while (if (side < 0) x > 0 else x < w - RUN) {
                    var best = -1f
                    var bestD = 0
                    for (d in -4..4) {
                        val score = runScore(x.toDouble(), (x + RUN).toDouble(), y + d)
                        if (score > best) {
                            best = score
                            bestD = d
                        }
                    }
                    if (best >= 5f) {
                        y += bestD
                        track += x to y
                    }
                    x += side * RUN
                }
                var turn = 0.0
                var at: Pair<Int, Int>? = null
                for (i in 6 until track.size - 6) {
                    val before = (track[i].second - track[i - 6].second).toDouble() / (track[i].first - track[i - 6].first)
                    val after = (track[i + 6].second - track[i].second).toDouble() / (track[i + 6].first - track[i].first)
                    if (abs(before - after) > turn) {
                        turn = abs(before - after)
                        at = track[i]
                    }
                }
                if (turn >= minimumTurn && at != null) points += at
            }
            if (points.size < minimum) return null
            val (fitted, fittedB, count) = consensus(points, 20.0) ?: return null
            if (count < minimum) return null
            // The bends are often all in one part of the page, so the slope is poorly known and must not
            // swing the line far from them. It is limited, turning about where the bends are.
            val bent = points.filter { abs(it.first - (fitted * it.second + fittedB)) < 20.0 }
            val bentY = bent.sumOf { it.second.toDouble() } / max(1, bent.size)
            val a = fitted.coerceIn(-.03, .03)
            val b = fittedB + (fitted - a) * bentY
            val y0 = rows.first().leftY
            val y1 = rows.last().leftY
            val x = a * (y0 + y1) / 2.0 + b
            // A fold has a whole other page beyond it, so the rules must run on well past it.
            val sorted = ends.map { it.first }.sorted()
            val runOn =
                if (side < 0) x - sorted[sorted.size / 8] else sorted[(sorted.size * 7 / 8).coerceAtMost(sorted.lastIndex)] - x
            if (runOn < .12 * w) return null
            val near = medianOf((x - 26).toInt(), (x - 8).toInt(), y0, y1)
            val far = medianOf((x + 8).toInt(), (x + 26).toInt(), y0, y1)
            // Many rules bending together is proof enough. Fewer need the step in the light as well.
            if (count < ScanConfig.float("ruleFrameFoldSure", .35f) * n && abs(near - far) < .04f * max(near, far)) return null
            return Line("fold", a, b, "$count-rules")
        }

        /** Walks from the outermost rule until the paper stops: its coverage falls, or its level jumps. */
        private fun endRow(x0: Int, x1: Int, start: Int, direction: Int): Int? {
            val recent = ArrayDeque<Int>()
            var y = start
            while (true) {
                y += direction
                if (y <= 0 || y >= h - 1) return null
                if (paperShare(x0, x1, y, y + 1) < .5f) return y
                val value = medianOf(x0, x1, y, y + 1)
                if (recent.size >= 12) {
                    val reference = recent.sorted()[recent.size / 2].toFloat()
                    val ahead = (y + direction * 8).coerceIn(0, h - 1)
                    if (abs(value - reference) >= .12f * reference && abs(medianOf(x0, x1, ahead, ahead + 1) - reference) >= .12f * reference)
                        return y
                }
                recent.addLast(value)
                if (recent.size > 24) recent.removeFirst()
            }
        }

        private fun warp(source: Mat, destination: Mat, matrix: DoubleArray, flags: Int) {
            val m = Mat(3, 3, CvType.CV_64FC1)
            try {
                m.put(0, 0, *matrix)
                Imgproc.warpPerspective(source, destination, m, Size(w.toDouble(), h.toDouble()), flags, Core.BORDER_CONSTANT, Scalar.all(0.0))
            } finally {
                m.release()
            }
        }

        private fun pad(q: List<DoubleArray>): List<DoubleArray> {
            fun minus(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1])
            val top = minus(q[1], q[0])
            val right = minus(q[2], q[1])
            val bottom = minus(q[2], q[3])
            val left = minus(q[3], q[0])
            fun at(p: DoubleArray, a: DoubleArray, sa: Double, b: DoubleArray, sb: Double) =
                doubleArrayOf(p[0] + a[0] * sa + b[0] * sb, p[1] + a[1] * sa + b[1] * sb)
            return listOf(
                at(q[0], top, -PAD, left, -PAD),
                at(q[1], top, PAD, right, -PAD),
                at(q[2], bottom, PAD, right, PAD),
                at(q[3], bottom, -PAD, left, PAD),
            )
        }

        private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])

        private fun corners(width: Double, height: Double) =
            listOf(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(width - 1, 0.0),
                doubleArrayOf(width - 1, height - 1),
                doubleArrayOf(0.0, height - 1),
            )

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

        private fun multiply(a: DoubleArray, b: DoubleArray) =
            DoubleArray(9) { i ->
                val row = i / 3
                val column = i % 3
                (0..2).sumOf { a[row * 3 + it] * b[it * 3 + column] }
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
}
