package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max

/** Offsets refer to the exact line string. A ligature or OCR word is an indivisible unit. */
data class ReaderTextUnit(
    val start: Int,
    val end: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ReaderSelectionUnit(val line: Int, val span: ReaderTextUnit)

class ReaderSelectionPage(val page: Int, val lines: List<ReaderTextBlock>, val order: Int = 0) {
    val ordered = ReaderDocument.order(lines, order)
    val units =
        ordered.flatMapIndexed { index, block ->
            block.units
                .ifEmpty {
                    listOf(
                        ReaderTextUnit(
                            0,
                            block.text.length,
                            block.left,
                            block.top,
                            block.right,
                            block.bottom,
                        )
                    )
                }
                .filter { it.start >= 0 && it.end > it.start && it.end <= block.text.length }
                .map { ReaderSelectionUnit(index, it) }
        }

    fun nearest(point: PointN): Int? =
        units.indices.minByOrNull { index ->
            val span = units[index].span
            val dx = max(max(span.left - point.x, point.x - span.right), 0f)
            val dy = max(max(span.top - point.y, point.y - span.bottom), 0f)
            dx * dx + dy * dy * 4
        }

    fun select(point: PointN): ReaderTextSelection? {
        val index = nearest(point) ?: return null
        val unit = units[index]
        val span = unit.span
        // A press in empty margins should still open the text/OCR panel.
        if (
            point.x !in (span.left - .025f)..(span.right + .025f) ||
                point.y !in (span.top - .025f)..(span.bottom + .025f)
        )
            return null
        val line = ordered[unit.line]
        val words = BreakIterator.getWordInstance(Locale.getDefault()).apply { setText(line.text) }
        val start =
            words.preceding((span.start + 1).coerceAtMost(line.text.length)).coerceAtLeast(0)
        val end = words.following(span.start).let { if (it < 0) line.text.length else it }
        val same =
            units.indices.filter {
                units[it].line == unit.line &&
                    units[it].span.start < end &&
                    units[it].span.end > start
            }
        return ReaderTextSelection(this, same.firstOrNull() ?: index, same.lastOrNull() ?: index)
    }
}

data class ReaderTextSelection(val model: ReaderSelectionPage, val anchor: Int, val focus: Int) {
    val first
        get() = minOf(anchor, focus)

    val last
        get() = maxOf(anchor, focus)

    val blocks: List<ReaderTextBlock>
        get() =
            model.units
                .subList(first, last + 1)
                .groupBy { it.line }
                .map { (lineIndex, selected) ->
                    val line = model.ordered[lineIndex]
                    val spans = selected.map { it.span }
                    line.copy(
                        text =
                            line.text
                                .substring(spans.minOf { it.start }, spans.maxOf { it.end })
                                .trim(),
                        left = spans.minOf { it.left },
                        top = spans.minOf { it.top },
                        right = spans.maxOf { it.right },
                        bottom = spans.maxOf { it.bottom },
                        units = emptyList(),
                    )
                }
                .filter { it.text.isNotEmpty() }

    val text
        get() = blocks.joinToString("\n") { it.text }

    val startPoint: PointN
        get() = model.units[first].span.let { PointN(it.left, it.bottom) }

    val endPoint: PointN
        get() = model.units[last].span.let { PointN(it.right, it.bottom) }

    fun move(start: Boolean, point: PointN): ReaderTextSelection {
        val index = model.nearest(point) ?: return this
        // Keep physical handles on their own sides instead of swapping them mid-drag.
        return if (start) copy(anchor = minOf(index, last), focus = last)
        else copy(anchor = first, focus = maxOf(first, index))
    }

    fun line(): ReaderTextSelection {
        val selected = model.units[first].line
        val same = model.units.indices.filter { model.units[it].line == selected }
        return copy(anchor = same.first(), focus = same.last())
    }

    fun all() = copy(anchor = 0, focus = model.units.lastIndex)

    fun reordered(order: Int): ReaderTextSelection {
        val startUnit = model.units[first]
        val endUnit = model.units[last]
        val startLine = model.ordered[startUnit.line]
        val endLine = model.ordered[endUnit.line]
        val next = ReaderSelectionPage(model.page, model.lines, order)
        fun locate(line: ReaderTextBlock, span: ReaderTextUnit) =
            next.units
                .indexOfFirst { next.ordered[it.line] == line && it.span == span }
                .coerceAtLeast(0)
        return ReaderTextSelection(
            next,
            locate(startLine, startUnit.span),
            locate(endLine, endUnit.span),
        )
    }
}
