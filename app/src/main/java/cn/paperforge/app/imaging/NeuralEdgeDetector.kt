package cn.paperforge.app.imaging

import cn.paperforge.app.model.*
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Measured evidence behind a neural-edge confidence. All values live on the 240x240 heat map;
 * [aspect] is the source width/height used only by the aspect prior (the network input is squashed
 * to a square, so normalised side lengths alone cannot tell a page from a strip).
 */
internal data class EdgeConfidenceFeatures(
    val edgeSupportMean: Float,
    val edgeSupportMin: Float,
    val edgeSupportSpread: Float,
    val edgeStrengthMean: Float,
    val edgeResidualMean: Float,
    val contourResidualMean: Float,
    val contourResidualP90: Float,
    val cornerPeakMin: Float,
    val cornerPeakMean: Float,
    val area: Float,
    val aspect: Float,
)

/**
 * Continuous confidence for the neural edge fit (#21). The old score was
 * `(.45f + .35f*mean + .2f*minimum).coerceIn(0f, .99f)`, which hit 0.99 on every clean page and
 * every real DocUNet photo, so capture/tracker/fallback thresholds could not tell qualities apart.
 *
 * This score combines:
 *  - per-side support fraction (mean, minimum, dispersion across the four sides),
 *  - continuous local edge strength (the fraction above a hard threshold saturates),
 *  - how far the sub-pixel ridge peak sits from the fitted straight edge (curved/inset receipts),
 *  - how well the straight quad still explains the contour it was approximated from,
 *  - corner heat-map peaks (rounded or occluded corners),
 *  - loose area/aspect priors (only degenerate strips and near-full-frame quads are discounted).
 *
 * The output is an affine map of [0,1] evidence with an open top end (< 0.999); no value is pinned
 * to a shared ceiling. Thresholds keep their historical meaning: >=.72 confident fit, >=.60
 * auto-capture floor, >=.55 trackable outline, below .55 discarded.
 */
internal object EdgeConfidence {
    fun score(f: EdgeConfidenceFeatures): Float {
        val edgeSupport =
            .45f * f.edgeSupportMean + .35f * f.edgeSupportMin + .20f * f.edgeStrengthMean
        val spreadPenalty = .30f * f.edgeSupportSpread
        val edge = (edgeSupport - spreadPenalty).coerceIn(0f, 1f)
        val alignment = (1f - f.edgeResidualMean / 3f).coerceIn(0f, 1f)
        val structure = (1f - (f.contourResidualP90 - 1f) / 3f).coerceIn(0f, 1f)
        val corner = .55f * f.cornerPeakMean + .45f * f.cornerPeakMin
        val prior = areaPrior(f.area) * aspectPrior(f.aspect)
        val quality =
            .15f * edge + .20f * alignment + .30f * structure + .15f * corner + .20f * prior
        return (.10f + .88f * quality).coerceIn(0f, .999f)
    }

    private fun areaPrior(area: Float): Float =
        when {
            area < .04f -> .50f
            area < .06f -> .85f
            area <= .90f -> 1f
            area >= .97f -> .60f
            else -> 1f - .20f * ((area - .90f) / .07f)
        }

    private fun aspectPrior(aspect: Float): Float =
        when {
            aspect <= 3f -> 1f
            aspect >= 7f -> .70f
            else -> 1f - .30f * (aspect - 3f) / 4f
        }
}

/** Recovered edge network, with PaperForge's contour fitting (not the original EDLine stage). */
object NeuralEdgeDetector {
    internal data class Fit(
        val quad: Quad?,
        val confidence: Float,
        val features: EdgeConfidenceFeatures?,
    )

    private data class ContourShape(val mean: Float, val p90: Float)

    fun detect(source: Mat, preview: Boolean): DetectionResult {
        val started = System.nanoTime()
        val values = ScanModel.run(source)
        val result = fit(values, source.cols().toFloat() / max(1, source.rows()))
        val quad = result.quad?.let { if (preview) it else DocumentDetector.refine(source, it) }
        return DetectionResult(
            quad = quad,
            confidence = result.confidence,
            method = "neural-edge" + if (preview) "" else "+refined",
            diagnostics =
                result.features?.let {
                    mapOf(
                        "edge_support_mean" to it.edgeSupportMean,
                        "edge_support_min" to it.edgeSupportMin,
                        "edge_support_spread" to it.edgeSupportSpread,
                        "edge_strength" to it.edgeStrengthMean,
                        "edge_residual" to it.edgeResidualMean,
                        "contour_residual_mean" to it.contourResidualMean,
                        "contour_residual_p90" to it.contourResidualP90,
                        "corner_peak_min" to it.cornerPeakMin,
                        "corner_peak_mean" to it.cornerPeakMean,
                        "quad_area" to it.area,
                        "quad_aspect" to it.aspect,
                    )
                } ?: emptyMap(),
            elapsedMs = (System.nanoTime() - started) / 1e6f,
        )
    }

