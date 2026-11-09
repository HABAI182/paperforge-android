package cn.paperforge.app.imaging

import kotlin.math.max
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** OpenCV adapter around the dependency-free [LineStraightness] core. */
object GridStraighten {
    const val VERSION = "grid-straight-1"

    data class Estimate(
        val correction: LineStraightness.Correction,
        val before: LineStraightness.Metrics,
        val after: LineStraightness.Metrics,
        val note: String,
        /** True when real ink sits in the outer 2% ring, where a straighten shift could push it out. */
        val edgeInk: Boolean = false,
    )

    fun analyze(source: Mat, maximumEdge: Int = 1000): Estimate? {
        if (source.empty()) return null
        val side = minOf(maximumEdge, max(source.cols(), source.rows()))
        val scale = side.toDouble() / max(source.cols(), source.rows())
        val gray = Mat()
        val small = Mat()
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
            }
            if (scale < 1.0) {
                Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                small.create(gray.rows(), gray.cols(), gray.type())
                gray.copyTo(small)
            }
            val width = small.cols()
            val height = small.rows()
            if (width < 160 || height < 160) return null
            val bytes = ByteArray(width * height).also { small.get(0, 0, it) }
            val result = LineStraightness.estimate(bytes, width, height) ?: return null
            if (!result.guard.accepted) return null
            return Estimate(
                result.correction,
                result.before,
                result.after,
                result.note,
                edgeInk = touchesEdge(bytes, width, height),
            )
        } finally {
            gray.release()
            small.release()
        }
    }

    /**
     * Accept a correction only when no axis gets worse and at least one clearly improves.
     * Full-chain DocUNet probes showed single-axis regressions on curled pages, so the
     * default path must fail closed instead of trusting the fit.
     */
    fun acceptable(estimate: Estimate): Boolean {
        val before = estimate.before
        val after = estimate.after
        if (estimate.correction.linesH + estimate.correction.linesV < 2) return false
        if (estimate.correction.maxShift > 0.02 * estimate.correction.side) return false
        // A straighten shift resamples the output frame and can push edge content outside the
        // source (remap uses BORDER_CONSTANT white). When ink already touches the frame edge,
        // only a sub-pixel correction is safe; otherwise the page keeps its existing geometry.
        if (
            estimate.edgeInk &&
                ScanConfig.int("gridStraightenEdgeGuard", 1) != 0 &&
                estimate.correction.maxShift > 0.01 * estimate.correction.side
        )
            return false
        if (before.inkMass > 0.0) {
            val ratio = after.inkMass / before.inkMass
            if (ratio !in 0.94..1.02) return false
        }
        if (!axisOk(before.horizontal, after.horizontal)) return false
        if (!axisOk(before.vertical, after.vertical)) return false
        if (!axisOk(before.text, after.text)) return false
        return improved(before.horizontal, after.horizontal) ||
            improved(before.vertical, after.vertical) ||
            improved(before.text, after.text)
    }

    private fun axisOk(
        before: LineStraightness.AxisMetric,
        after: LineStraightness.AxisMetric,
    ): Boolean =
        before.lines < 2 ||
            after.lines < 1 ||
            (after.tiltAbsMedianDeg <= before.tiltAbsMedianDeg + .05 &&
                after.rmsMedian <= before.rmsMedian + .05)

    private fun improved(
        before: LineStraightness.AxisMetric,
        after: LineStraightness.AxisMetric,
    ): Boolean =
        before.lines >= 2 &&
            after.lines >= 1 &&
            (after.tiltAbsMedianDeg < before.tiltAbsMedianDeg - .02 ||
                after.rmsMedian < before.rmsMedian - .02)

    /** True when ink (< 60% of the page paper level) touches the outer 2% ring. */
    fun touchesEdge(bytes: ByteArray, width: Int, height: Int): Boolean {
        if (width < 16 || height < 16) return false
        val histogram = IntArray(256)
        for (v in bytes) histogram[v.toInt() and 255]++
        var seen = 0
        var paper = 255
        val target = max(1, (width * height * 9) / 10)
        for (level in 0..255) {
            seen += histogram[level]
            if (seen >= target) {
                paper = level
                break
            }
        }
        val threshold = (paper * .60f).toInt()
        val band = max(2, (minOf(width, height) * .02f).toInt())
        var dark = 0
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x >= band && x < width - band && y >= band && y < height - band) continue
            count++
            if ((bytes[y * width + x].toInt() and 255) < threshold) dark++
        }
        return dark >= max(3, (count * .005f).toInt())
    }
    fun json(estimate: Estimate?): String {
        if (estimate == null) return "{\"active\":false}"
        val before = estimate.before
        val after = estimate.after
        return buildString {
            append("{\"active\":true")
            append(",\"version\":\"").append(VERSION).append('"')
            append(",\"angleDeg\":").append("%.3f".format(estimate.correction.angleDeg))
            append(",\"maxShift\":").append("%.2f".format(estimate.correction.maxShift))
            append(",\"linesH\":").append(estimate.correction.linesH)
            append(",\"linesV\":").append(estimate.correction.linesV)
            append(",\"gridScale\":").append("%.3f".format(estimate.correction.scale))
            append(",\"horizontalBefore\":").append(metric(before.horizontal))
            append(",\"horizontalAfter\":").append(metric(after.horizontal))
            append(",\"verticalBefore\":").append(metric(before.vertical))
            append(",\"verticalAfter\":").append(metric(after.vertical))
            append(",\"textBefore\":").append(metric(before.text))
            append(",\"textAfter\":").append(metric(after.text))
            append("}")
        }
    }

    private fun metric(axis: LineStraightness.AxisMetric): String =
        "{\"lines\":${axis.lines},\"rms\":${
            "%.2f".format(axis.rmsMedian)
        },\"tiltDeg\":${"%.3f".format(axis.tiltMedianDeg)},\"fwhm\":${
            "%.2f".format(axis.fwhmMedian)
        }}"
}
