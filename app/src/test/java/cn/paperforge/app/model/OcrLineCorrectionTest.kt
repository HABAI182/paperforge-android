package cn.paperforge.app.model

import org.junit.Assert.*
import org.junit.Test

/** #24: manual fragment write-back must keep geometry and the rest of the OCR layout intact. */
class OcrLineCorrectionTest {
    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        listOf(PointN(left, top), PointN(right, top), PointN(right, bottom), PointN(left, bottom))

    private fun element(text: String, confidence: Float?, left: Float, right: Float) =
        OcrElement(text, box(left, .1f, right, .15f), confidence)

    private fun layout() =
        OcrLayout(
            200,
            320,
            listOf(
                OcrLine(
                    "Outside Header",
                    box(.1f, .1f, .9f, .15f),
                    .55f,
                    elements =
                        listOf(
                            element("Outside", .81f, .1f, .45f),
                            element("Header", .52f, .5f, .9f),
                        ),
                ),
                OcrLine(
                    "Footer 00123",
                    box(.1f, .8f, .9f, .85f),
                    .48f,
                    elements =
                        listOf(
                            element("Footer", .7f, .1f, .4f),
                            element("00123", .43f, .45f, .9f),
                        ),
                ),
            ),
            engineId = "unit-test",
            sourceRevision = 4,
        )

    @Test
    fun firstWordReplacementKeepsGeometryAndSiblings() {
        val original = layout()
        val result = OcrLineCorrection.apply(original, 0, 0, "Inner")
        val line = result.lines[0]
        assertEquals("Inner Header", line.text)
        assertEquals("Inner", line.elements[0].text)
        assertNull("人工改写后不再是模型置信度", line.elements[0].confidence)
        assertEquals(original.lines[0].elements[0].corners, line.elements[0].corners)
        assertEquals(original.lines[0].elements[1], line.elements[1])
        assertNull(line.confidence)
        assertTrue(line.corrected)
        assertEquals(original.lines[0].corners, line.corners)
        assertEquals(original.lines[1], result.lines[1])
        assertEquals(original.width, result.width)
        assertEquals(original.height, result.height)
        assertEquals(original.sourceRevision, result.sourceRevision)
        assertEquals(2, result.lines.size)
    }

    @Test
    fun lastWordReplacementKeepsGeometry() {
        val original = layout()
        val result = OcrLineCorrection.apply(original, 1, 1, "00128")
        val line = result.lines[1]
        assertEquals("Footer 00128", line.text)
        assertEquals("00128", line.elements[1].text)
        assertEquals(original.lines[1].elements[1].corners, line.elements[1].corners)
        assertEquals(original.lines[1].elements[0], line.elements[0])
        assertEquals(original.lines[0], result.lines[0])
    }

    @Test
    fun emptyWordTextDeletesOnlyThatFragment() {
        val result = OcrLineCorrection.apply(layout(), 0, 1, "")
        val line = result.lines[0]
        assertEquals("Outside ", line.text)
        assertEquals("", line.elements[1].text)
        assertEquals("Outside", line.elements[0].text)
        assertEquals(2, line.elements.size)
        assertEquals(layout().lines[0].elements[1].corners, line.elements[1].corners)
    }

    @Test
    fun editAfterEmptyFragmentKeepsWordsButWordGapIsNotRestored() {
        // OCR elements never contain the separators between words, so after clearing a word the
        // re-insert position is the old element offset, not the old separator. Document the
        // limitation: words survive, spacing may collapse ("Outside Header" -> "OutsideHeader ").
        val emptied = OcrLineCorrection.apply(layout(), 0, 1, "")
        val restored = OcrLineCorrection.apply(emptied, 0, 1, "Header")
        assertEquals("OutsideHeader ", restored.lines[0].text)
        assertEquals(listOf("Outside", "Header"), restored.lines[0].elements.map { it.text })
        assertEquals(layout().lines[0].elements[1].corners, restored.lines[0].elements[1].corners)
    }

    @Test
    fun emptyWholeLineClearsTextAndWords() {
        val result = OcrLineCorrection.apply(layout(), 0, null, "")
        val line = result.lines[0]
        assertEquals("", line.text)
        assertTrue(line.elements.isEmpty())
        assertTrue(line.corrected)
        assertNull(line.confidence)
        assertEquals(layout().lines[1], result.lines[1])
    }

    @Test
    fun wholeLineReplacementKeepsOtherLines() {
        val result = OcrLineCorrection.apply(layout(), 1, null, "整行人工校正")
        val line = result.lines[1]
        assertEquals("整行人工校正", line.text)
        assertTrue(line.elements.isEmpty())
        assertTrue(line.corrected)
        assertEquals(layout().lines[0], result.lines[0])
    }

    @Test
    fun veryLongTextIsKeptVerbatim() {
        val long = "超长校正".repeat(1000) + "尾"
        val result = OcrLineCorrection.apply(layout(), 0, 0, long)
        assertEquals("$long Header", result.lines[0].text)
        assertEquals(long, result.lines[0].elements[0].text)
        assertEquals(long.length + 1 + "Header".length, result.lines[0].text.length)
        assertEquals(layout().lines[0].elements[0].corners, result.lines[0].elements[0].corners)
    }

    @Test
    fun newlineIsRejected() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                OcrLineCorrection.apply(layout(), 0, null, "第一行\n第二行")
            }
        assertTrue(error.message.orEmpty().contains("换行"))
    }

    @Test
    fun outOfRangeLineIsRejected() {
        listOf(-1, 2, 99).forEach { index ->
            val error =
                assertThrows(IllegalStateException::class.java) {
                    OcrLineCorrection.apply(layout(), index, null, "x")
                }
            assertEquals("请选择文字行", error.message)
        }
    }

    @Test
    fun missingWordSpanFallsBackToWholeLineMessage() {
        val wholeLine = OcrLineCorrection.apply(layout(), 0, null, "整行")
        val error =
            assertThrows(IllegalStateException::class.java) {
                OcrLineCorrection.apply(wholeLine, 0, 0, "词")
            }
        assertEquals("该词无法单独替换，请选择整行校正", error.message)
        val outOfRange =
            assertThrows(IllegalStateException::class.java) {
                OcrLineCorrection.apply(layout(), 0, 5, "词")
            }
        assertEquals("该词无法单独替换，请选择整行校正", outOfRange.message)
    }

    @Test
    fun repeatedCorrectionsStayConsistent() {
        var current = layout()
        current = OcrLineCorrection.apply(current, 0, 0, "First")
        current = OcrLineCorrection.apply(current, 0, 1, "Last")
        current = OcrLineCorrection.apply(current, 1, 0, "Tail")
        assertEquals("First Last", current.lines[0].text)
        assertEquals("Tail 00123", current.lines[1].text)
        assertEquals(2, current.lines.size)
        assertEquals(layout().lines[0].elements[0].corners, current.lines[0].elements[0].corners)
    }
}