    internal fun fit(values: FloatArray, aspect: Float = 1f): Fit {
        require(values.size >= 240 * 240)
        val heat = Mat(240, 240, CvType.CV_8UC1)
        val binary = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        var best: Quad? = null
        var bestScore = 0f
        var bestShape: ContourShape? = null
        fun peak(x: Int, y: Int): Float {
            var value = 0f
            for (dy in -2..2) for (dx in -2..2) {
                val px = x + dx
                val py = y + dy
                if (px in 0..239 && py in 0..239) value = max(value, values[py * 240 + px])
            }
            return value
        }
        fun support(a: PointN, b: PointN): Float {
            var supported = 0
            for (i in 0..63) {
                val t = i / 63f
                val x = ((a.x + (b.x - a.x) * t) * 239).roundToInt()
                val y = ((a.y + (b.y - a.y) * t) * 239).roundToInt()
                if (peak(x, y) >= .2f) supported++
            }
            return supported / 64f
        }
        try {
            heat.put(
                0,
                0,
                ByteArray(240 * 240) { (values[it].coerceIn(0f, 1f) * 255).toInt().toByte() },
            )
            for (threshold in listOf(40.0, 80.0, 128.0, 176.0)) {
                Imgproc.threshold(heat, binary, threshold, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
                val contours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(
                        binary,
                        contours,
                        hierarchy,
                        Imgproc.RETR_LIST,
                        Imgproc.CHAIN_APPROX_SIMPLE,
                    )
                    for (contour in contours) {
                        if (abs(Imgproc.contourArea(contour)) < 240 * 240 * .04) continue
                        val contourPoints = contour.toArray()
                        val curve = MatOfPoint2f(*contourPoints)
                        val poly = MatOfPoint2f()
                        try {
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (epsilon in listOf(.012, .022, .035, .05)) {
                                Imgproc.approxPolyDP(curve, poly, perimeter * epsilon, true)
                                if (poly.total() != 4L) continue
                                val q =
                                    Quad.ordered(
                                        poly.toArray().map {
                                            PointN(
                                                (it.x / 239).toFloat().coerceIn(0f, 1f),
                                                (it.y / 239).toFloat().coerceIn(0f, 1f),
                                            )
                                        }
                                    )
                                if (!q.valid() || q.area < .04f) continue
                                val edges =
                                    q.points.indices.map {
                                        support(q.points[it], q.points[(it + 1) % 4])
                                    }
                                val mean = edges.average().toFloat()
                                val minimum = edges.min()
                                if (mean < .5f || minimum < .25f) continue
                                val score = .65f * mean + .2f * minimum + .15f * sqrt(q.area)
                                if (score > bestScore) {
                                    best = q
                                    bestScore = score
                                    bestShape = contourShape(contourPoints, q)
                                }
                            }
                        } finally {
                            curve.release()
                            poly.release()
                        }
                    }
                } finally {
                    contours.forEach { it.release() }
                }
            }
            val chosen = best ?: return Fit(null, 0f, null)
            val features = measure(values, chosen, bestShape, aspect)
            return Fit(chosen, EdgeConfidence.score(features), features)
        } finally {
            heat.release()
            binary.release()
            hierarchy.release()
            kernel.release()
        }
    }

    /**
     * Distance from the contour that produced the quad to the straight quad itself. A clean page
     * contour hugs its four-point approximation; a curled/inset receipt makes the straight chord
     * deviate from the curved contour. Measured on the same contour so candidate selection is not
     * touched.
     */
    private fun contourShape(points: Array<Point>, quad: Quad): ContourShape {
        val step = max(1, points.size / 256)
        val distances = ArrayList<Float>(points.size / step + 1)
        for (index in points.indices step step) {
            val x = points[index].x.toFloat()
            val y = points[index].y.toFloat()
            var nearest = Float.MAX_VALUE
            for (edge in quad.points.indices) {
                val a = quad.points[edge]
                val b = quad.points[(edge + 1) % 4]
                val ax = a.x * 239f
                val ay = a.y * 239f
                val bx = b.x * 239f
                val by = b.y * 239f
                val dx = bx - ax
                val dy = by - ay
                val length = dx * dx + dy * dy
                val t = if (length < 1e-3f) 0f else ((x - ax) * dx + (y - ay) * dy) / length
                val px = ax + dx * t.coerceIn(0f, 1f)
                val py = ay + dy * t.coerceIn(0f, 1f)
                nearest = min(nearest, hypot(x - px, y - py))
            }
            distances.add(nearest)
        }
        distances.sort()
        val mean = distances.average().toFloat()
        val p90 = distances[(distances.size * 9 / 10).coerceAtMost(distances.size - 1)]
        return ContourShape(mean, p90)
    }

