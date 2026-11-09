package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import kotlin.math.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Original distributed-line consensus, using the already bundled OpenCV LSD detector. */
internal object WorkbookStructure {
    const val VERSION = "workbook-structure-1"

    data class Segment(
        val x: Double,
        val y: Double,
        val length: Double,
        val angle: Double,
        val vertical: Boolean,
        val start: Double,
        val end: Double,
    )

    data class Axis(val angle: Double, val confidence: Double, val count: Int, val spread: Double)

    data class Estimate(val horizontal: Axis, val vertical: Axis, val textHint: Boolean = false) {
        val active
            get() =
                (horizontal.confidence >= .7 && abs(horizontal.angle) >= .06) ||
                    (vertical.confidence >= .7 &&
                        horizontal.confidence >= .7 &&
                        abs(vertical.angle + horizontal.angle) >= .06)

        fun json() =
            JSONObject()
                .put("version", VERSION)
                .put("active", active)
                .put("textBaselineHint", textHint)
                .put("horizontalDegrees", horizontal.angle)
                .put("horizontalConfidence", horizontal.confidence)
                .put("horizontalLines", horizontal.count)
                .put("horizontalMAD", horizontal.spread)
                .put("verticalDegrees", vertical.angle)
                .put("verticalConfidence", vertical.confidence)
                .put("verticalLines", vertical.count)
                .put("verticalMAD", vertical.spread)
                .put(
                    "fallback",
                    if (active) "bounded-affine-composed-with-grid"
                    else if (horizontal.confidence >= .7 || vertical.confidence >= .7)
                        "identity-already-straight"
                    else "identity-insufficient-structure",
                )
    }

