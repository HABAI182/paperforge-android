package cn.paperforge.app.imaging

import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The page the user aimed at, not the neighbouring page the camera also caught. In a photo of an open notebook or
 * book with the target page in full and the facing page cut off by the frame, both page networks segment the two
 * pages as one sheet: they meet at the gutter with no background between them. The owner's notebook photo of
 * 2026-11-13 was exported with a strip of the left page.
 *
 * Only when one side of [refine]'s quad lies on the photo's frame (a page cut off there) and the opposite side does
 * not. In the page warped flat, the edge of the target page is a long straight line, near-vertical across the
 * pages, between the cut side and the middle, with the brightness stepping the same way along most of its height.
 * Handwriting, print and rules give steps of both signs and short runs. That line alone could be a printed border
 * or a margin rule, so the paper's tone must also step across it: a different sheet, lit and curved differently.
 * A coloured line (a red margin) is never a page edge. With all of this, the quad is cut at the line and keeps the
 * side away from the frame; otherwise nothing changes and the editor's manual crop stays the way to choose.
 */
object NeighborPage {
    const val VERSION = "neighbor-page-1"
    private const val HEIGHT = 600.0

    fun refine(photo: Mat, quad: Quad): Quad? {
        if (ScanConfig.int("neighborPage", 1) == 0 || photo.empty() || !quad.valid()) return null
        val frame = ScanConfig.float("neighborPageFrame", .02f)
        val q = quad.points
        val left = q[0].x <= frame && q[3].x <= frame
        val right = q[1].x >= 1 - frame && q[2].x >= 1 - frame
        val top = q[0].y <= frame && q[1].y <= frame
        val bottom = q[3].y >= 1 - frame && q[2].y >= 1 - frame
        // One cut side, and the page's opposite side inside the photo. The facing page of an open book or notebook
        // lies beside the page, so only a left or right side is cut by default: across the top or bottom, the cut
        // things were long receipts, and a crease across one passed for a gutter (9 of 12 receipt crops worse).
        val across = ScanConfig.int("neighborPageAcross", 0) != 0
        val side =
            when {
                left && !right -> 0
                right && !left -> 1
                across && top && !bottom -> 2
                across && bottom && !top -> 3
                else -> return null
            }
        val owned = mutableListOf<Mat>()
        fun mat() = Mat().also(owned::add)
        try {
            val w = photo.cols()
            val h = photo.rows()
            val p = q.map { Point(it.x * (w - 1.0), it.y * (h - 1.0)) }
            // Corners in the order that puts the cut side at x = 0 of the warped page, the page running down.
            val order =
                when (side) {
                    0 -> listOf(0, 1, 2, 3)
                    1 -> listOf(1, 0, 3, 2)
                    2 -> listOf(0, 3, 2, 1)
                    else -> listOf(3, 0, 1, 2)
                }
            val c = order.map { p[it] }
            val across = max(hypot(c[1].x - c[0].x, c[1].y - c[0].y), hypot(c[2].x - c[3].x, c[2].y - c[3].y))
            val down = max(hypot(c[3].x - c[0].x, c[3].y - c[0].y), hypot(c[2].x - c[1].x, c[2].y - c[1].y))
            val hh = HEIGHT.toInt()
            val ww = (HEIGHT * across / down).roundToInt()
            if (ww < 150) return null
            val src = MatOfPoint2f(*c.toTypedArray()).also(owned::add)
            val dst =
                MatOfPoint2f(Point(0.0, 0.0), Point(ww - 1.0, 0.0), Point(ww - 1.0, hh - 1.0), Point(0.0, hh - 1.0))
                    .also(owned::add)
            val forward = Imgproc.getPerspectiveTransform(src, dst).also(owned::add)
            val colour = mat()
            Imgproc.warpPerspective(photo, colour, forward, Size(ww.toDouble(), hh.toDouble()), Imgproc.INTER_AREA)
            val gray = mat()
            Imgproc.cvtColor(colour, gray, if (photo.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), 1.2)
            val gx = mat()
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
            val g = FloatArray(ww * hh).also { gx.get(0, 0, it) }
            val luma = ByteArray(ww * hh).also { gray.get(0, 0, it) }
            val step = ScanConfig.float("neighborPageStep", 18f)
            val need = ScanConfig.float("neighborPageSupport", .6f)
            val y0 = (.03 * hh).toInt()
            val y1 = (.97 * hh).toInt()
            val rows = y1 - y0
            // Signed step per pixel, so a line is scored by rows stepping one way along it.
            val sign = ByteArray(ww * hh) { if (g[it] > step) 1 else if (g[it] < -step) -1 else 0 }
            var best = 0.0
            var bestA = 0
            var bestD = 0
            val slant = (ScanConfig.float("neighborPageSlant", .12f) * ww).toInt()
            for (xa in (.04 * ww).toInt() until (ScanConfig.float("neighborPageReach", .48f) * ww).toInt()) {
                var d = -slant
                while (d <= slant) {
                    var up = 0
                    var downs = 0
                    for (y in y0 until y1) {
                        val x = xa + (d * (y - y0).toDouble() / (rows - 1)).roundToInt()
                        if (x < 1 || x >= ww - 1) continue
                        // The step can sit a pixel off the line.
                        val i = y * ww + x
                        val s = if (sign[i] != 0.toByte()) sign[i] else if (sign[i - 1] != 0.toByte()) sign[i - 1] else sign[i + 1]
                        if (s > 0) up++ else if (s < 0) downs++
                    }
                    val share = max(up, downs).toDouble() / rows
                    if (share > best) {
                        best = share
                        bestA = xa
                        bestD = d
                    }
                    d += 2
                }
            }
            if (best < need) return null
            // A different sheet: the paper on the two sides differs in tone. Medians of strips beside the line.
            fun lineX(y: Int) = bestA + (bestD * (y - y0).toDouble() / (rows - 1)).roundToInt()
            val width = max(8, (.05 * ww).toInt())
            val outside = ArrayList<Int>()
            val inside = ArrayList<Int>()
            for (y in y0 until y1 step 2) {
                val x = lineX(y)
                for (dx in 4 until 4 + width) {
                    if (x - dx >= 0) outside += luma[y * ww + x - dx].toInt() and 255
                    if (x + dx < ww) inside += luma[y * ww + x + dx].toInt() and 255
                }
            }
            if (outside.size < 50 || inside.size < 50) return null
            // The paper is the bright part of each strip; ink is a minority.
            fun paper(values: ArrayList<Int>) = values.sorted()[(values.size * .75).toInt()]
            val toneStep = abs(paper(outside) - paper(inside))
            if (toneStep < ScanConfig.int("neighborPageTone", 8)) return null
            // A coloured line is a margin rule or a printed border, not the edge of a sheet.
            val hsv = mat()
            if (photo.channels() == 4) Imgproc.cvtColor(colour, hsv, Imgproc.COLOR_RGBA2RGB) else colour.copyTo(hsv)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            val hsvBytes = ByteArray(ww * hh * 3).also { hsv.get(0, 0, it) }
            var saturation = 0L
            var count = 0
            for (y in y0 until y1 step 3) {
                val x = lineX(y)
                if (x !in 0 until ww) continue
                saturation += hsvBytes[(y * ww + x) * 3 + 1].toInt() and 255
                count++
            }
            if (count == 0 || saturation / count > ScanConfig.int("neighborPageSaturation", 60)) return null
            // Cut at the line, a little past it so the target page keeps its own edge and nothing of the gutter.
            val margin = 2.0
            val topX = lineX(0).toDouble() + margin
            val bottomX = lineX(hh - 1).toDouble() + margin
            if (topX >= ww * .55 || bottomX >= ww * .55) return null
            val inverse = mat()
            Core.invert(forward, inverse)
            val cut =
                MatOfPoint2f(Point(topX, 0.0), Point(ww - 1.0, 0.0), Point(ww - 1.0, hh - 1.0), Point(bottomX, hh - 1.0))
                    .also(owned::add)
            val mapped = MatOfPoint2f().also(owned::add)
            Core.perspectiveTransform(cut, mapped, inverse)
            val out =
                Quad.ordered(
                    mapped.toArray().map {
                        PointN((it.x / (w - 1.0)).toFloat().coerceIn(0f, 1f), (it.y / (h - 1.0)).toFloat().coerceIn(0f, 1f))
                    }
                )
            return out.takeIf { it.valid() && it.area >= .45f * quad.area && it.area < quad.area }
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