    /** Secondary pass over the chosen quad so candidate geometry/selection stays byte-identical. */
    private fun measure(
        values: FloatArray,
        quad: Quad,
        shape: ContourShape?,
        aspect: Float,
    ): EdgeConfidenceFeatures {
        fun peak(x: Int, y: Int): Float {
            var value = 0f
            for (dy in -2..2) for (dx in -2..2) {
                val px = x + dx
                val py = y + dy
                if (px in 0..239 && py in 0..239) value = max(value, values[py * 240 + px])
            }
            return value
        }
        fun sample(x: Float, y: Float): Float {
            if (x !in 0f..239f || y !in 0f..239f) return 0f
            val x0 = floor(x).toInt().coerceIn(0, 239)
            val y0 = floor(y).toInt().coerceIn(0, 239)
            val x1 = min(x0 + 1, 239)
            val y1 = min(y0 + 1, 239)
            val fx = x - x0
            val fy = y - y0
            val top = values[y0 * 240 + x0] * (1 - fx) + values[y0 * 240 + x1] * fx
            val bottom = values[y1 * 240 + x0] * (1 - fx) + values[y1 * 240 + x1] * fx
            return top * (1 - fy) + bottom * fy
        }
        class Edge(val support: Float, val strength: Float, val residual: Float)
        val edges =
            quad.points.indices.map { index ->
                val a = quad.points[index]
                val b = quad.points[(index + 1) % 4]
                val ax = a.x * 239f
                val ay = a.y * 239f
                val bx = b.x * 239f
                val by = b.y * 239f
                val length = hypot(bx - ax, by - ay)
                val nx = if (length > 1e-3f) -(by - ay) / length else 0f
                val ny = if (length > 1e-3f) (bx - ax) / length else 0f
                var supported = 0
                var strength = 0f
                var residual = 0f
                val order = listOf(0, -1, 1, -2, 2, -3, 3)
                for (i in 0..63) {
                    val t = i / 63f
                    val x = ax + (bx - ax) * t
                    val y = ay + (by - ay) * t
                    val value = peak(x.roundToInt(), y.roundToInt())
                    strength += value
                    if (value >= .2f) supported++
                    var bestOffset = 0
                    var bestValue = -1f
                    for (offset in order) {
                        val at = sample(x + nx * offset, y + ny * offset)
                        if (at > bestValue + 1e-4f) {
                            bestValue = at
                            bestOffset = offset
                        }
                    }
                    var refined = bestOffset.toFloat()
                    if (bestOffset in -2..2) {
                        val before = sample(x + nx * (bestOffset - 1), y + ny * (bestOffset - 1))
                        val after = sample(x + nx * (bestOffset + 1), y + ny * (bestOffset + 1))
                        val denominator = before - 2f * bestValue + after
                        if (abs(denominator) > 1e-5f) {
                            refined += .5f * (before - after) / denominator
                        }
                    }
                    residual += if (bestValue >= .2f) abs(refined).coerceAtMost(3f) else 3f
                }
                Edge(supported / 64f, strength / 64f, residual / 64f)
            }
        val supports = edges.map { it.support }
        val corners = quad.points.map {
            peak((it.x * 239f).roundToInt(), (it.y * 239f).roundToInt())
        }
        fun sideLength(index: Int): Float {
            val a = quad.points[index]
            val b = quad.points[(index + 1) % 4]
            return hypot((b.x - a.x) * aspect * 239f, (b.y - a.y) * 239f)
        }
        val horizontal = (sideLength(0) + sideLength(2)) / 2f
        val vertical = (sideLength(1) + sideLength(3)) / 2f
        val ratio = max(horizontal, vertical) / max(1e-3f, min(horizontal, vertical))
        return EdgeConfidenceFeatures(
            edgeSupportMean = supports.average().toFloat(),
            edgeSupportMin = supports.min(),
            edgeSupportSpread = supports.max() - supports.min(),
            edgeStrengthMean = edges.map { it.strength }.average().toFloat(),
            edgeResidualMean = edges.map { it.residual }.average().toFloat(),
            contourResidualMean = shape?.mean ?: 0f,
            contourResidualP90 = shape?.p90 ?: 0f,
            cornerPeakMin = corners.min(),
            cornerPeakMean = corners.average().toFloat(),
            area = quad.area,
            aspect = ratio,
        )
    }
}
