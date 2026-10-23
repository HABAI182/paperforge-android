package cn.paperforge.app.model

import kotlin.math.*

/** Clockwise image coordinates, white expanded canvas. Same mapping for pixels and OCR boxes. */
class DeskewGeometry(val width: Int, val height: Int, val degrees: Float) {
    val cosine = cos(degrees * PI / 180).toFloat()
    val sine = sin(degrees * PI / 180).toFloat()
    val outputWidth = ceil(abs(width * cosine) + abs(height * sine)).toInt()
    val outputHeight = ceil(abs(width * sine) + abs(height * cosine)).toInt()
    val translateX = outputWidth / 2f - width / 2f * cosine + height / 2f * sine
    val translateY = outputHeight / 2f - width / 2f * sine - height / 2f * cosine

    fun forward(p: PointN) =
        PointN(
            (p.x * width * cosine - p.y * height * sine + translateX) / outputWidth,
            (p.x * width * sine + p.y * height * cosine + translateY) / outputHeight,
        )

    fun inverse(p: PointN): PointN {
        val x = p.x * outputWidth - translateX
        val y = p.y * outputHeight - translateY
        return PointN((x * cosine + y * sine) / width, (-x * sine + y * cosine) / height)
    }

    fun map(layout: OcrLayout, forward: Boolean): OcrLayout {
        val transform: (PointN) -> PointN = if (forward) ::forward else ::inverse
        return layout.copy(
            width = if (forward) outputWidth else width,
            height = if (forward) outputHeight else height,
            lines =
                layout.lines.map { l ->
                    l.copy(
                        corners = l.corners.map(transform),
                        elements =
                            l.elements.map { e -> e.copy(corners = e.corners.map(transform)) },
                    )
                },
        )
    }
}
