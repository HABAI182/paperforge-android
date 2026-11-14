package cn.paperforge.app.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The gutter of an open book or notebook photographed as one spread: the paper's own brightness dips toward the
 * fold from both sides, the whole way down. Measured on the paper alone, so print and handwriting do not count:
 * per column, the paper level is the bright part (80th percentile) of the column's pixels. A gutter is a column,
 * between 30 and 70 % of the width, whose paper level is below the paper on both sides (4 to 10 % of the width
 * away), and where most horizontal bands of the page have their own darkest paper too.
 *
 * Two-sided on purpose: the edge of a dark picture or a coloured block is darker on one side only, and in the
 * evaluation those were what a one-sided dip took for a gutter. A crease across a flyer, a table rule or a column
 * gap has no paper dip, or none the whole way down.
 *
 * Evaluated on photographs of open books and notebooks from Wikimedia Commons (see
 * docs/development/SCAN-ENGINE-2026-11-14.md): thresholds were chosen on half of them and on the selection halves of
 * the single-sheet sets, and reported on the other halves. [Strict] is for deciding without being asked (a capture
 * marks a spread); [Asked] is for the editor's split, where the user has already said the page is a spread.
 */
object SpreadGutter {
    const val Method = "paper-valley"

    class Thresholds(val valley: Float, val bands: Float)

    val Strict get() = Thresholds(ScanConfig.float("spreadGutterValley", .08f), ScanConfig.float("spreadGutterBands", .75f))
    val Asked get() = Thresholds(ScanConfig.float("spreadGutterAskedValley", .05f), ScanConfig.float("spreadGutterAskedBands", .5f))

    private const val WORK = 600

    /** Note key a capture's detection carries when its crop is an open spread; see [mark]. */
    const val NOTE = "spread"

