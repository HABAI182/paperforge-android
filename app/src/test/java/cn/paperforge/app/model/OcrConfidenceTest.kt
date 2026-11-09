package cn.paperforge.app.model

import org.junit.Assert.*
import org.junit.Test

/** #24: which fragments are flagged and how the review buttons cycle (pure JVM, no Android). */
class OcrConfidenceTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) =
        listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b))

    private fun line(
        text: String,
        confidence: Float? = null,
        words: List<Float?> = emptyList(),
        corrected: Boolean = false,
    ) =
        OcrLine(
            text,
            box(.1f, .1f, .9f, .2f),
            confidence,
            elements =
                words.mapIndexed { index, score ->
                    OcrElement(
                        "w$index",
                        box(.1f + index * .1f, .1f, .2f + index * .1f, .2f),
                        score,
                    )
                },
            corrected = corrected,
        )

    private fun layout(vararg lines: OcrLine) = OcrLayout(100, 100, lines.toList())

    @Test
    fun thresholdFollowsConfiguredRange() {
        assertEquals(.6f, OcrConfidence.threshold(.6f), 0f)
        assertEquals(.3f, OcrConfidence.threshold(.1f), 0f)
        assertEquals(.9f, OcrConfidence.threshold(2f), 0f)
        assertEquals(.6f, OcrConfidence.threshold(Float.NaN), 0f)
        assertEquals(.6f, OcrConfidence.threshold(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun lowLinesFilterByThresholdAndIgnoreUnknownScores() {
        val subject =
            layout(
                line("below", .59f),
                line("onThreshold", .6f),
                line("unknown", null),
                line("lowWordOnly", .95f, listOf(.42f, .9f)),
                line("highWordOnly", .95f, listOf(.9f, null)),
                line("unknownLineLowWord", null, listOf(null, .35f)),
                line("correctedUnknown", null, corrected = true),
            )
        assertEquals(listOf(0, 3, 5), OcrConfidence.lowLines(subject, .6f))
        assertEquals(emptyList<Int>(), OcrConfidence.lowLines(subject, .3f))
        assertEquals(listOf(0, 1, 3, 5), OcrConfidence.lowLines(subject, .9f))
    }

    @Test
    fun lowWordsSkipUnknownAndKeepOrder() {
        val subject = line("abc", .4f, listOf(.59f, null, .61f, .3f))
        assertEquals(listOf(0, 3), OcrConfidence.lowWords(subject, .6f))
        assertEquals(emptyList<Int>(), OcrConfidence.lowWords(subject, .3f))
    }

    @Test
    fun lineIsLowCoversOwnScoreAndWords() {
        assertTrue(OcrConfidence.lineIsLow(line("a", .5f), .6f))
        assertTrue(OcrConfidence.lineIsLow(line("a", .9f, listOf(.59f)), .6f))
        assertFalse(OcrConfidence.lineIsLow(line("a", .9f, listOf(.6f)), .6f))
        assertFalse(OcrConfidence.lineIsLow(line("a", null), .6f))
    }

    @Test
    fun stepWrapsForwardAndBackward() {
        val flagged = listOf(1, 4, 7)
        assertEquals(1, OcrConfidence.step(0, flagged, true))
        assertEquals(4, OcrConfidence.step(1, flagged, true))
        assertEquals(7, OcrConfidence.step(5, flagged, true))
        assertEquals(1, OcrConfidence.step(7, flagged, true))
        assertEquals(7, OcrConfidence.step(0, flagged, false))
        assertEquals(1, OcrConfidence.step(4, flagged, false))
        assertEquals(4, OcrConfidence.step(5, flagged, false))
        assertEquals(7, OcrConfidence.step(99, flagged, false))
        assertEquals(1, OcrConfidence.step(99, flagged, true))
        assertEquals(0, OcrConfidence.step(0, listOf(0), true))
        assertEquals(0, OcrConfidence.step(0, listOf(0), false))
        assertNull(OcrConfidence.step(0, emptyList(), true))
        assertNull(OcrConfidence.step(0, emptyList(), false))
    }

    @Test
    fun singleFlaggedLineCyclesToItselfAfterEditing() {
        val flagged = listOf(3)
        assertEquals(3, OcrConfidence.step(3, flagged, true))
        assertEquals(3, OcrConfidence.step(3, flagged, false))
    }

    @Test
    fun reviewQueueUsesActualScoresDisagreementAndSkipsCorrections() {
        val disagreement =
            line("学号 2026O101", null)
                .copy(hypotheses = listOf(OcrHypothesis("学号 20260101", null, "alternate")))
        val subject =
            layout(
                line("normal", .95f),
                line("faint", .5f),
                disagreement,
                line("weak word", .9f, listOf(.2f)),
                disagreement.copy(corrected = true),
            )
        val ordered = OcrConfidence.queue(subject, .6f, lowestFirst = true)
        assertEquals(listOf(3, 1, 2), ordered.map { it.line })
        assertNull(ordered.last().confidence)
        assertTrue(ordered.last().reasons.contains("多路识别有分歧"))
        assertEquals(1, OcrConfidence.stepQueue(3, ordered.map { it.line }, true))
        assertEquals(2, OcrConfidence.stepQueue(3, ordered.map { it.line }, false))
        assertEquals(3, OcrConfidence.stepQueue(0, ordered.map { it.line }, true))
        assertEquals(
            listOf(1, 2, 3),
            OcrConfidence.queue(subject, .6f, lowestFirst = false).map { it.line },
        )
        assertTrue(OcrConfidence.queue(layout(line("unknown", null)), .6f, true).isEmpty())
        val partlyCorrected =
            line("fixed word plus weak word", null, listOf(null, .2f), corrected = true)
        assertEquals(listOf("包含低置信度词"), OcrConfidence.review(partlyCorrected, .6f))
    }
}
