package cn.paperforge.app.ocr

import cn.paperforge.app.model.*
import kotlin.math.*

/** Only observed model hypotheses may enter a result. */
internal object OcrLineFusion {
    const val VERSION = "observed-line-union-1"

    fun merge(base: OcrLayout, alternative: OcrLayout, voting: Boolean): OcrLayout {
        if (base.lines.isEmpty()) return alternative.copy(width = base.width, height = base.height)
        fun bounds(p: List<PointN>) =
            floatArrayOf(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
        fun overlap(a: OcrLine, b: OcrLine): Float {
            val x = bounds(a.corners)
            val y = bounds(b.corners)
            val intersection =
                max(0f, min(x[2], y[2]) - max(x[0], y[0])) *
                    max(0f, min(x[3], y[3]) - max(x[1], y[1]))
            return intersection /
                max(
                    .000001f,
                    (x[2] - x[0]) * (x[3] - x[1]) + (y[2] - y[0]) * (y[3] - y[1]) - intersection,
                )
        }
        val fused =
            base.copy(
                lines =
                    base.lines.map { line ->
                        val candidate = alternative.lines.maxByOrNull { overlap(line, it) }
                        if (
                            candidate == null ||
                                overlap(line, candidate) <= .55f ||
                                (line.text.count { it.code in 0x3400..0x9fff } >= 2 &&
                                    candidate.text.count { it.code in 0x3400..0x9fff } <
                                        line.text.count { it.code in 0x3400..0x9fff } * .7f)
                        )
                            line
                        else {
                            val hypotheses =
                                (line.hypotheses.ifEmpty {
                                        listOf(
                                            OcrHypothesis(line.text, line.confidence, line.variant)
                                        )
                                    } +
                                        OcrHypothesis(
                                            candidate.text,
                                            candidate.confidence,
                                            candidate.variant,
                                        ))
                                    .distinctBy { it.variant }
                            val selected =
                                if (
                                    candidate.confidence != null &&
                                        candidate.confidence > (line.confidence ?: 0f) + .025f
                                )
                                    candidate.copy(block = line.block, paragraph = line.paragraph)
                                else line
                            val original = hypotheses.first()
                            val strongest = hypotheses.maxBy { it.confidence ?: 0f }
                            val tieText =
                                if (
                                    (strongest.confidence ?: 0f) >
                                        (original.confidence ?: 0f) + .025f
                                )
                                    strongest.text
                                else original.text
                            val ballot =
                                hypotheses.filter { it.text.length == selected.text.length }
                            val voted =
                                if (voting && ballot.size >= 3)
                                    selected.text.indices
                                        .map { i ->
                                            val counts =
                                                ballot.groupingBy { it.text[i] }.eachCount()
                                            val top = counts.maxBy { it.value }
                                            if (top.value > ballot.size / 2) top.key
                                            else if (tieText.length == selected.text.length)
                                                tieText[i]
                                            else selected.text[i]
                                        }
                                        .joinToString("")
                                        .takeIf { value -> hypotheses.any { it.text == value } }
                                        ?: selected.text
                                else selected.text
                            if (voted != selected.text)
                                selected.copy(
                                    text = voted,
                                    elements = emptyList(),
                                    variant = "variant-vote",
                                    hypotheses = hypotheses,
                                )
                            else selected.copy(hypotheses = hypotheses)
                        }
                    }
            )
        return includeMissing(fused, alternative)
    }

    /**
     * Recover whole observed rows that the first pass missed; never append overlapping fragments.
     */
    private fun includeMissing(base: OcrLayout, alternative: OcrLayout): OcrLayout {
        val lines = base.lines.toMutableList()
        for (candidate in alternative.lines) {
            if (
                candidate.corners.size != 4 ||
                    candidate.text.isBlank() ||
                    (candidate.confidence ?: 0f) < .72f ||
                    candidate.corners.any {
                        !it.x.isFinite() ||
                            !it.y.isFinite() ||
                            it.x !in -.01f..1.01f ||
                            it.y !in -.01f..1.01f
                    }
            )
                continue
            val c = bounds(candidate)
            if (c[2] <= c[0] || c[3] <= c[1]) continue
            val occupied =
                lines.any { line ->
                    val b = bounds(line)
                    val iw = max(0f, min(c[2], b[2]) - max(c[0], b[0]))
                    val ih = max(0f, min(c[3], b[3]) - max(c[1], b[1]))
                    iw / max(.000001f, min(c[2] - c[0], b[2] - b[0])) > .30f &&
                        ih / max(.000001f, min(c[3] - c[1], b[3] - b[1])) > .45f
                }
            if (!occupied) lines += candidate
        }
        return base.copy(lines = lines)
    }

    private fun bounds(line: OcrLine) =
        floatArrayOf(
            line.corners.minOf { it.x },
            line.corners.minOf { it.y },
            line.corners.maxOf { it.x },
            line.corners.maxOf { it.y },
        )
}
