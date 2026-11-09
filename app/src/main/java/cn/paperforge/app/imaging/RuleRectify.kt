package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Straightens a cropped page from its own ruled lines. The rules of one sheet are parallel, so when a
 * crop's corners are off they fan out and their slope changes steadily with height. Fitting that fan
 * gives a projective map that makes them parallel while the page's left and right frame stay put.
 *
 * It fails closed: too few rules, a poor fit or a large move means no correction. A page without
 * rules, such as a printed book, is left to the other geometry stages.
 */
internal object RuleRectify {
    const val VERSION = "rule-rectify-1"
    private const val EDGE = 1000.0

    internal class Rule(val slope: Double, val centre: Double, val span: Double)

    /** [forward] maps the analysis frame to the corrected page, [inverse] the other way. */
    class Estimate(
        private val width: Int,
        private val height: Int,
        private val forward: DoubleArray,
        private val inverse: DoubleArray,
        val lines: Int,
        val fitted: Int,
        val moved: Double,
    ) {
        /** Where a point of the corrected page lies in the uncorrected one, in normalised coordinates. */
        fun input(point: PointN): PointN {
            val x = point.x.toDouble() * width
            val y = point.y.toDouble() * height
            val w = inverse[6] * x + inverse[7] * y + inverse[8]
            return PointN(
                ((inverse[0] * x + inverse[1] * y + inverse[2]) / w / width).toFloat(),
                ((inverse[3] * x + inverse[4] * y + inverse[5]) / w / height).toFloat(),
            )
        }

        /** The correction for an image of any size, with paper white outside the source. */
        fun warp(source: Mat, destination: Mat) {
            val sx = source.cols().toDouble() / width
            val sy = source.rows().toDouble() / height
            val h = forward
            val matrix = Mat(3, 3, CvType.CV_64FC1)
            try {
                matrix.put(
                    0,
                    0,
                    h[0], h[1] * sx / sy, h[2] * sx,
                    h[3] * sy / sx, h[4], h[5] * sy,
                    h[6] / sx, h[7] / sy, h[8],
                )
                Imgproc.warpPerspective(
                    source,
                    destination,
                    matrix,
                    source.size(),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT,
                    Scalar.all(255.0),
                )
            } finally {
                matrix.release()
            }
        }
    }

    fun estimate(source: Mat): Estimate? {
        if (ScanConfig.int("ruleRectify", 1) == 0 || source.empty()) return null
        val gray = Mat()
        val small = Mat()
        try {
            when (source.channels()) {
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
                else -> source.copyTo(gray)
            }
            val scale = EDGE / max(gray.cols(), gray.rows())
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            val width = small.cols()
            val height = small.rows()
            val rules = rules(small)
            val minimum = ScanConfig.int("ruleRectifyMinimumLines", 6)
            if (rules.size < minimum) return null
            val (p, q) = fan(rules) ?: return null
            val tolerance = ScanConfig.float("ruleRectifyTolerance", .008f).toDouble()
            val used = rules.filter { abs(it.slope - (p * it.centre + q)) < tolerance }
            if (used.size < minimum || used.size < .6 * rules.size) return null
            val top = used.minOf { it.centre }
            val bottom = used.maxOf { it.centre }
            if (bottom - top < .3 * height) return null
            fun on(centre: Double, x: Double) = (p * centre + q) * (x - width / 2.0) + centre
            val w = width.toDouble()
            val from =
                MatOfPoint2f(
                    Point(0.0, on(top, 0.0)),
                    Point(w, on(top, w)),
                    Point(w, on(bottom, w)),
                    Point(0.0, on(bottom, 0.0)),
                )
            val to =
                MatOfPoint2f(
                    Point(0.0, top),
                    Point(w, top),
                    Point(w, bottom),
                    Point(0.0, bottom),
                )
            val matrix = Imgproc.getPerspectiveTransform(from, to)
            val inverse = Mat()
            try {
                Core.invert(matrix, inverse)
                val forward = DoubleArray(9).also { matrix.get(0, 0, it) }
                val backward = DoubleArray(9).also { inverse.get(0, 0, it) }
                var moved = 0.0
                for ((x, y) in listOf(0.0 to 0.0, w to 0.0, w to height.toDouble(), 0.0 to height.toDouble())) {
                    val d = forward[6] * x + forward[7] * y + forward[8]
                    moved = max(moved, abs((forward[3] * x + forward[4] * y + forward[5]) / d - y))
                    moved = max(moved, abs((forward[0] * x + forward[1] * y + forward[2]) / d - x))
                }
                // Too large a move means the fit is not describing this page. Too small is not worth a resample.
                if (moved > ScanConfig.float("ruleRectifyMaximumMove", .16f) * height) return null
                if (moved < .002 * height) return null
                return Estimate(width, height, forward, backward, rules.size, used.size, moved)
            } finally {
                listOf(from, to, matrix, inverse).forEach(Mat::release)
            }
        } finally {
            gray.release()
            small.release()
        }
    }

