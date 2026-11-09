package cn.paperforge.app.imaging

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-device checks for the dependency-free straightness core with deterministic synthetic pages.
 * Real Android outputs are measured by GeometryStraightnessProbeTest on the device.
 */
class LineStraightnessLabTest {
    private data class Page(val data: ByteArray, val width: Int, val height: Int)

    @Test
    fun syntheticRuledPageIsStraightenedWithoutLosingContent() {
        val page = syntheticPage(1000, 1400, grid = false)
        val warped = warp(page, angleDeg = 1.2, bend = 6.0, phase = 0.7)
        val before = LineStraightness.measure(warped.data, warped.width, warped.height)
        val result = LineStraightness.estimate(warped.data, warped.width, warped.height)
        assertTrue("expected rule constraints", before.horizontal.lines >= 3)
        assertTrue("expected an accepted correction", result != null && result.guard.accepted)
        val after = result!!.after
        println("synthetic-ruled before=$before after=$after guard=${result.guard}")
        assertTrue(
            "horizontal RMS should improve: ${before.horizontal.rmsMedian} -> " +
                "${after.horizontal.rmsMedian}",
            after.horizontal.rmsMedian < before.horizontal.rmsMedian * 0.8,
        )
        assertTrue(
            "horizontal tilt should improve: ${before.horizontal.tiltAbsMedianDeg} -> " +
                "${after.horizontal.tiltAbsMedianDeg}",
            after.horizontal.tiltAbsMedianDeg < before.horizontal.tiltAbsMedianDeg * 0.5,
        )
        assertTrue(result.guard.inkRetention in 0.95..1.05)
        assertTrue(result.guard.spacingRatio in 0.90..1.10)
    }

    @Test
    fun syntheticTableIsStraightenedOnBothAxes() {
        val page = syntheticPage(1000, 1400, grid = true)
        val warped = warp(page, angleDeg = 1.5, bend = 8.0, phase = 0.7)
        val before = LineStraightness.measure(warped.data, warped.width, warped.height)
        val result = LineStraightness.estimate(warped.data, warped.width, warped.height)
        assertTrue("expected vertical constraints", before.vertical.lines >= 3)
        assertTrue("expected an accepted correction", result != null && result.guard.accepted)
        val after = result!!.after
        println("synthetic-grid before=$before after=$after guard=${result.guard}")
        assertTrue(after.horizontal.rmsMedian < before.horizontal.rmsMedian * 0.8)
        assertTrue(after.vertical.rmsMedian < before.vertical.rmsMedian * 0.9)
        assertTrue(after.horizontal.tiltAbsMedianDeg < before.horizontal.tiltAbsMedianDeg * 0.5)
        assertTrue(after.vertical.tiltAbsMedianDeg < before.vertical.tiltAbsMedianDeg * 0.5)
    }

    @Test
    fun outerPageRingIsNotMoved() {
        val page = syntheticPage(1000, 1400, grid = true)
        val warped = warp(page, angleDeg = 1.5, bend = 8.0, phase = 0.7)
        val result = checkNotNull(LineStraightness.estimate(warped.data, warped.width, warped.height))
        val corrected = LineStraightness.apply(
            warped.data,
            warped.width,
            warped.height,
            result.correction,
        )
        val ring = (minOf(warped.width, warped.height) * .02).toInt().coerceAtLeast(1)
        var changed = 0
        for (y in 0 until warped.height) for (x in 0 until warped.width) {
            if (x < ring || y < ring || x >= warped.width - ring || y >= warped.height - ring) {
                if (warped.data[y * warped.width + x] != corrected[y * warped.width + x]) changed++
            }
        }
        println("outer-ring changed pixels=$changed")
        assertTrue("page border must stay fixed, changed=$changed", changed == 0)
    }

    @Test
    fun alreadyStraightPageIsLeftAlone() {
        val page = syntheticPage(1000, 1400, grid = false)
        val result = LineStraightness.estimate(page.data, page.width, page.height)
        assertTrue(
            "a straight page must not be warped: ${result?.correction?.maxShift}",
            result == null,
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun syntheticPage(width: Int, height: Int, grid: Boolean): Page {
        val data = ByteArray(width * height) { 246.toByte() }
        fun dot(x: Int, y: Int, value: Int = 90) {
            if (x in 0 until width && y in 0 until height) data[y * width + x] = value.toByte()
        }
        fun horizontal(y: Int) {
            for (x in 55 until width - 45) {
                dot(x, y)
                dot(x, y + 1)
            }
        }
        fun vertical(x: Int) {
            for (y in 60 until height - 60) {
                dot(x, y)
                dot(x + 1, y)
            }
        }
        if (grid) {
            for (y in 70 until height - 50 step 86) horizontal(y)
            for (x in 60 until width - 50 step 108) vertical(x)
        } else {
            for (y in 80 until height - 60 step 42) horizontal(y)
            vertical(70)
            vertical(width - 70)
        }
        var seed = 7
        fun next(bound: Int): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return seed % bound
        }
        val step = if (grid) 86 else 42
        for (row in 110 until height - 80 step step) {
            var x = 90
            while (x < width - 80) {
                val ww = 18 + next(52)
                val hh = 8 + next(10)
                for (yy in row - hh until row - 2) for (xx in x until x + ww) dot(xx, yy, 60)
                x += ww + 8 + next(12)
            }
        }
        return Page(data, width, height)
    }

    private fun warp(page: Page, angleDeg: Double, bend: Double, phase: Double): Page {
        val width = page.width
        val height = page.height
        val theta = Math.toRadians(angleDeg)
        val ct = cos(theta)
        val st = sin(theta)
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        val out = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val px = x - cx
            val py = y - cy
            var sx = ct * px + st * py + cx
            var sy = -st * px + ct * py + cy
            sy += bend * sin(2 * Math.PI * sx / width + phase) * (0.35 + 0.65 * sy / height)
            sx += 0.008 * (sy - cy) * (sy - cy) / height
            out[y * width + x] = bilinear(page, sx, sy)
        }
        return Page(out, width, height)
    }

    private fun bilinear(page: Page, x: Double, y: Double): Byte {
        val sx = x.coerceIn(0.0, (page.width - 1).toDouble())
        val sy = y.coerceIn(0.0, (page.height - 1).toDouble())
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = (x0 + 1).coerceAtMost(page.width - 1)
        val y1 = (y0 + 1).coerceAtMost(page.height - 1)
        val fx = sx - x0
        val fy = sy - y0
        fun at(xx: Int, yy: Int) = page.data[yy * page.width + xx].toInt() and 255
        val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
        val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
        return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255).toByte()
    }
}