    /**
     * Whether a still crop of [photo] by [quad] is an open two-page spread, with the strict thresholds; the gutter
     * as "top,bottom" fractions of the crop's width, or null. Only crops shaped like two pages side by side count
     * (width over height between 1.0 and 1.9; the evaluation's spreads were 1.1 to 1.85): a sheet folded once is
     * as tall as it is wide or taller, and a folded flyer had the same shadow down its middle.
     */
    fun mark(photo: Mat, quad: cn.paperforge.app.model.Quad): String? {
        if (ScanConfig.int("spreadMark", 1) == 0 || photo.empty() || !quad.valid()) return null
        val w = photo.cols() - 1.0
        val h = photo.rows() - 1.0
        val p = quad.points.map { org.opencv.core.Point(it.x * w, it.y * h) }
        fun len(a: org.opencv.core.Point, b: org.opencv.core.Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
        val across = max(len(p[0], p[1]), len(p[3], p[2]))
        val down = max(len(p[0], p[3]), len(p[1], p[2]))
        if (down < 1) return null
        val aspect = across / down
        if (aspect < ScanConfig.float("spreadAspectMin", 1f) || aspect > ScanConfig.float("spreadAspectMax", 1.9f)) return null
        val scale = 1600.0 / max(across, down)
        val ww = max(2, (across * scale).roundToInt())
        val hh = max(2, (down * scale).roundToInt())
        val owned = mutableListOf<Mat>()
        try {
            val src = org.opencv.core.MatOfPoint2f(*p.toTypedArray()).also(owned::add)
            val dst =
                org.opencv.core.MatOfPoint2f(
                        org.opencv.core.Point(0.0, 0.0),
                        org.opencv.core.Point(ww - 1.0, 0.0),
                        org.opencv.core.Point(ww - 1.0, hh - 1.0),
                        org.opencv.core.Point(0.0, hh - 1.0),
                    )
                    .also(owned::add)
            val transform = Imgproc.getPerspectiveTransform(src, dst).also(owned::add)
            val page = Mat().also(owned::add)
            Imgproc.warpPerspective(photo, page, transform, Size(ww.toDouble(), hh.toDouble()), Imgproc.INTER_AREA)
            val gray = Mat().also(owned::add)
            Imgproc.cvtColor(page, gray, if (photo.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val found = find(gray, Strict)
            val top = found.top
            if (!found.found) return null
            return "%.4f,%.4f".format(java.util.Locale.ROOT, top, found.bottom)
        } finally {
            owned.forEach(Mat::release)
        }
    }
    private const val BANDS = 8

    /** Whether a page's capture marked it as an open spread that has not been split or kept since. */
    fun noted(processingMetadata: String?): Boolean =
        runCatching {
                org.json.JSONObject(processingMetadata ?: return false)
                    .optJSONObject("capture")
                    ?.optJSONObject("notes")
                    ?.has(NOTE) == true
            }
            .getOrDefault(false)

    /** [processingMetadata] without the spread mark: after a split, or when the user keeps the spread. */
    fun withoutNote(processingMetadata: String): String =
        runCatching {
                val json = org.json.JSONObject(processingMetadata)
                json.optJSONObject("capture")?.optJSONObject("notes")?.remove(NOTE)
                json.toString()
            }
            .getOrDefault(processingMetadata)

    /** [gray] is one page crop, upright (the fold running down it), CV_8UC1. */
    fun find(gray: Mat, thresholds: Thresholds = Strict): SpineDetection {
        if (gray.empty() || gray.cols() < 120 || gray.rows() < 120) return none("图像过小")
        val small = Mat()
        try {
            val height = max(40, (gray.rows() * WORK.toDouble() / gray.cols()).roundToInt())
            Imgproc.resize(gray, small, Size(WORK.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 1.0)
            val w = WORK
            val h = height
            val pixels = ByteArray(w * h).also { small.get(0, 0, it) }
            val level = smooth(paperLevel(pixels, w, (.04 * h).toInt(), (.96 * h).toInt()), 3f)
            val bandCentres = FloatArray(BANDS)
            val bandMinima = IntArray(BANDS)
            for (k in 0 until BANDS) {
                val y0 = (h * (.05 + .9 * k / BANDS)).toInt()
                val y1 = (h * (.05 + .9 * (k + 1) / BANDS)).toInt()
                val band = smooth(paperLevel(pixels, w, y0, y1), 3f)
                val from = (.28 * w).toInt()
                val to = (.72 * w).toInt()
                var best = from
                for (x in from until to) if (band[x] < band[best]) best = x
                bandMinima[k] = best
                bandCentres[k] = ((y0 + y1) / 2f) / h
            }
            val near = (.04 * w).toInt()
            val far = (.10 * w).toInt()
            val tolerance = .03f * w
            var bestX = -1
            var bestValley = 0f
            var bestBands = 0f
            var bestLeft = 0f
            var bestRight = 0f
            for (x in (.30 * w).toInt() until (.70 * w).toInt()) {
                val left = mean(level, x - far, x - near)
                val right = mean(level, x + near, x + far)
                val valley = (min(left, right) - level[x]) / max(1f, max(left, right))
                val bands = bandMinima.count { abs(it - x) <= tolerance }.toFloat() / BANDS
                if (bestX < 0 || valley + .02f * bands > bestValley + .02f * bestBands) {
                    bestX = x
                    bestValley = valley
                    bestBands = bands
                    bestLeft = left
                    bestRight = right
                }
            }
            val evidence = mapOf("valley" to bestValley, "bands" to bestBands, "x" to bestX.toFloat() / (w - 1))
            if (bestValley < thresholds.valley || bestBands < thresholds.bands)
                return none(
                    "没有贯穿上下、两侧都更亮的书脊阴影（阴影 ${(bestValley * 100).roundToInt()}%，" +
                        "贯穿 ${(bestBands * 100).roundToInt()}%）",
                    evidence,
                )
            // The fold may lean in the crop: a line through the bands that agree.
            val agree = (0 until BANDS).filter { abs(bandMinima[it] - bestX) <= tolerance }
            var top = bestX.toFloat()
            var bottom = bestX.toFloat()
            if (agree.size >= 2) {
                val ys = agree.map { bandCentres[it] }
                val xs = agree.map { bandMinima[it].toFloat() }
                val my = ys.average().toFloat()
                val mx = xs.average().toFloat()
                var num = 0f
                var den = 0f
                for (i in ys.indices) {
                    num += (ys[i] - my) * (xs[i] - mx)
                    den += (ys[i] - my) * (ys[i] - my)
                }
                val slope = if (den > 1e-6f) num / den else 0f
                top = mx - slope * my
                bottom = mx + slope * (1 - my)
            }
            // The shadow's own width, where the paper is back halfway to the brighter side, so neither page keeps it.
            val floor = level[bestX]
            val halfLeft = floor + .5f * (bestLeft - floor)
            val halfRight = floor + .5f * (bestRight - floor)
            var a = bestX
            while (a > bestX - far && level[a] < halfLeft) a--
            var b = bestX
            while (b < bestX + far && level[b] < halfRight) b++
            val span = (w - 1).toFloat()
            fun frac(v: Float) = (v / span).coerceIn(0f, 1f)
            val confidence = (bestValley / (thresholds.valley * 2)).coerceIn(0f, 1f) * .6f + bestBands * .4f
            return SpineDetection(
                x = frac((top + bottom) / 2),
                top = frac(top),
                bottom = frac(bottom),
                confidence = confidence,
                method = Method,
                evidence = evidence,
                seamLeft = SpineEdge(frac(top - (bestX - a)), frac(bottom - (bestX - a))),
                seamRight = SpineEdge(frac(top + (b - bestX)), frac(bottom + (b - bestX))),
            )
        } finally {
            small.release()
        }
    }

    /** The bright part of each column between rows [from] and [to]: its 80th percentile. */
    private fun paperLevel(pixels: ByteArray, w: Int, from: Int, to: Int): FloatArray {
        val out = FloatArray(w)
        val counts = IntArray(256)
        val rows = max(1, to - from)
        val rank = (.8 * (rows - 1)).toInt()
        for (x in 0 until w) {
            counts.fill(0)
            for (y in from until to) counts[pixels[y * w + x].toInt() and 255]++
            var seen = 0
            var v = 0
            while (v < 255) {
                seen += counts[v]
                if (seen > rank) break
                v++
            }
            out[x] = v.toFloat()
        }
        return out
    }

    private fun smooth(values: FloatArray, sigma: Float): FloatArray {
        val radius = (3 * sigma).roundToInt()
        val kernel = FloatArray(2 * radius + 1) { val d = it - radius; kotlin.math.exp(-d * d / (2 * sigma * sigma)) }
        val sum = kernel.sum()
        return FloatArray(values.size) { x ->
            var acc = 0f
            for (k in -radius..radius) acc += kernel[k + radius] * values[(x + k).coerceIn(0, values.size - 1)]
            acc / sum
        }
    }

    private fun mean(values: FloatArray, from: Int, to: Int): Float {
        val a = from.coerceIn(0, values.size - 1)
        val b = to.coerceIn(a + 1, values.size)
        var s = 0f
        for (i in a until b) s += values[i]
        return s / (b - a)
    }

    private fun none(reason: String, evidence: Map<String, Float> = emptyMap()) =
        SpineDetection(x = null, method = BookSpineDetector.MethodNone, reason = reason, evidence = evidence)
}
