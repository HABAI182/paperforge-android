package cn.paperforge.app.imaging

import cn.paperforge.app.model.Quad
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * Content-aware crop safety (v200 20016 feedback: "the left column of formulas was erased").
 *
 * [cn.paperforge.app.model.CropSafety] expands a crop by a fixed ring so a curled receipt edge
 * does not shave the last glyph column. It is blind to whether anything is actually there, and the
 * shipped configuration sets `cropSafetyMargin=0`.
 *
 * This guard looks at the pixels just *inside* each side of the quad. When real ink touches the
 * crop frame, that side is pushed **outward** (towards the rest of the photograph), never inward:
 * a page that is already tight only gains paper/exterior pixels, which the paper-boundary pass can
 * classify later, while a page whose crop clips a stroke stops clipping it. When no ink touches a
 * side, the quad is returned unchanged, so the existing geometry is preserved.
 *
 * The caller must supply a bitmap that still contains a small ring outside the quad (PageDecoder
 * decodes such a ring). Expansion is clamped to the bitmap.
 */
object ContentCrop {
    const val VERSION = "content-crop-1"

    /** Dark-luma threshold used only to decide whether content touches the frame. */
    private const val Ink = 118

    internal fun enabled() = ScanConfig.int("contentCropProtect", 1) != 0

    /** Returns a quad that keeps edge ink inside the frame, or [quad] when nothing touches. */
    fun protect(source: Mat, quad: Quad): Quad {
        if (!enabled() || source.empty() || !quad.valid()) return quad
        val width = source.cols()
        val height = source.rows()
        if (width < 64 || height < 64) return quad
        val short = min(width, height)
        val extra = (short * ScanConfig.float("contentCropExtra", .012f)).roundToInt().coerceAtLeast(2)
        val band = max(2, (short * .004f).roundToInt())
        val gray = Mat()
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
            }
            val bytes = ByteArray(width * height).also { gray.get(0, 0, it) }
            fun lum(x: Int, y: Int): Int =
                bytes[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 255

            val points =
                quad.points.map {
                    Point(
                        (it.x * (width - 1)).toDouble(),
                        (it.y * (height - 1)).toDouble(),
                    )
                }
            val sides =
                arrayOf(
                    // top, right, bottom, left: the two endpoints of each side
                    points[0] to points[1],
                    points[1] to points[2],
                    points[2] to points[3],
                    points[3] to points[0],
                )
            val touched = BooleanArray(4)
            for (side in 0..3) {
                val (a, b) = sides[side]
                val steps = max(24, (kotlin.math.hypot(b.x - a.x, b.y - a.y) / 12).toInt())
                var dark = 0
                var samples = 0
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val px = a.x + (b.x - a.x) * t
                    val py = a.y + (b.y - a.y) * t
                    for (d in 1..band step 2) {
                        val x =
                            when (side) {
                                3 -> (px + d).toInt()
                                1 -> (px - d).toInt()
                                else -> px.toInt()
                            }
                        val y =
                            when (side) {
                                0 -> (py + d).toInt()
                                2 -> (py - d).toInt()
                                else -> py.toInt()
                            }
                        samples++
                        if (lum(x, y) < Ink) dark++
                    }
                }
                // A real stroke/rule covers several consecutive samples; noise does not.
                touched[side] = dark >= max(3, (samples * .06).toInt())
            }
            if (touched.none { it }) return quad
            val result = points.map { Point(it.x, it.y) }.toMutableList()
            if (touched[0]) {
                result[0].y -= extra
                result[1].y -= extra
            }
            if (touched[1]) {
                result[1].x += extra
                result[2].x += extra
            }
            if (touched[2]) {
                result[2].y += extra
                result[3].y += extra
            }
            if (touched[3]) {
                result[3].x -= extra
                result[0].x -= extra
            }
            val expanded =
                Quad(
                    result.map {
                        cn.paperforge.app.model.PointN(
                            (it.x / (width - 1)).coerceIn(0.0, 1.0).toFloat(),
                            (it.y / (height - 1)).coerceIn(0.0, 1.0).toFloat(),
                        )
                    }
                )
            return if (expanded.valid() && expanded.area > quad.area) expanded else quad
        } finally {
            gray.release()
        }
    }
}