    /** Long near-horizontal lines, one entry per physical rule, from top to bottom. */
    internal fun rules(gray: Mat, minimumSpan: Double = .3): List<Rule> {
        val width = gray.cols()
        val height = gray.rows()
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        fun rect(w: Int, h: Int) =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(w.toDouble(), h.toDouble()))
                .also(owned::add)
        try {
            // Closing across removes a thin line, so the difference from the page is the line itself.
            val closed = mat()
            Imgproc.morphologyEx(gray, closed, Imgproc.MORPH_CLOSE, rect(1, 9))
            val response = mat()
            Core.subtract(closed, gray, response)
            // Opening along keeps what is long, which drops writing.
            Imgproc.morphologyEx(response, response, Imgproc.MORPH_OPEN, rect(25, 1))
            Imgproc.threshold(response, response, 12.0, 255.0, Imgproc.THRESH_BINARY)
            // Each connected run is cut into sections, and each section is fitted by least squares. Whole
            // runs would not do: a rule crossing a booklet's fold has two slopes and would average them.
            // Collecting pixels is far cheaper than searching every angle, and the fit is as fine.
            val labels = mat()
            val stats = mat()
            val centroids = mat()
            val count = Imgproc.connectedComponentsWithStats(response, labels, stats, centroids, 8, CvType.CV_32S)
            val labelValues = IntArray(width * height).also { labels.get(0, 0, it) }
            val sections = 8
            val sectionWidth = max(1, width / sections)
            val slots = count * (sections + 1)
            val n = DoubleArray(slots)
            val sumX = DoubleArray(slots)
            val sumY = DoubleArray(slots)
            val sumXX = DoubleArray(slots)
            val sumXY = DoubleArray(slots)
            val first = IntArray(slots) { Int.MAX_VALUE }
            val last = IntArray(slots) { -1 }
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val label = labelValues[row + x]
                    if (label == 0) continue
                    val slot = label * (sections + 1) + x / sectionWidth
                    n[slot] += 1.0
                    sumX[slot] += x.toDouble()
                    sumY[slot] += y.toDouble()
                    sumXX[slot] += x.toDouble() * x
                    sumXY[slot] += x.toDouble() * y
                    if (x < first[slot]) first[slot] = x
                    if (x > last[slot]) last[slot] = x
                }
            }
            class Piece(val label: Int, val slope: Double, val centre: Double, val start: Double, val end: Double)
            fun fit(a: Double, b: Double, c: Double, d: Double, e: Double): Pair<Double, Double>? {
                val spread = a * d - b * b
                if (spread < 1e-6) return null
                val slope = (a * e - b * c) / spread
                return slope to (c - slope * b) / a + slope * width / 2.0
            }
            val pieces = mutableListOf<Piece>()
            for (slot in 0 until slots) {
                if (last[slot] < 0 || last[slot] - first[slot] < .05 * width) continue
                val (slope, centre) = fit(n[slot], sumX[slot], sumY[slot], sumXX[slot], sumXY[slot]) ?: continue
                if (abs(slope) > .35) continue
                pieces += Piece(slot, slope, centre, first[slot].toDouble(), last[slot] + 1.0)
            }
            // Long pieces anchor a rule. A shorter one joins by where it lies, as its own slope is noisy.
            val groups = mutableListOf<MutableList<Piece>>()
            for (piece in pieces.sortedByDescending { it.end - it.start }) {
                val middle = (piece.start + piece.end) / 2
                val height0 = piece.centre + piece.slope * (middle - width / 2.0)
                val group =
                    groups.firstOrNull {
                        val anchor = it[0]
                        abs(anchor.centre + anchor.slope * (middle - width / 2.0) - height0) < .007 * height &&
                            (piece.end - piece.start < .11 * width || abs(anchor.slope - piece.slope) < .02)
                    }
                if (group != null) group += piece
                else if (piece.end - piece.start >= .11 * width) groups += mutableListOf(piece)
            }
            val lines =
                groups
                    .mapNotNull { group ->
                        val span = group.maxOf { it.end } - group.minOf { it.start }
                        if (span < minimumSpan * width) return@mapNotNull null
                        val (slope, centre) =
                            fit(
                                group.sumOf { n[it.label] },
                                group.sumOf { sumX[it.label] },
                                group.sumOf { sumY[it.label] },
                                group.sumOf { sumXX[it.label] },
                                group.sumOf { sumXY[it.label] },
                            ) ?: return@mapNotNull null
                        Rule(slope, centre, span)
                    }
                    .sortedBy { it.centre }
            // Both edges of one rule, or a double rule, count once.
            val merged = mutableListOf<Rule>()
            for (line in lines) {
                if (merged.isNotEmpty() && line.centre - merged.last().centre < .012 * height) {
                    if (line.span > merged.last().span) merged[merged.lastIndex] = line
                } else merged += line
            }
            return merged
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** slope = p * centre + q, fitted by consensus over line pairs and refined by least squares. */
    internal fun fan(rules: List<Rule>): Pair<Double, Double>? {
        val tolerance = ScanConfig.float("ruleRectifyTolerance", .008f).toDouble()
        var bestCount = 0
        var bestError = Double.MAX_VALUE
        var best: Pair<Double, Double>? = null
        for (i in rules.indices) for (j in i + 1 until rules.size) {
            val run = rules[j].centre - rules[i].centre
            if (abs(run) < 1e-3) continue
            val p = (rules[j].slope - rules[i].slope) / run
            val q = rules[i].slope - p * rules[i].centre
            var count = 0
            var error = 0.0
            for (rule in rules) {
                val e = abs(rule.slope - (p * rule.centre + q))
                if (e < tolerance) count++
                error += e
            }
            if (count > bestCount || (count == bestCount && error < bestError)) {
                bestCount = count
                bestError = error
                best = p to q
            }
        }
        val (p0, q0) = best ?: return null
        val kept = rules.filter { abs(it.slope - (p0 * it.centre + q0)) < tolerance }
        if (kept.size < 2) return null
        val meanX = kept.sumOf { it.centre } / kept.size
        val meanY = kept.sumOf { it.slope } / kept.size
        val spread = kept.sumOf { (it.centre - meanX).pow(2) }
        if (spread < 1e-6) return null
        val p = kept.sumOf { (it.centre - meanX) * (it.slope - meanY) } / spread
        return p to meanY - p * meanX
    }
}