    fun analyze(source: Mat, textFallback: Boolean = true): Estimate {
        val gray = Mat()
        val small = Mat()
        val lines = Mat()
        val detector = Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD)
        try {
            when (source.channels()) {
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
                else -> source.copyTo(gray)
            }
            val scale =
                min(
                    1.0,
                    ScanConfig.int("workbookStructureEdge", 900).toDouble() /
                        max(gray.cols(), gray.rows()),
                )
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            detector.detect(small, lines)
            val w = small.cols()
            val h = small.rows()
            val pixels = ByteArray(w * h).also { small.get(0, 0, it) }
            val values =
                FloatArray((lines.total() * lines.channels()).toInt()).also {
                    if (it.isNotEmpty()) lines.get(0, 0, it)
                }
            val segments = mutableListOf<Segment>()
            fun at(x: Double, y: Double) =
                pixels[y.roundToInt().coerceIn(0, h - 1) * w + x.roundToInt().coerceIn(0, w - 1)]
                    .toInt() and 255
            for (i in values.indices step 4) {
                val x0 = values[i].toDouble()
                val y0 = values[i + 1].toDouble()
                val x1 = values[i + 2].toDouble()
                val y1 = values[i + 3].toDouble()
                val dx = x1 - x0
                val dy = y1 - y0
                val length = hypot(dx, dy)
                val vertical = abs(dy) > abs(dx)
                if (length < (if (vertical) h else w) * .025) continue
                val angle = atan(if (vertical) dx / dy else dy / dx) * 180 / PI
                if (
                    !angle.isFinite() ||
                        abs(angle) > ScanConfig.float("workbookStructureMaximumAngle", 4f)
                )
                    continue
                // Normal contrast rejects illumination ramps; do not trust all long image edges.
                var contrast = 0.0
                for (t in listOf(.2, .5, .8)) {
                    val x = x0 + dx * t
                    val y = y0 + dy * t
                    val a = at(x + 3 * dy / length, y - 3 * dx / length)
                    val b = at(x - 3 * dy / length, y + 3 * dx / length)
                    contrast += max(abs(at(x, y) - a), abs(at(x, y) - b))
                }
                if (contrast / 3 < 8) continue
                val slope = tan(angle * PI / 180)
                segments +=
                    Segment(
                        if (vertical) ((x0 + x1) / 2 - slope * ((y0 + y1) / 2 - h / 2.0)) / w
                        else (x0 + x1) / 2 / w,
                        if (!vertical) ((y0 + y1) / 2 - slope * ((x0 + x1) / 2 - w / 2.0)) / h
                        else (y0 + y1) / 2 / h,
                        length,
                        angle,
                        vertical,
                        if (vertical) min(y0, y1) / h else min(x0, x1) / w,
                        if (vertical) max(y0, y1) / h else max(x0, x1) / w,
                    )
            }
            fun axis(vertical: Boolean): Axis {
                val groups = mutableListOf<MutableList<Segment>>()
                for (s in
                    segments.filter { it.vertical == vertical }.sortedByDescending { it.length }) {
                    val location = if (vertical) s.x else s.y
                    val group =
                        groups.firstOrNull { g ->
                            abs(g.first().angle - s.angle) < .35 &&
                                abs((if (vertical) g.first().x else g.first().y) - location) < .009
                        }
                    if (group == null) groups += mutableListOf(s) else group += s
                }
                val candidates =
                    groups
                        .map { g ->
                            val length = g.sumOf { it.length }
                            Segment(
                                g.sumOf { it.x * it.length } / length,
                                g.sumOf { it.y * it.length } / length,
                                length,
                                g.sumOf { it.angle * it.length } / length,
                                vertical,
                                g.minOf { it.start },
                                g.maxOf { it.end },
                            )
                        }
                        .filter {
                            it.length >= (if (vertical) h else w) * .30 && it.end - it.start >= .28
                        }
                        .sortedByDescending { it.length }
                if (candidates.isEmpty()) return Axis(0.0, 0.0, 0, 0.0)
                fun median(data: List<Segment>, value: (Segment) -> Double): Double {
                    val ordered = data.sortedBy(value)
                    val half = data.sumOf { it.length } / 2
                    var weight = 0.0
                    for (s in ordered) {
                        weight += s.length
                        if (weight >= half) return value(s)
                    }
                    return value(ordered.last())
                }
                val center = median(candidates) { it.angle }
                // Merge duplicate LSD boundaries of the same physical rule before counting.
                val selected =
                    candidates
                        .filter { abs(it.angle - center) <= .45 }
                        .fold(mutableListOf<Segment>()) { list, s ->
                            val location = if (vertical) s.x else s.y
                            if (list.none { abs((if (vertical) it.x else it.y) - location) < .018 })
                                list += s
                            list
                        }
                if (selected.isEmpty()) return Axis(0.0, 0.0, 0, 0.0)
                val angle = median(selected) { it.angle }
                val mad = median(selected) { abs(it.angle - angle) }
                val extent =
                    selected.maxOf { if (vertical) it.x else it.y } -
                        selected.minOf { if (vertical) it.x else it.y }
                val edgePair =
                    vertical &&
                        selected.size == 2 &&
                        extent >= .65 &&
                        selected.all { it.end - it.start >= .65 && (it.x < .12 || it.x > .88) }
                val enough =
                    ((selected.size >= (if (vertical) 3 else 4) && extent >= .30) || edgePair) &&
                        mad <= .25
                return Axis(
                    angle,
                    if (enough) (.95 - mad).coerceIn(.7, .95) else .0,
                    selected.size,
                    mad,
                )
            }
            val horizontal = axis(false)
            val vertical = axis(true)
            if (horizontal.confidence >= .7) return Estimate(horizontal, vertical)
            if (!textFallback) return Estimate(horizontal, vertical)
            // Reuse the existing robust text-ridge fit only as an orientation hint, not another
            // warp.
            val text = TextLineDewarp.estimate(small, 0f)
            val hint = text.baselineAngle
            return if (hint != null && abs(hint) <= 4.0)
                Estimate(Axis(hint, .7, text.lines, text.baselineMad), vertical, true)
            else Estimate(horizontal, vertical)
        } finally {
            detector.clear()
            listOf(gray, small, lines).forEach(Mat::release)
        }
    }

    /** Fixed canvas, fit ALL source corners. Empty margins are white, not cropped or replicated. */
    class Mapping(val width: Int, val height: Int, estimate: Estimate) {
        private val hy =
            if (estimate.horizontal.confidence >= .7) tan(estimate.horizontal.angle * PI / 180)
            else 0.0
        private val vx =
            if (estimate.vertical.confidence >= .7 && estimate.horizontal.confidence >= .7)
                tan(estimate.vertical.angle * PI / 180)
            else -hy
        private val determinant = 1 - hy * vx
        private val scale =
            min(
                1.0 / (abs(1 / determinant) + abs(vx / determinant) * height / width),
                1.0 / (abs(1 / determinant) + abs(hy / determinant) * width / height),
            )

        fun input(p: PointN): PointN {
            val x = (p.x - .5) * (width - 1) / scale
            val y = (p.y - .5) * (height - 1) / scale
            return PointN(
                ((x + vx * y) / (width - 1) + .5).toFloat(),
                ((hy * x + y) / (height - 1) + .5).toFloat(),
            )
        }
    }
}
