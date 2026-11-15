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
    const val VERSION = "neighbor-page-2"
    private const val HEIGHT = 600.0

    /** Detection note: the page's own curved edge (photo-normalised "x,y;x,y;..."), see [lastSeam]. */
    const val SEAM_NOTE = "pageSeam"

    /**
     * The curved edge found by the last [refine] on this thread that cut along a seam, photo-normalised, or null. The
     * cut itself is a straight line on the neighbour's side of the seam (nothing of this page is lost), which leaves
     * a sliver of the facing page where the edge bows; the renderer fills that sliver with paper (ScanProcessor).
     */
    val lastSeam: ThreadLocal<String?> = ThreadLocal()

    /**
     * A cut page is still a page: convex, every corner between 50 and 130 degrees. A line found at a steep slant
     * once turned a spread into a triangle; then the crop is left as it was.
     */
    internal fun plausible(quad: Quad): Boolean {
        val p = quad.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val ux = a.x - b.x
            val uy = a.y - b.y
            val vx = c.x - b.x
            val vy = c.y - b.y
            val cross = ux * vy - uy * vx
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0 || (sign != 0 && s != sign)) return false
            sign = s
            val cos = (ux * vx + uy * vy) / max(1e-6, hypot(ux.toDouble(), uy.toDouble()) * hypot(vx.toDouble(), vy.toDouble()))
            val angle = Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1.0, 1.0)))
            if (angle < 50 || angle > 130) return false
        }
        return true
    }

    /**
     * The top-to-bottom path, moving at most one pixel sideways per row between [from] and [to], with the most rows
     * stepping the same way on it (within a pixel), and that share of the rows; null if there is no room.
     */
    internal fun seam(sign: ByteArray, w: Int, y0: Int, y1: Int, from: Int, to: Int): Triple<IntArray, Double, Int>? {
        val cols = to - from
        val rows = y1 - y0
        if (cols < 3 || rows < 3) return null
        var bestPath: IntArray? = null
        var bestTotal = -1f
        var bestPolarity = 1
        for (polarity in intArrayOf(1, -1)) {
            val hit = FloatArray(rows * cols)
            for (r in 0 until rows) {
                val base = (y0 + r) * w
                for (c in 0 until cols) {
                    val x = from + c
                    val near =
                        (x > 0 && sign[base + x - 1].toInt() == polarity) || sign[base + x].toInt() == polarity ||
                            (x < w - 1 && sign[base + x + 1].toInt() == polarity)
                    if (near) hit[r * cols + c] = 1f
                }
            }
            val back = ByteArray(rows * cols)
            var acc = FloatArray(cols) { hit[it] }
            for (r in 1 until rows) {
                val next = FloatArray(cols)
                for (c in 0 until cols) {
                    var choice = 0
                    var value = acc[c]
                    if (c > 0 && acc[c - 1] > value) {
                        value = acc[c - 1]
                        choice = -1
                    }
                    if (c < cols - 1 && acc[c + 1] > value) {
                        value = acc[c + 1]
                        choice = 1
                    }
                    next[c] = value + hit[r * cols + c]
                    back[r * cols + c] = choice.toByte()
                }
                acc = next
            }
            var c = 0
            for (k in 1 until cols) if (acc[k] > acc[c]) c = k
            if (acc[c] > bestTotal) {
                bestTotal = acc[c]
                bestPolarity = polarity
                val path = IntArray(rows)
                path[rows - 1] = c
                for (r in rows - 1 downTo 1) {
                    c += back[r * cols + c]
                    path[r - 1] = c
                }
                bestPath = IntArray(rows) { path[it] + from }
            }
        }
        return bestPath?.let { Triple(it, bestTotal.toDouble() / rows, bestPolarity) }
    }

    fun refine(photo: Mat, quad: Quad): Quad? {
        lastSeam.set(null)
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
            var polarity = 1
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
                        polarity = if (up >= downs) 1 else -1
                    }
                    d += 2
                }
            }
            // The edge of a curved page bows: then no straight line has the support, but a seam that follows it does.
            var seam: IntArray? = null
            if (best < need) {
                val found = seam(sign, ww, y0, y1, (.04 * ww).toInt(), (ScanConfig.float("neighborPageReach", .48f) * ww).toInt())
                if (found == null || found.second < ScanConfig.float("neighborPageSeamSupport", .55f)) return null
                seam = found.first
                polarity = found.third
                // The cut: the straight line through the seam, moved onto the neighbour's side of it at every height,
                // so a bowed edge leaves a sliver of the facing page rather than taking any of this page.
                var sy = 0.0
                var sx = 0.0
                var syy = 0.0
                var sxy = 0.0
                for (r in seam.indices) {
                    val y = (y0 + r).toDouble()
                    sy += y
                    sx += seam[r]
                    syy += y * y
                    sxy += y * seam[r]
                }
                val n = seam.size.toDouble()
                val slope = (n * sxy - sy * sx) / max(1e-9, n * syy - sy * sy)
                val offset = (sx - slope * sy) / n
                var shift = 0.0
                for (r in seam.indices) shift = max(shift, offset + slope * (y0 + r) - seam[r])
                bestA = (offset + slope * y0 - shift).roundToInt()
                bestD = (slope * (rows - 1)).roundToInt()
            }
            // A different sheet: the paper on the two sides differs in tone. Medians of strips beside the line.
            fun lineX(y: Int) = bestA + (bestD * (y - y0).toDouble() / (rows - 1)).roundToInt()
            // Where the edge itself is: the seam when there is one, else the line.
            fun edgeX(y: Int) = seam?.let { it[(y - y0).coerceIn(0, it.size - 1)] } ?: lineX(y)
            val width = max(8, (.05 * ww).toInt())
            val outside = ArrayList<Int>()
            val inside = ArrayList<Int>()
            for (y in y0 until y1 step 2) {
                val x = edgeX(y)
                for (dx in 4 until 4 + width) {
                    if (x - dx >= 0) outside += luma[y * ww + x - dx].toInt() and 255
                    if (x + dx < ww) inside += luma[y * ww + x + dx].toInt() and 255
                }
            }
            if (outside.size < 50 || inside.size < 50) return null
            // The paper is the bright part of each strip; ink is a minority.
            fun paper(values: ArrayList<Int>) = values.sorted()[(values.size * .75).toInt()]
            val toneStep = abs(paper(outside) - paper(inside))
            // Or a single step: where one page lies on another its edge steps once, and the shadow beside it is soft.
            // A printed line (a grid, a margin rule, a border) is a dark stripe: the opposite step follows within a few
            // pixels on most rows. Measured: page edges 0.23-0.33 of rows, printed grid lines 0.86-0.93.
            var paired = 0
            var sampled = 0
            for (y in y0 until y1) {
                val x = edgeX(y)
                if (x < 7 || x >= ww - 7) continue
                sampled++
                val base = y * ww
                var found = false
                for (dx in 2..6) {
                    if (sign[base + x - dx].toInt() == -polarity || sign[base + x + dx].toInt() == -polarity) {
                        found = true
                        break
                    }
                }
                if (found) paired++
            }
            val singleStep = sampled > 0 && paired.toDouble() / sampled <= ScanConfig.float("neighborPagePaired", .40f)
            // A straight cut keeps its tone test. A seam can wind along the edges of printed letters (it cut 4 of 198
            // receipts through their text), so a seam must be a single step, whatever the tone.
            if (seam != null) {
                if (!singleStep) return null
                // Writing never runs across a page's own edge; it runs across a crease. Ink within 6 px of the seam:
                // page edges 0.06-0.09 of rows, the crease of a receipt 0.25.
                val paperLevel = mat()
                Imgproc.dilate(gray, paperLevel, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(31.0, 1.0)))
                val paperBytes = ByteArray(ww * hh).also { paperLevel.get(0, 0, it) }
                var inked = 0
                var looked = 0
                for (y in y0 until y1) {
                    val x = edgeX(y)
                    looked++
                    for (dx in -6..6) {
                        val xx = x + dx
                        if (xx !in 0 until ww) continue
                        val i = y * ww + xx
                        if ((paperBytes[i].toInt() and 255) - (luma[i].toInt() and 255) > 45) {
                            inked++
                            break
                        }
                    }
                }
                if (looked == 0 || inked.toDouble() / looked > ScanConfig.float("neighborPageSeamInk", .15f)) return null
            } else if (toneStep < ScanConfig.int("neighborPageTone", 8)) return null
            // A coloured line is a margin rule or a printed border, not the edge of a sheet.
            val hsv = mat()
            if (photo.channels() == 4) Imgproc.cvtColor(colour, hsv, Imgproc.COLOR_RGBA2RGB) else colour.copyTo(hsv)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
            val hsvBytes = ByteArray(ww * hh * 3).also { hsv.get(0, 0, it) }
            var saturation = 0L
            var count = 0
            for (y in y0 until y1 step 3) {
                val x = edgeX(y)
                if (x !in 0 until ww) continue
                saturation += hsvBytes[(y * ww + x) * 3 + 1].toInt() and 255
                count++
            }
            if (count == 0 || saturation / count > ScanConfig.int("neighborPageSaturation", 60)) return null
            // Cut at the line, a little past it so the target page keeps its own edge and nothing of the gutter.
            val margin = if (seam != null) 0.0 else 2.0
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
            val accepted = out.takeIf { it.valid() && it.area >= .45f * quad.area && it.area < quad.area && plausible(it) }
            if (accepted != null && seam != null && ScanConfig.int("neighborPageSeamFill", 1) != 0) {
                // The seam, every 10 rows, back in the photo.
                val pts = (seam.indices step 10).map { r -> Point(seam[r].toDouble(), (y0 + r).toDouble()) } +
                    Point(seam.last().toDouble(), (y0 + seam.size - 1).toDouble())
                val warpedSeam = MatOfPoint2f(*pts.toTypedArray()).also(owned::add)
                val photoSeam = MatOfPoint2f().also(owned::add)
                Core.perspectiveTransform(warpedSeam, photoSeam, inverse)
                lastSeam.set(
                    photoSeam.toArray().joinToString(";") {
                        "%.5f,%.5f".format(java.util.Locale.ROOT, it.x / (w - 1.0), it.y / (h - 1.0))
                    }
                )
            }
            return accepted
        } finally {
            owned.forEach(Mat::release)
        }
    }
}
