package cn.paperforge.app.imaging

import cn.paperforge.app.model.DewarpGrid
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Original text-ridge estimator; no learned weights or reference-app internals. */
internal object TextLineDewarp {
    data class Estimate(
        val grid: DewarpGrid,
        val lines: Int,
        val note: String,
        val baselineAngle: Double? = null,
        val baselineMad: Double = 0.0,
        val meanSpan: Double = 0.0,
    )

    private data class Ridge(
        val center: Double,
        val curve: Double,
        val span: Int,
        val slope: Double,
    )

    fun estimate(source: Mat, strength: Float): Estimate {
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        val small = mat()
        val gray = mat()
        val ink = mat()
        val joined = mat()
        val labels = mat()
        val stats = mat()
        val centers = mat()
        try {
            val originalWidth = source.cols()
            val originalHeight = source.rows()
            val scale =
                min(
                    1.0,
                    ScanConfig.int("dewarpWorkEdge", 1600).toDouble() /
                        max(source.cols(), source.rows()),
                )
            Imgproc.resize(source, small, Size(), scale, scale, Imgproc.INTER_AREA)
            when (small.channels()) {
                1 -> small.copyTo(gray)
                4 -> Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
            }
            val w = gray.cols()
            val h = gray.rows()
            Imgproc.adaptiveThreshold(
                gray,
                ink,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                ScanConfig.int("dewarpThresholdWindow", 41).coerceIn(3, 201) or 1,
                ScanConfig.float("dewarpThresholdBias", 12f).toDouble(),
            )
            val kernel =
                Imgproc.getStructuringElement(
                        Imgproc.MORPH_RECT,
                        Size(
                            max(3, (w * ScanConfig.float("dewarpJoinFraction", .025f)).roundToInt())
                                .toDouble(),
                            1.0,
                        ),
                    )
                    .also(owned::add)
            Imgproc.morphologyEx(ink, joined, Imgproc.MORPH_CLOSE, kernel)
            val count =
                Imgproc.connectedComponentsWithStats(
                    joined,
                    labels,
                    stats,
                    centers,
                    8,
                    CvType.CV_32S,
                )
            val pixels = ByteArray(w * h).also { ink.get(0, 0, it) }
            val regions = IntArray(w * h).also { labels.get(0, 0, it) }
            val ridges = mutableListOf<Ridge>()
            for (label in 1 until count) {
                val row = IntArray(5).also { stats.get(label, 0, it) }
                val x0 = row[0]
                val y0 = row[1]
                val width = row[2]
                val height = row[3]
                if (
                    width < w * ScanConfig.float("dewarpMinimumLineSpan", .35f) ||
                        height !in 3..max(4, (h * .14).toInt())
                )
                    continue
                val samples = mutableListOf<Pair<Double, Double>>()
                val step = max(4, w / 80)
                for (x in x0 until x0 + width step step) {
                    var sum = 0.0
                    var n = 0
                    for (xx in x until min(x + step, x0 + width)) for (y in y0 until y0 + height) {
                        val i = y * w + xx
                        if (regions[i] == label && pixels[i].toInt() != 0) {
                            sum += y
                            n++
                        }
                    }
                    if (n >= 3) samples += (2.0 * (x + step * .5) / (w - 1) - 1.0) to sum / n
                }
                if (samples.size < 10) continue
                var selected = samples.toList()
                var curve = fit(selected) ?: continue
                repeat(2) {
                    val errors =
                        selected.map { (x, y) ->
                            abs(y - (curve[0] + curve[1] * x + curve[2] * x * x))
                        }
                    val limit = max(2.0, errors.sorted()[errors.size / 2] * 3.0)
                    selected = selected.filterIndexed { i, _ -> errors[i] <= limit }
                    if (selected.size >= 8) fit(selected)?.let { curve = it }
                }
                val error =
                    selected
                        .map { (x, y) -> abs(y - (curve[0] + curve[1] * x + curve[2] * x * x)) }
                        .average()
                if (
                    error > max(3.0, height * .18) ||
                        abs(curve[2]) > h * ScanConfig.float("dewarpMaximumDisplacement", .045f) ||
                        abs(curve[1]) > h * .08 ||
                        !curve.all(Double::isFinite)
                )
                    continue
                ridges += Ridge(curve[0], curve[2], width, curve[1])
            }
            val lines =
                ridges
                    .sortedByDescending { it.span }
                    .fold(mutableListOf<Ridge>()) { list, ridge ->
                        if (list.none { abs(it.center - ridge.center) < h * .012 }) list += ridge
                        list
                    }
                    .sortedBy { it.center }
            val enough =
                lines.size >= ScanConfig.int("dewarpMinimumLines", 3) &&
                    lines.last().center - lines.first().center > h * .16
            val amplitude =
                if (enough)
                    lines
                        .map {
                            abs(it.curve) +
                                abs(it.slope) * ScanConfig.float("dewarpSlopeWeight", 1f)
                        }
                        .sorted()[lines.size / 2]
                else 0.0
            val active = enough && amplitude >= h * ScanConfig.float("dewarpMinimumCurve", .0015f)
            fun displacement(y: Double, u: Double): Double {
                if (!active) return 0.0
                fun offset(line: Ridge) =
                    line.curve * (u * u - 1.0 / 3) +
                        line.slope * u * ScanConfig.float("dewarpSlopeWeight", 1f)
                val after = lines.indexOfFirst { it.center >= y }
                if (after == 0) return offset(lines.first())
                if (after < 0) return offset(lines.last())
                val a = lines[after - 1]
                val b = lines[after]
                val t = ((y - a.center) / max(1.0, b.center - a.center)).coerceIn(0.0, 1.0)
                return offset(a) * (1 - t) + offset(b) * t
            }
            val side = ScanConfig.int("dewarpGridSide", 96).coerceIn(16, 240)
            val values = FloatArray(side * side * 2)
            for (gy in 0 until side) for (gx in 0 until side) {
                val x =
                    (((gx + .5) * originalWidth / side - .5) / (originalWidth - 1)).coerceIn(
                        0.0,
                        1.0,
                    )
                val y =
                    (((gy + .5) * originalHeight / side - .5) / (originalHeight - 1)).coerceIn(
                        0.0,
                        1.0,
                    )
                val u = 2 * x - 1
                val edgeBlend = min(1.0, min(y, 1 - y) / .08).coerceAtLeast(0.0)
                val delta =
                    displacement(y * (h - 1), u).coerceIn(-h * .045, h * .045) / (h - 1) * edgeBlend
                val index = 2 * (gy * side + gx)
                values[index] = x.toFloat()
                // Strictly increasing backward mapping prevents folded/duplicated text rows.
                val lower = if (gy == 0) 0f else values[index - 2 * side + 1] + .25f / side
                val upper = 1f - (side - 1 - gy) * .25f / side
                values[index + 1] = (y + delta).toFloat().coerceIn(lower, upper)
            }
            val angles = lines.map { atan(2 * it.slope / (w - 1)) * 180 / PI }.sorted()
            val angle = angles.getOrNull(angles.size / 2) ?: 0.0
            val mad =
                angles.map { abs(it - angle) }.sorted().let { it.getOrNull(it.size / 2) ?: 0.0 }
            val trustedBaseline =
                lines.size >= 5 &&
                    enough &&
                    mad <= .25 &&
                    lines.last().center - lines.first().center >= h * .30 &&
                    lines.count { abs(it.curve) > h * .003 } <= lines.size / 4
            return Estimate(
                DewarpGrid(
                    originalWidth,
                    originalHeight,
                    if (active) strength.coerceIn(0f, 1f) else 0f,
                    side,
                    values,
                ),
                lines.size,
                when {
                    !enough -> "可用文字行不足，保留原图"
                    !active -> "文字行已接近平直，保留原图"
                    else -> "基于 ${lines.size} 条文字行进行曲率校正"
                },
                if (trustedBaseline) angle else null,
                mad,
                lines.map { it.span.toDouble() / w }.average().takeIf { it.isFinite() } ?: 0.0,
            )
        } finally {
            owned.forEach(Mat::release)
        }
    }

    /** Small original least-squares solver with partial pivoting. */
    private fun fit(points: List<Pair<Double, Double>>): DoubleArray? {
        val a = Array(3) { DoubleArray(4) }
        for ((x, y) in points) {
            val basis = doubleArrayOf(1.0, x, x * x)
            for (r in 0..2) {
                for (c in 0..2) a[r][c] += basis[r] * basis[c]
                a[r][3] += basis[r] * y
            }
        }
        for (c in 0..2) {
            val pivot = (c..2).maxBy { abs(a[it][c]) }
            if (abs(a[pivot][c]) < 1e-8) return null
            val swap = a[c]
            a[c] = a[pivot]
            a[pivot] = swap
            val divisor = a[c][c]
            for (j in c..3) a[c][j] /= divisor
            for (r in 0..2) if (r != c) {
                val factor = a[r][c]
                for (j in c..3) a[r][j] -= factor * a[c][j]
            }
        }
        return DoubleArray(3) { a[it][3] }
    }
}
