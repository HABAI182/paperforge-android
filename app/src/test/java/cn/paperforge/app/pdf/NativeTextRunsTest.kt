package cn.paperforge.app.pdf

import org.junit.Assert.*
import org.junit.Test

class NativeTextRunsTest {
    private val line = "请于 2026 年提交 Report"

    @Test
    fun restyleKeepsOriginalGlyphsAroundTheRange() {
        val runs = NativeTextRuns.restyle(NativeTextRuns.initial(line), 3, 7, NativeStyle(color = 0xFFFF0000.toInt()))
        assertEquals(line, NativeTextRuns.text(runs))
        assertEquals(3, runs.size)
        // Colour only: all three runs still re-use original glyphs.
        assertTrue(runs.all { it.keepsGlyphs })
        assertEquals(3 to 7, runs[1].origStart to runs[1].origEnd)
        assertEquals(0xFFFF0000.toInt(), runs[1].color)
        assertNull(runs[0].color)
    }

    @Test
    fun fontChangeTurnsTheRangeIntoNewText() {
        val runs = NativeTextRuns.restyle(NativeTextRuns.initial(line), 0, 2, NativeStyle(font = "builtin:kai"))
        assertFalse(runs[0].keepsGlyphs)
        assertEquals("请于", runs[0].text)
        assertEquals("builtin:kai", runs[0].font)
        assertTrue(runs[1].keepsGlyphs)
        assertEquals(2, runs[1].origStart)
    }

    @Test
    fun replaceInheritsStyleAndKeepsTheRest() {
        val styled = NativeTextRuns.restyle(NativeTextRuns.initial(line), 3, 7, NativeStyle(size = 14f))
        val replaced = NativeTextRuns.replace(styled, 3, 7, "2027")
        assertEquals("请于 2027 年提交 Report", NativeTextRuns.text(replaced))
        val new = replaced.first { it.text == "2027" }
        assertEquals(14f, new.size)
        assertFalse(new.keepsGlyphs)
        assertTrue(replaced.first().keepsGlyphs)
        assertTrue(replaced.last().keepsGlyphs)
    }

    @Test
    fun deletingEverythingLeavesNoRuns() {
        assertTrue(NativeTextRuns.replace(NativeTextRuns.initial(line), 0, line.length, "").isEmpty())
    }

    @Test
    fun restoringTheStyleMergesBackToTheOriginal() {
        val red = NativeTextRuns.restyle(NativeTextRuns.initial(line), 3, 7, NativeStyle(color = 0xFFFF0000.toInt()))
        // A later edit that touches the same range with the original colour again would need a "clear" style;
        // splitting and normalising an untouched run list must give back the initial list.
        assertTrue(NativeTextRuns.unchanged(line, NativeTextRuns.normalize(NativeTextRuns.initial(line))))
        assertFalse(NativeTextRuns.unchanged(line, red))
    }

    @Test
    fun overlappingEditsStayConsistent() {
        var runs = NativeTextRuns.restyle(NativeTextRuns.initial(line), 0, 7, NativeStyle(font = "CJK"))
        runs = NativeTextRuns.restyle(runs, 5, 12, NativeStyle(color = 0xFF0000FF.toInt()))
        runs = NativeTextRuns.replace(runs, 9, 11, "提交")
        assertEquals(line, NativeTextRuns.text(runs))
        // Offsets of kept glyphs still point into the original text.
        runs.filter { it.keepsGlyphs }.forEach { assertEquals(line.substring(it.origStart, it.origEnd), it.text) }
    }

    @Test
    fun opsAcrossObjectsMapLinesToSegments() {
        val a = NativePdfEditor.TextObject(0, 0, "第一行文字", "F", 12f)
        val b = NativePdfEditor.TextObject(0, 1, "第二行文字", "F", 12f)
        val originals = mapOf(a.key to a, b.key to b)
        val segments = listOf(NativeSegment(a.key, 3, 5), NativeSegment(b.key, 0, 2))
        val edits = NativeEditOps.replace(emptyList(), originals, segments, "内容\n下一")
        assertEquals("第一行内容", edits.first { it.key == a.key }.text)
        assertEquals("下一行文字", edits.first { it.key == b.key }.text)
        // Different line count: all text goes to the first segment, the rest is removed.
        val merged = NativeEditOps.replace(emptyList(), originals, segments, "合并")
        assertEquals("第一行合并", merged.first { it.key == a.key }.text)
        assertEquals("行文字", merged.first { it.key == b.key }.text)
    }

    @Test
    fun uneditableObjectsAreLeftAlone() {
        val ocr = NativePdfEditor.TextObject(0, 0, "识别层", "F", 12f, reason = "不可见文字层，请使用OCR校对", invisible = true)
        val edits = NativeEditOps.restyle(emptyList(), mapOf(ocr.key to ocr), listOf(NativeSegment(ocr.key, 0, 3)), NativeStyle(font = "CJK"))
        assertTrue(edits.isEmpty())
    }
}
