package cn.paperforge.app.model

/**
 * Applies one manual correction to an already recognized layout without touching geometry.
 *
 * The edited fragment keeps its corners (line and every word), the other lines and words stay
 * byte-for-byte identical, and only the edited text/word loses its confidence because it is no
 * longer a model result. Encoding stays the same as before, so page coordinates and the layout IR
 * are preserved.
 */
object OcrLineCorrection {
    fun apply(layout: OcrLayout, lineIndex: Int, word: Int?, text: String): OcrLayout {
        val line = layout.lines.getOrNull(lineIndex) ?: error("请选择文字行")
        require(!text.contains('\n')) { "逐行校正请保留当前行，换行可在完整文字中编辑" }
        val updated =
            if (word == null)
                line.copy(text = text, elements = emptyList(), corrected = true, confidence = null)
            else {
                val span =
                    OcrRegionMerge.wordSpan(line, word) ?: error("该词无法单独替换，请选择整行校正")
                line.copy(
                    text = line.text.replaceRange(span.first, span.last + 1, text),
                    elements =
                        line.elements.mapIndexed { index, element ->
                            if (index == word) element.copy(text = text, confidence = null)
                            else element
                        },
                    corrected = true,
                    confidence = null,
                )
            }
        return layout.copy(
            lines =
                layout.lines.mapIndexed { index, old -> if (index == lineIndex) updated else old }
        )
    }
}
