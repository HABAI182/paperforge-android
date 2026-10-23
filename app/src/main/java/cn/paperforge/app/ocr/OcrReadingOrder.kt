package cn.paperforge.app.ocr

import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.OcrLine
import kotlin.math.*

/** Layout-only grouping; no language-specific character substitution. */
object OcrReadingOrder {
    fun arrange(layout: OcrLayout): OcrLayout {
        fun left(l: OcrLine) = l.corners.minOf { it.x }
        fun right(l: OcrLine) = l.corners.maxOf { it.x }
        fun top(l: OcrLine) = l.corners.minOf { it.y }
        fun bottom(l: OcrLine) = l.corners.maxOf { it.y }
        val narrow = layout.lines.filter { right(it) - left(it) < .48f }
        val leftColumn = narrow.filter { right(it) < .53f }
        val rightColumn = narrow.filter { left(it) > .47f }
        val twoColumns =
            leftColumn.size >= 2 &&
                rightColumn.size >= 2 &&
                leftColumn.maxOf { right(it) } + .025f < rightColumn.minOf { left(it) }
        val output = mutableListOf<OcrLine>()
        if (twoColumns) {
            val separators = layout.lines.filter { right(it) - left(it) >= .48f }.sortedBy(::top)
            var lower = -1f
            (separators + listOf<OcrLine?>(null)).forEach { separator ->
                val upper = separator?.let(::top) ?: 2f
                val band = narrow.filter { top(it) >= lower && top(it) < upper }
                output.addAll(band.filter { (left(it) + right(it)) / 2 < .5f }.sortedBy(::top))
                output.addAll(band.filter { (left(it) + right(it)) / 2 >= .5f }.sortedBy(::top))
                if (separator != null) {
                    output.add(separator)
                    lower = bottom(separator)
                }
            }
        } else output.addAll(layout.lines.sortedWith(compareBy(::top, ::left)))
        var paragraph = -1
        var previous: OcrLine? = null
        return layout.copy(
            lines =
                output.map { line ->
                    val old = previous
                    if (
                        old == null ||
                            line.block != old.block ||
                            top(line) - bottom(old) > max(.008f, (bottom(old) - top(old)) * .8f) ||
                            abs(left(line) - left(old)) > .07f
                    )
                        paragraph++
                    previous = line
                    line.copy(paragraph = paragraph)
                }
        )
    }
}
