package cn.paperforge.app.ocr

import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.OcrLine
import kotlin.math.*

/** Layout-only grouping; no language-specific character substitution. */
object OcrReadingOrder {
    const val VERSION = "columns-vertical-baselines-2"

    private fun cjk(text: String): Boolean {
        val letters = text.codePoints().filter { Character.isLetter(it) }.toArray()
        return letters.isNotEmpty() &&
            letters.count {
                Character.UnicodeScript.of(it) in
                    setOf(
                        Character.UnicodeScript.HAN,
                        Character.UnicodeScript.HIRAGANA,
                        Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL,
                    )
            } >= letters.size * .5
    }

    private fun tall(line: OcrLine, layout: OcrLayout): Boolean {
        if (line.corners.size != 4) return false
        val width = (line.corners.maxOf { it.x } - line.corners.minOf { it.x }) * layout.width
        val height = (line.corners.maxOf { it.y } - line.corners.minOf { it.y }) * layout.height
        return height > width * 1.8f
    }

    fun vertical(layout: OcrLayout): Boolean {
        val positioned = layout.lines.filter { it.corners.size == 4 }
        return positioned.isNotEmpty() &&
            positioned.count { tall(it, layout) && cjk(it.text) } >= positioned.size * .6
    }

    /** Reorder the actual quad, rather than inventing character or word boxes. */
    private fun verticalQuad(
        points: List<cn.paperforge.app.model.PointN>,
        layout: OcrLayout,
    ): List<cn.paperforge.app.model.PointN> {
        if (points.size != 4) return points
        fun distance(a: Int, b: Int) =
            hypot(
                (points[a].x - points[b].x) * layout.width,
                (points[a].y - points[b].y) * layout.height,
            )
        return if (distance(0, 1) < distance(0, 3) * .75f)
            listOf(points[1], points[2], points[3], points[0])
        else points
    }

    fun arrange(layout: OcrLayout, order: OcrOrder = OcrOrder.AUTO): OcrLayout {
        fun left(l: OcrLine) = l.corners.minOf { it.x }
        fun right(l: OcrLine) = l.corners.maxOf { it.x }
        fun top(l: OcrLine) = l.corners.minOf { it.y }
        fun bottom(l: OcrLine) = l.corners.maxOf { it.y }
        val verticalMode =
            order in setOf(OcrOrder.VERTICAL_RTL, OcrOrder.VERTICAL_LTR) ||
                order == OcrOrder.AUTO && vertical(layout)
        val positioned =
            layout.lines
                .filter { it.corners.size == 4 }
                .map { line ->
                    if (tall(line, layout) && cjk(line.text)) {
                        val points = verticalQuad(line.corners, layout)
                        line.copy(
                            corners = points,
                            angle =
                                (atan2(
                                        (points[1].y - points[0].y) * layout.height,
                                        (points[1].x - points[0].x) * layout.width,
                                    ) * 180 / PI)
                                    .toFloat(),
                            elements =
                                line.elements.map {
                                    it.copy(corners = verticalQuad(it.corners, layout))
                                },
                        )
                    } else line
                }
        val unpositioned = layout.lines.filter { it.corners.size != 4 }
        val narrow = positioned.filter { right(it) - left(it) < .48f }
        fun groups(lines: List<OcrLine>): List<List<OcrLine>> {
            if (lines.isEmpty()) return emptyList()
            val widths = lines.map { right(it) - left(it) }.sorted()
            val tolerance = max(.015f, widths[widths.size / 2] * .7f)
            val clusters = mutableListOf<MutableList<OcrLine>>()
            lines
                .sortedBy { (left(it) + right(it)) / 2 }
                .forEach { line ->
                    val group = clusters.lastOrNull()
                    if (
                        group == null ||
                            abs(
                                (left(line) + right(line)) / 2 -
                                    group.map { (left(it) + right(it)) / 2 }.average()
                            ) > tolerance
                    )
                        clusters += mutableListOf(line)
                    else group += line
                }
            return clusters
        }
        val columns = groups(narrow)
        val clearColumns =
            columns.size >= 2 &&
                columns.all { it.size >= 2 } &&
                columns.zipWithNext().all { (a, b) -> a.maxOf(::right) + .025f < b.minOf(::left) }
        val columnMode =
            verticalMode ||
                order in setOf(OcrOrder.COLUMNS_LTR, OcrOrder.COLUMNS_RTL) ||
                order == OcrOrder.AUTO && clearColumns
        val rtl =
            order in setOf(OcrOrder.COLUMNS_RTL, OcrOrder.VERTICAL_RTL) ||
                verticalMode && order == OcrOrder.AUTO
        val output = mutableListOf<OcrLine>()
        if (columnMode) {
            val verticalLines = positioned.filter { tall(it, layout) }
            val separators =
                positioned
                    .filter { line ->
                        right(line) - left(line) >= .48f ||
                            verticalMode &&
                                !tall(line, layout) &&
                                verticalLines.isNotEmpty() &&
                                (bottom(line) < verticalLines.minOf(::top) ||
                                    top(line) > verticalLines.maxOf(::bottom))
                    }
                    .sortedBy(::top)
            val body = positioned.filter { it !in separators }
            var lower = -1f
            (separators + listOf<OcrLine?>(null)).forEach { separator ->
                val upper = separator?.let(::top) ?: 2f
                val band = body.filter { top(it) >= lower && top(it) < upper }
                val groups = groups(band).let { if (rtl) it.reversed() else it }
                groups.forEach { group -> output.addAll(group.sortedBy(::top)) }
                if (separator != null) {
                    output.add(separator)
                    lower = top(separator)
                }
            }
        } else output.addAll(positioned.sortedWith(compareBy(::top, ::left)))
        output.addAll(unpositioned)
        var paragraph = -1
        var previous: OcrLine? = null
        return layout.copy(
            lines =
                output.map { line ->
                    val old = previous
                    if (
                        old == null ||
                            line.corners.size != 4 ||
                            old.corners.size != 4 ||
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
