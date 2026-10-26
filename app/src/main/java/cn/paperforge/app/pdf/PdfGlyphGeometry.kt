package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.hypot

/** Text rendering matrices already include the page crop origin, unlike direction-adjusted axes. */
internal object PdfGlyphGeometry {
    const val VERSION = "crop-matrix-font-metrics-1"

    fun displayed(position: TextPosition, box: PDRectangle, rotation: Int): List<PointN> {
        val matrix = position.textMatrix
        val up = hypot(matrix.shearX, matrix.scaleY).coerceAtLeast(.0001f)
        val dx = position.endX - matrix.translateX
        val dy = position.endY - matrix.translateY
        val descriptor = position.font.fontDescriptor
        val ascent = descriptor?.ascent?.div(1000f)?.takeIf { it > 0f }
        val descent = descriptor?.descent?.div(1000f)?.takeIf { it <= 0f } ?: 0f
        val hx =
            if (ascent != null) matrix.shearX * ascent else matrix.shearX / up * position.heightDir
        val hy =
            if (ascent != null) matrix.scaleY * ascent else matrix.scaleY / up * position.heightDir
        val lx = if (ascent != null) matrix.shearX * descent else 0f
        val ly = if (ascent != null) matrix.scaleY * descent else 0f
        val x = matrix.translateX
        val y = matrix.translateY
        return listOf(
                x + lx to y + ly,
                x + dx + lx to y + dy + ly,
                x + dx + hx to y + dy + hy,
                x + hx to y + hy,
            )
            .map { (px, py) ->
                val normalized = PointN(px / box.width, 1 - py / box.height)
                when ((rotation % 360 + 360) % 360) {
                    90 -> PointN(1 - normalized.y, normalized.x)
                    180 -> PointN(1 - normalized.x, 1 - normalized.y)
                    270 -> PointN(normalized.y, 1 - normalized.x)
                    else -> normalized
                }
            }
    }
}
