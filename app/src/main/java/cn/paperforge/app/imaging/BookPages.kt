package cn.paperforge.app.imaging

import cn.paperforge.app.model.CropGeometry
import cn.paperforge.app.model.Quad
import kotlin.math.hypot
import kotlin.math.max
import org.opencv.core.Mat

/**
 * The camera's 书本 mode: the user has said the photo is an open book or notebook and which page they want (左页,
 * 右页 or 两页). The spread is cut at once only where its gutter is certain: the paper's own shadow passes
 * [SpreadGutter]'s strict test (on held-out photographs of open books no strict hit was in the wrong place). Anything
 * less (a weaker shadow, the band search of [BookSpineDetector], or no evidence, when the middle is suggested) cuts
 * nothing: the capture keeps the whole spread and the editor asks the user to place the split, starting there. No
 * content is dropped by a guess. Each page ends on its own side of the gutter so neither keeps the shadow.
 *
 * A crop that is not shaped like two pages (taller than it is wide: one page, often because [NeighborPage] already
 * cut off the facing page) is left whole. Then 左页 and 右页 keep that page, and 两页 gives one page, not a copy.
 */
object BookPages {
    const val LEFT = "LEFT"
    const val RIGHT = "RIGHT"
    const val BOTH = "BOTH"

    /** How the gutter was found, for the page's metadata and the tests. */
    /** SHADOW passed the strict test and is cut without asking; the others are suggestions the user confirms. */
    enum class Source { SHADOW, WEAK_SHADOW, BAND, MIDDLE }

    /** [top]/[bottom]: the gutter's middle as fractions of the crop's width, for the editor's split. */
    data class Halves(val left: Quad, val right: Quad, val source: Source, val gutter: Float, val top: Float = gutter, val bottom: Float = gutter)

    /** [halves] on the photo in [file], decoded as the detector decodes it (orientation applied). */
    fun halves(file: java.io.File, spread: Quad): Halves? {
        val bitmap = ScanProcessor.decode(file, 2000)
        val rgba = Mat()
        try {
            org.opencv.android.Utils.bitmapToMat(bitmap, rgba)
            return halves(rgba, spread)
        } finally {
            bitmap.recycle()
            rgba.release()
        }
    }

    fun halves(photo: Mat, spread: Quad): Halves? {
        if (photo.empty() || !spread.valid()) return null
        val w = photo.cols() - 1.0
        val h = photo.rows() - 1.0
        val p = spread.points.map { org.opencv.core.Point(it.x * w, it.y * h) }
        fun len(a: org.opencv.core.Point, b: org.opencv.core.Point) = hypot(a.x - b.x, a.y - b.y)
        val across = max(len(p[0], p[1]), len(p[3], p[2]))
        val down = max(len(p[0], p[3]), len(p[1], p[2]))
        if (down < 1 || across / down < ScanConfig.float("bookSpreadAspect", .95f)) return null
        val gray = SpreadGutter.warpGray(photo, spread) ?: return null
        try {
            var source = Source.SHADOW
            var found = SpreadGutter.find(gray, SpreadGutter.Strict)
            if (!found.found) {
                found = SpreadGutter.find(gray, SpreadGutter.Asked)
                source = Source.WEAK_SHADOW
            }
            if (!found.found) {
                val luma = IntArray(gray.cols() * gray.rows())
                val bytes = ByteArray(luma.size).also { gray.get(0, 0, it) }
                for (i in luma.indices) luma[i] = bytes[i].toInt() and 255
                found = BookSpineDetector.detect(luma, gray.cols(), gray.rows())
                source = Source.BAND
            }
            val left: SpineEdge
            val right: SpineEdge
            val x: Float
            if (found.found && found.seamLeft != null && found.seamRight != null) {
                left = found.seamLeft
                right = found.seamRight
                x = found.x ?: .5f
            } else {
                // No evidence: the middle, with a hair of margin so neither page shows the other's edge.
                source = Source.MIDDLE
                left = SpineEdge(.495f, .495f)
                right = SpineEdge(.505f, .505f)
                x = .5f
            }
            val (l, r) = CropGeometry.split(spread, left.top, left.bottom, right.top, right.bottom)
            return Halves(l, r, source, x, (left.top + right.top) / 2, (left.bottom + right.bottom) / 2)
        } catch (_: IllegalArgumentException) {
            return null
        } finally {
            gray.release()
        }
    }
}
