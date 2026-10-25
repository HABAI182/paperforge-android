package cn.paperforge.app.model

import kotlin.math.*

data class OcrRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    fun contains(p: PointN) = p.x in left..right && p.y in top..bottom

    fun valid() =
        listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
            width >= .01f &&
            height >= .01f

    fun map(points: List<PointN>) = points.map { PointN(left + it.x * width, top + it.y * height) }

    fun overlaps(points: List<PointN>): Float {
        val x0 = points.minOf { it.x }
        val x1 = points.maxOf { it.x }
        val y0 = points.minOf { it.y }
        val y1 = points.maxOf { it.y }
        return max(0f, min(right, x1) - max(left, x0)) * max(0f, min(bottom, y1) - max(top, y0)) /
            max(.000001f, (x1 - x0) * (y1 - y0))
    }

    fun mapped(result: OcrLayout, width: Int, height: Int) =
        result
            .mapped(width, height) { PointN(left + it.x * this.width, top + it.y * this.height) }
            .copy(orientationCorrection = 0, deskewDegrees = 0f, rawRecognition = null)

    companion object {
        fun between(first: PointN, last: PointN) =
            OcrRegion(
                min(first.x, last.x).coerceIn(0f, 1f),
                min(first.y, last.y).coerceIn(0f, 1f),
                max(first.x, last.x).coerceIn(0f, 1f),
                max(first.y, last.y).coerceIn(0f, 1f),
            )
    }
}

object OcrRegionMerge {
    data class Result(val layout: OcrLayout, val canMerge: Boolean)

    private fun center(points: List<PointN>) =
        PointN(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    fun wordSpan(line: OcrLine, index: Int): IntRange? {
        var cursor = 0
        line.elements.forEachIndexed { i, element ->
            val start = line.text.indexOf(element.text, cursor)
            if (start < 0) return null
            if (i == index) return start until start + element.text.length
            cursor = start + element.text.length
        }
        return null
    }

    fun apply(base: OcrLayout, region: OcrRegion, local: OcrLayout): Result {
        if (local.lines.isEmpty()) return Result(base, false)
        val candidates = region.mapped(local, base.width, base.height).lines
        val pending = candidates.toMutableList()
        var ambiguous = false
        val retained =
            base.lines.mapNotNull { line ->
                val overlap = region.overlaps(line.corners)
                if (overlap < .01f) return@mapNotNull line
                val allWordsCovered =
                    line.elements.isNotEmpty() &&
                        line.elements.all { region.overlaps(it.corners) > .99f }
                if (overlap >= .999f || allWordsCovered) return@mapNotNull null
                val partialWord = line.elements.any { region.overlaps(it.corners) in .05f..0.95f }
                if (partialWord) {
                    ambiguous = true
                    return@mapNotNull line
                }
                val affected =
                    line.elements.indices.filter {
                        region.overlaps(line.elements[it].corners) > .95f
                    }
                if (affected.isEmpty()) {
                    ambiguous = true
                    return@mapNotNull line
                }
                val first = wordSpan(line, affected.first())
                val last = wordSpan(line, affected.last())
                if (
                    first == null ||
                        last == null ||
                        affected != (affected.first()..affected.last()).toList()
                ) {
                    ambiguous = true
                    return@mapNotNull line
                }
                val rowCandidates =
                    pending.filter { candidate ->
                        val y = center(candidate.corners).y
                        y >= line.corners.minOf { it.y } - .01f &&
                            y <= line.corners.maxOf { it.y } + .01f
                    }
                if (rowCandidates.isEmpty()) {
                    ambiguous = true
                    return@mapNotNull line
                }
                pending.removeAll(rowCandidates.toSet())
                val replacement =
                    rowCandidates
                        .sortedBy { it.corners.minOf { p -> p.x } }
                        .joinToString(" ") { it.text }
                val text = line.text.replaceRange(first.first, last.last + 1, replacement)
                line.copy(
                    text = text,
                    elements =
                        line.elements.take(affected.first()) +
                            rowCandidates.flatMap { candidate ->
                                candidate.elements.ifEmpty {
                                    listOf(
                                        OcrElement(
                                            candidate.text,
                                            candidate.corners,
                                            candidate.confidence,
                                            candidate.angle,
                                        )
                                    )
                                }
                            } +
                            line.elements.drop(affected.last() + 1),
                    hypotheses =
                        line.hypotheses.ifEmpty {
                            listOf(OcrHypothesis(line.text, line.confidence, line.variant))
                        } + OcrHypothesis(text, null, "region"),
                    variant = "region",
                )
            }
        if (ambiguous) return Result(base, false)
        return Result(
            cn.paperforge.app.ocr.OcrReadingOrder.arrange(
                base.copy(lines = retained + pending, tables = emptyList())
            ),
            true,
        )
    }
}
