package cn.paperforge.app.pdf

import cn.paperforge.app.model.*

object PdfCoordinates {
    fun unrotate(p: PointN, rotation: Int): PointN =
        when ((rotation % 360 + 360) % 360) {
            90 -> PointN(p.y, 1 - p.x)
            180 -> PointN(1 - p.x, 1 - p.y)
            270 -> PointN(1 - p.y, p.x)
            else -> p
        }

    fun unrotate(layout: OcrLayout, rotation: Int) =
        layout.copy(
            lines =
                layout.lines.map { line ->
                    line.copy(
                        corners = line.corners.map { unrotate(it, rotation) },
                        elements =
                            line.elements.map { e ->
                                e.copy(corners = e.corners.map { unrotate(it, rotation) })
                            },
                    )
                }
        )
}
