package cn.paperforge.app.imaging

import cn.paperforge.app.model.*
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Recovered edge network, with PaperForge's contour fitting (not the original EDLine stage). */
object NeuralEdgeDetector {
    fun detect(source: Mat, preview: Boolean): DetectionResult {
        val started = System.nanoTime()
        val values = ScanModel.run(source)
        val result = fit(values)
        val quad = result.first?.let { if (preview) it else DocumentDetector.refine(source, it) }
        return DetectionResult(
            quad,
            result.second,
            "neural-edge" + if (preview) "" else "+refined",
            elapsedMs = (System.nanoTime() - started) / 1e6f,
        )
    }

    internal fun fit(values: FloatArray): Pair<Quad?, Float> {
        require(values.size >= 240 * 240)
        val heat = Mat(240, 240, CvType.CV_8UC1)
        val binary = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        var best: Quad? = null
        var bestScore = 0f
        var bestConfidence = 0f
        fun support(a: PointN, b: PointN): Float {
            var supported = 0
            for (i in 0..63) {
                val t = i / 63f
                val x = ((a.x + (b.x - a.x) * t) * 239).roundToInt()
                val y = ((a.y + (b.y - a.y) * t) * 239).roundToInt()
                var peak = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 0..239 && py in 0..239) peak = max(peak, values[py * 240 + px])
                }
                if (peak >= .2f) supported++
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
                        val curve = MatOfPoint2f(*contour.toArray())
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
                                    bestConfidence =
                                        (.45f + .35f * mean + .2f * minimum).coerceIn(0f, .99f)
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
            return best to bestConfidence
        } finally {
            heat.release()
            binary.release()
            hierarchy.release()
            kernel.release()
        }
    }
}
