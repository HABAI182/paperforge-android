package cn.paperforge.app

import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.OcrReadingOrder
import org.junit.Assert.*
import org.junit.Test

class OcrReadingOrderTest {
    private fun line(s: String, x: Float, y: Float, w: Float) =
        OcrLine(
            s,
            listOf(PointN(x, y), PointN(x + w, y), PointN(x + w, y + .03f), PointN(x, y + .03f)),
        )

    @Test
    fun columnsStayTogetherBelowSpanningTitle() {
        val input =
            OcrLayout(
                1000,
                1400,
                listOf(
                    line("R2", .57f, .3f, .35f),
                    line("title", .1f, .05f, .8f),
                    line("L2", .08f, .3f, .35f),
                    line("R1", .57f, .2f, .35f),
                    line("L1", .08f, .2f, .35f),
                ),
            )
        assertEquals(
            listOf("title", "L1", "L2", "R1", "R2"),
            OcrReadingOrder.arrange(input).lines.map { it.text },
        )
        val rotated = input.rotated(90).rotated(270)
        input.lines.zip(rotated.lines).forEach { (a, b) ->
            a.corners.zip(b.corners).forEach { (x, y) ->
                assertEquals(x.x, y.x, .00001f)
                assertEquals(x.y, y.y, .00001f)
            }
        }
    }

    @Test
    fun deskewAndInverseKeepCoordinates() {
        for (degrees in listOf(-7f, -2.5f, 0f, 3.8f, 8f)) {
            val geometry = DeskewGeometry(3024, 4032, degrees)
            for (point in listOf(PointN(0f, 0f), PointN(1f, 1f), PointN(.34f, .72f))) {
                val restored = geometry.inverse(geometry.forward(point))
                assertEquals(point.x, restored.x, .00001f)
                assertEquals(point.y, restored.y, .00001f)
            }
        }
    }

    @Test
    fun repeatedAgreementChoosesObservedTextAndKeepsHypotheses() {
        val good =
            (0..5).map { i ->
                line("ABCDEF $i", .1f, .1f + i * .05f, .7f)
                    .copy(hypotheses = listOf(OcrHypothesis("ABCDEF $i", .7f, "original")))
            }
        val uncertain =
            line("XBCDEF 7", .1f, .5f, .7f)
                .copy(
                    confidence = .6f,
                    hypotheses =
                        listOf(
                            OcrHypothesis("XBCDEF 7", .6f, "original"),
                            OcrHypothesis("ABCDEF 7", .59f, "clean"),
                        ),
                )
        val result =
            cn.paperforge.app.ocr.OcrPageVoting.apply(OcrLayout(1000, 1400, good + uncertain))
        assertEquals("ABCDEF 7", result.lines.last().text)
        assertEquals(uncertain.hypotheses, result.lines.last().hypotheses)
        val unsupported =
            uncertain.copy(hypotheses = listOf(OcrHypothesis("XBCDEF 7", .6f, "original")))
        assertEquals(
            "XBCDEF 7",
            cn.paperforge.app.ocr.OcrPageVoting.apply(OcrLayout(1000, 1400, good + unsupported))
                .lines
                .last()
                .text,
        )
    }
}
