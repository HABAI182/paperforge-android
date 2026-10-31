package cn.paperforge.app.model

/**
 * #24: which OCR fragments are worth human review, and how the review buttons step through them.
 *
 * Confidence is only compared when the engine actually reported it. A null score means "unknown"
 * (the model did not return a finite 0..1 value, the line was merged without one, or the text was
 * already corrected) and is never treated as low confidence, so the UI does not invent certainty.
 */
object OcrConfidence {
    const val DEFAULT_THRESHOLD = .6f
    val RANGE = .3f..0.9f

    /** Settings are clamped here as well as in the preferences panel (defensive for imports). */
    fun threshold(value: Float) =
        if (!value.isFinite()) DEFAULT_THRESHOLD else value.coerceIn(RANGE)

    fun isLow(confidence: Float?, threshold: Float) =
        confidence != null && confidence.isFinite() && confidence < threshold

    /**
     * A line is flagged when its own score is below the threshold, or when any word in it is.
     * ML Kit sometimes reports element scores while the line score is absent, and the user still
     * needs to reach that line.
     */
    fun lineIsLow(line: OcrLine, threshold: Float) =
        isLow(line.confidence, threshold) ||
            line.elements.any { isLow(it.confidence, threshold) }

    fun lowLines(layout: OcrLayout, threshold: Float) =
        layout.lines.indices.filter { lineIsLow(layout.lines[it], threshold) }

    fun lowWords(line: OcrLine, threshold: Float) =
        line.elements.indices.filter { isLow(line.elements[it].confidence, threshold) }

    /**
     * Next low-confidence line after [current], wrapping around. Works even when [current] is not
     * itself flagged (for example after the user edited the line that was flagged).
     */
    fun step(current: Int, lines: List<Int>, forward: Boolean): Int? {
        if (lines.isEmpty()) return null
        return if (forward) lines.firstOrNull { it > current } ?: lines.first()
        else lines.lastOrNull { it < current } ?: lines.last()
    }
}
