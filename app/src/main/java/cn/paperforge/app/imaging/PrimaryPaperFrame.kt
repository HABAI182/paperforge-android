package cn.paperforge.app.imaging

import cn.paperforge.app.model.*
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Tighten a stack of narrow exposed sheets to the innermost supported paper edge. Requires several
 * distinct long parallel boundaries; one table border is insufficient.
 */
internal object PrimaryPaperFrame {
    const val VERSION = "primary-paper-frame-3"

    fun refine(source: Mat, result: DetectionResult): DetectionResult {
        val quad = result.quad ?: return result
        if (result.confidence < .65f || ScanConfig.int("primaryPaperFrame", 1) == 0) return result
        val owned = mutableListOf<Mat>()
        fun own(m: Mat) = m.also(owned::add)
        val from =
            own(
                MatOfPoint2f(
                    *quad.points
                        .map { Point(it.x * (source.cols() - 1.0), it.y * (source.rows() - 1.0)) }
                        .toTypedArray()
                )
            )
        val to =
            own(
                MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(599.0, 0.0),
                    Point(599.0, 799.0),
                    Point(0.0, 799.0),
                )
            )
        val detector = Imgproc.createLineSegmentDetector()
        try {
            val transform =
                own(Imgproc.getPerspectiveTransform(from as MatOfPoint2f, to as MatOfPoint2f))
            val image = own(Mat())
            val gray = own(Mat())
            val lines = own(Mat())
            Imgproc.warpPerspective(source, image, transform, Size(600.0, 800.0))
            if (image.channels() == 1) image.copyTo(gray)
            else
                Imgproc.cvtColor(
                    image,
                    gray,
                    if (image.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY,
                )
            detector.detect(gray, lines)
            val values =
                FloatArray((lines.total() * lines.channels()).toInt()).also {
                    if (it.isNotEmpty()) lines.get(0, 0, it)
                }
            // Ruling intersections split a real sheet boundary into short LSD segments.
            // Union their vertical coverage instead of requiring a single unbroken segment.
            data class Track(var x: Double, var weight: Double, val covered: BooleanArray)
            fun edgeTracks(horizontal: Boolean): List<Double> {
                val tracks = mutableListOf<Track>()
                val segments =
                    values.indices
                        .step(4)
                        .mapNotNull { i ->
                            val dx = values[i + 2] - values[i]
                            val dy = values[i + 3] - values[i + 1]
                            if (
                                (!horizontal && (abs(dy) < 800 * .025 || abs(dx / dy) >= .035)) ||
                                    (horizontal && (abs(dx) < 600 * .025 || abs(dy / dx) >= .035))
                            )
                                null
                            else if (horizontal)
                                doubleArrayOf(
                                    (values[i + 1] + values[i + 3]) / 1600.0,
                                    min(values[i], values[i + 2]) / 600.0,
                                    max(values[i], values[i + 2]) / 600.0,
                                )
                            else
                                doubleArrayOf(
                                    (values[i] + values[i + 2]) / 1200.0,
                                    min(values[i + 1], values[i + 3]) / 800.0,
                                    max(values[i + 1], values[i + 3]) / 800.0,
                                )
                        }
                        .sortedBy { it[0] }
                for ((x, top, bottom) in segments) {
                    val weight = bottom - top
                    val track =
                        tracks.lastOrNull()?.takeIf { abs(it.x - x) < .009 }
                            ?: Track(x, 0.0, BooleanArray(64)).also(tracks::add)
                    track.x = (track.x * track.weight + x * weight) / (track.weight + weight)
                    track.weight += weight
                    for (y in
                        ceil(top * 64).toInt().coerceAtLeast(0)..floor(bottom * 64)
                                .toInt()
                                .coerceAtMost(63)) track.covered[y] = true
                }
                return tracks.filter { it.covered.count { yes -> yes } >= 38 }.map { it.x }
            }
            val edges = edgeTracks(false)
            val horizontalEdges = edgeTracks(true)
            fun unique(data: List<Double>) =
                data.sorted().fold(mutableListOf<Double>()) { a, v ->
                    if (a.isEmpty() || v - a.last() > .009) a += v
                    a
                }
            val left = unique(edges.filter { it in 0.002..0.13 })
            val right = unique(edges.filter { it in 0.87..0.998 })
            val table = edges.any { it in 0.17..0.83 }
            var l = if (!table && left.size >= 2 && left.first() < .045) left.last() + .003 else 0.0
            var r =
                if (!table && right.size >= 2 && right.last() > .955) right.first() - .003 else 1.0
            // A paper-to-desk transition is directional: the inside becomes lighter/less
            // chromatic across most of the edge. A notebook/table rule has paper on both sides.
            val pixels =
                ByteArray((image.total() * image.channels()).toInt()).also { image.get(0, 0, it) }
            val channels = image.channels()
            fun tone(x: Int, y: Int): Pair<Double, Double> {
                val i = (y.coerceIn(0, 799) * 600 + x.coerceIn(0, 599)) * channels
                val a = pixels[i].toInt() and 255
                val b = if (channels == 1) a else pixels[i + 1].toInt() and 255
                val c = if (channels == 1) a else pixels[i + 2].toInt() and 255
                return Pair((a + 2.0 * b + c) / 4, (maxOf(a, b, c) - minOf(a, b, c)).toDouble())
            }
            fun paperColour(x: Int, y: Int): Pair<Double, Double> {
                if (channels == 1) return 1.0 to 1.0
                val i = (y.coerceIn(0, 799) * 600 + x.coerceIn(0, 599)) * channels
                val r = pixels[i].toInt() and 255
                val g = pixels[i + 1].toInt() and 255
                val b = pixels[i + 2].toInt() and 255
                val luma = max(1.0, (r + 2.0 * g + b) / 4)
                return r / luma to b / luma
            }
            fun surfaceEdge(candidates: List<Double>, horizontal: Boolean, low: Boolean): Double? {
                if (ScanConfig.int("primaryPaperSurface", 1) == 0) return null
                val extent = if (horizontal) 800 else 600
                val cross = if (horizontal) 600 else 800
                val direction = if (low) 1 else -1
                var best: Double? = null
                var bestSupport = 0.0
                val colourStep = ScanConfig.float("primaryPaperChromaStep", .08f)
                for (edge in
                    candidates.filter { if (low) it in 0.004..0.12 else it in 0.88..0.996 }) {
                    val centre = (edge * (extent - 1)).roundToInt()
                    val light = mutableListOf<Double>()
                    val chroma = mutableListOf<Double>()
                    val material = mutableListOf<Double>()
                    var supported = 0
                    for (sample in 0..60) {
                        val along = ((.18 + .64 * sample / 60) * (cross - 1)).roundToInt()
                        var insideL = 0.0
                        var outsideL = 0.0
                        var insideC = 0.0
                        var outsideC = 0.0
                        var insideRed = 0.0
                        var outsideRed = 0.0
                        var insideBlue = 0.0
                        var outsideBlue = 0.0
                        for (offset in 3..7) {
                            val inside = centre + direction * offset
                            val outside = centre - direction * offset
                            val a = if (horizontal) tone(along, inside) else tone(inside, along)
                            val b = if (horizontal) tone(along, outside) else tone(outside, along)
                            insideL += a.first / 5
                            insideC += a.second / 5
                            outsideL += b.first / 5
                            outsideC += b.second / 5
                            val insideColour =
                                if (horizontal) paperColour(along, inside)
                                else paperColour(inside, along)
                            val outsideColour =
                                if (horizontal) paperColour(along, outside)
                                else paperColour(outside, along)
                            insideRed += insideColour.first / 5
                            insideBlue += insideColour.second / 5
                            outsideRed += outsideColour.first / 5
                            outsideBlue += outsideColour.second / 5
                        }
                        light += insideL - outsideL
                        chroma += outsideC - insideC
                        val differentMaterial =
                            max(abs(insideRed - outsideRed), abs(insideBlue - outsideBlue))
                        material += differentMaterial
                        if (
                            insideL > 80 &&
                                differentMaterial >= colourStep &&
                                (insideL - outsideL >= 6 ||
                                    (outsideC - insideC >= 10 && insideL >= outsideL - 2))
                        )
                            supported++
                    }
                    val delta = light.sorted()[30]
                    val colour = chroma.sorted()[30]
                    // An illumination step on the SAME paper is not a physical boundary. Require
                    // a persistent colour-ratio change as well; gray/ambiguous edges keep the
                    // original detector instead of trimming at a shaded notebook ruling.
                    val differentMaterial = material.sorted()[30] >= colourStep
                    val support = supported / 61.0
                    if (
                        support >= .72 &&
                            differentMaterial &&
                            (delta >= 10 || (delta >= 4 && colour >= 12)) &&
                            support > bestSupport
                    ) {
                        bestSupport = support
                        best = (edge + direction * .002).coerceIn(0.0, 1.0)
                    }
                }
                return best
            }
            l = max(l, surfaceEdge(edges, false, true) ?: 0.0)
            r = min(r, surfaceEdge(edges, false, false) ?: 1.0)
            val t = surfaceEdge(horizontalEdges, true, true) ?: 0.0
            val b = surfaceEdge(horizontalEdges, true, false) ?: 1.0
            if (l == 0.0 && r == 1.0 && t == 0.0 && b == 1.0) return result
            val inverse = own(transform.inv())
            val adjusted =
                own(
                    MatOfPoint2f(
                        Point(l * 599, t * 799),
                        Point(r * 599, t * 799),
                        Point(r * 599, b * 799),
                        Point(l * 599, b * 799),
                    )
                )
            val mapped = own(MatOfPoint2f())
            Core.perspectiveTransform(adjusted, mapped, inverse)
            val tightened =
                Quad(
                    (mapped as MatOfPoint2f).toArray().map {
                        PointN(
                            (it.x / (source.cols() - 1)).toFloat().coerceIn(0f, 1f),
                            (it.y / (source.rows() - 1)).toFloat().coerceIn(0f, 1f),
                        )
                    }
                )
            return if (tightened.valid())
                result.copy(
                    quad = tightened,
                    method = result.method + "+primary-sheet",
                    diagnostics =
                        result.diagnostics +
                            mapOf(
                                "primaryInsetLeft" to l.toFloat(),
                                "primaryInsetRight" to (1 - r).toFloat(),
                                "primaryInsetTop" to t.toFloat(),
                                "primaryInsetBottom" to (1 - b).toFloat(),
                            ),
                )
            else result
        } finally {
            detector.clear()
            owned.forEach(Mat::release)
        }
    }
}
