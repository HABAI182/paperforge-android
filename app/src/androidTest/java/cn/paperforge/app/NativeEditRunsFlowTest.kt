package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Native text editing on representative PDFs: partial restyles, replacements, colour, glyph coverage, re-editing a
 * saved copy, and what must not change (other objects' positions, page text, annotations). Outputs are kept in
 * files/native-edit-out for an independent check with another PDF parser.
 */
class NativeEditRunsFlowTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val out = File(app.filesDir, "native-edit-out").apply { mkdirs() }

    private fun sample(name: String): File {
        val file = File(out, "src-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open("native-edit/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun text(file: File, page: Int = 0): String =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            PDFTextStripper().apply {
                startPage = page + 1
                endPage = page + 1
            }.getText(pdf)
        }

    /** Page text as the editor reads it (tolerant ToUnicode); PDFBox's stripper misreads some writers' CMaps. */
    private fun read(file: File, page: Int = 0): String =
        runBlocking { NativePdfEditor.inspect(file, page).second }.joinToString("|") { it.text }

    private fun request(file: File, edits: List<NativePdfEditor.Edit>) =
        JSONObject()
            .put("sourceSha256", runBlocking { PdfAssembly.sha256(file) })
            .put("edits", JSONArray(edits.map { it.json() }))

    private fun save(input: File, edits: List<NativePdfEditor.Edit>, name: String): Pair<File, JSONObject> =
        runBlocking {
            val output = File(out, name).apply { delete() }
            val report = NativePdfEditor.apply(app, input, output, request(input, edits))
            output to report
        }

    private fun objects(file: File, page: Int = 0) = runBlocking { NativePdfEditor.inspect(file, page).second }

    private fun box(o: NativePdfEditor.TextObject) = o.corners.getOrNull(0)

    @Test
    fun partialRestyleKeepsTextPositionsAndSearchability() {
        val input = sample("mixed-zh-en.pdf")
        val before = objects(input)
        val target = before.first { it.text.startsWith("请于") }
        assertTrue(target.editable)
        assertEquals(target.text.length, target.units.maxOf { it.end })
        // Red serif on "2026 年", the rest of the line keeps its glyphs.
        val start = target.text.indexOf("2026")
        val runs =
            NativeTextRuns.restyle(
                NativeTextRuns.initial(target.text),
                start,
                start + 6,
                NativeStyle(font = "builtin:serif-sc", color = 0xFFC62828.toInt()),
            )
        val edit = NativePdfEditor.Edit.of(target, runs)!!
        val (output, report) = save(input, listOf(edit), "mixed-restyled.pdf")
        assertFalse(report.getJSONArray("edits").getJSONObject(0).getBoolean("overflow"))
        val after = objects(output)
        // Same objects, same text, same place; the edited one is one group.
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) ->
            assertEquals(b.text, a.text)
            val p = box(b)!!
            val q = box(a)!!
            assertEquals("object ${b.index} moved", p.x, q.x, .002f)
            assertEquals(p.y, q.y, .01f)
        }
        assertTrue(after.first { it.index == target.index }.edited)
        assertEquals(read(input), read(output))
    }

    @Test
    fun replaceWordFillsGlyphsTheSubsetLacks() {
        val input = sample("zh-native-subset.pdf")
        val items = objects(input)
        val target = items.first { it.text.startsWith("矩阵的秩") }
        // The subset embeds only the glyphs used; new characters are missing from it. The edit no longer fails:
        // what the subset has keeps the subset, the rest is filled by a built-in font of the same style.
        val runs = NativeTextRuns.replace(NativeTextRuns.initial(target.text), 0, 2, "向量组")
        val (output, report) = save(input, listOf(NativePdfEditor.Edit.of(target, runs)!!), "zh-replaced.pdf")
        val fonts = report.getJSONArray("edits").getJSONObject(0).getString("actualFont").split(",").filter { it.isNotBlank() }
        assertTrue("both the subset and a fill-in font are used: $fonts", fonts.distinct().size >= 2)
        val page = read(output)
        assertTrue(page, page.contains("向量组的秩等于其行阶梯形中非零行的个数"))
        val after = objects(output)
        assertEquals(items.size, after.size)
        // Following lines did not move.
        items.filter { it.index != target.index }.forEach { b ->
            val a = after.first { it.index == b.index }
            assertEquals(b.text, a.text)
            assertEquals(box(b)!!.y, box(a)!!.y, .002f)
        }
    }

    @Test
    fun retypingReusesTheSubsetsOwnGlyphsAndChangesOnlyWhatChanged() {
        val input = sample("zh-native-subset.pdf")
        val items = objects(input)
        val target = items.first { it.text.startsWith("矩阵的秩") }
        val originals = items.associateBy { it.key }
        // "个数" -> "行数": 行 is on the page, so the embedded subset has it; nothing else in the line is touched.
        val edits =
            NativeEditOps.replace(
                emptyList(),
                originals,
                listOf(NativeSegment(target.key, 0, target.text.length)),
                target.text.replace("个数", "行数"),
            )
        val edit = edits.single()
        assertEquals(3, edit.runs.size)
        assertTrue(edit.runs.first().keepsGlyphs)
        assertTrue(edit.runs.last().keepsGlyphs)
        assertEquals("行", edit.runs[1].text)
        assertEquals(NativeRun.ORIGINAL, edit.runs[1].font)
        val (output, report) = save(input, edits, "zh-retyped-original-font.pdf")
        assertEquals(target.font, report.getJSONArray("edits").getJSONObject(0).getString("actualFont"))
        assertTrue(read(output).contains("非零行的行数"))
    }

    @Test
    fun kerningSpacingAndQuoteOperatorsSurvivePartialEdits() {
        val input = sample("en-kerning.pdf")
        val before = objects(input)
        val texts = before.map { it.text }
        assertTrue(texts.toString(), texts.any { it.startsWith("Quarterly report") })
        val edits =
            before.filter { it.editable && it.text.length > 8 }.map { item ->
                val runs = NativeTextRuns.restyle(NativeTextRuns.initial(item.text), 2, 6, NativeStyle(color = 0xFF1565C0.toInt(), sizeFactor = 1f))
                NativePdfEditor.Edit.of(item, runs)!!
            }
        assertTrue(edits.size >= 5)
        val (output, _) = save(input, edits, "en-kerning-colored.pdf")
        val after = objects(output)
        before.forEach { b ->
            val a = after.first { it.index == b.index }
            assertEquals(b.text, a.text)
            // Kept glyphs keep their kerning: every character box stays put.
            b.units.zip(a.units).forEach { (u, v) ->
                assertEquals("'${b.text}' char ${u.start}", u.left, v.left, .001f)
                assertEquals(u.right, v.right, .001f)
            }
        }
        assertEquals(read(input), read(output))
        assertEquals(text(input), text(output))
    }

    @Test
    fun savedCopyCanBeEditedAgainAndRestoredToOriginalFont() {
        val input = sample("en-serif-subset.pdf")
        val target = objects(input).first()
        val first =
            NativeTextRuns.restyle(NativeTextRuns.initial(target.text), 4, 9, NativeStyle(font = "builtin:serif-sc-bold", size = 13f))
        val (copy, _) = save(input, listOf(NativePdfEditor.Edit.of(target, first)!!), "serif-first.pdf")
        val reopened = objects(copy).first { it.index == target.index }
        assertTrue(reopened.edited)
        assertTrue(reopened.editable)
        assertEquals(target.text, reopened.text)
        // Second round on the copy: recolour a different word of the same object.
        val second = NativeTextRuns.restyle(NativeTextRuns.initial(reopened.text), 10, 15, NativeStyle(color = 0xFF2E7D32.toInt()))
        val (again, _) = save(copy, listOf(NativePdfEditor.Edit.of(reopened, second)!!), "serif-second.pdf")
        val last = objects(again)
        assertEquals(objects(input).size, last.size)
        assertEquals(target.text, last.first { it.index == target.index }.text)
        assertTrue(text(again).contains("The quick brown fox"))
    }

    @Test
    fun annotationsLinksAndImagesStayIntact() {
        val input = sample("mixed-annotated.pdf")
        val target = objects(input).first { it.text.startsWith("说明") }
        val runs = NativeTextRuns.replace(NativeTextRuns.initial(target.text), 0, 2, "注释", NativeStyle(font = "CJK"))
        val (output, _) = save(input, listOf(NativePdfEditor.Edit.of(target, runs)!!), "annotated-edited.pdf")
        PDDocument.load(input).use { a ->
            PDDocument.load(output).use { b ->
                val pa = a.getPage(0)
                val pb = b.getPage(0)
                assertEquals(pa.annotations.map { it.subtype }, pb.annotations.map { it.subtype })
                assertEquals(pa.resources.xObjectNames.count(), pb.resources.xObjectNames.count())
                assertEquals(pa.mediaBox.toString(), pb.mediaBox.toString())
            }
        }
        assertTrue(read(output).contains("注释：数据来自"))
    }

    @Test
    fun scannedPagesAreNotOfferedAsNativeText() {
        assertTrue(objects(sample("scanned-image.pdf")).none { it.text.isNotBlank() })
        val ocr = objects(sample("scanned-ocr.pdf")).filter { it.text.isNotBlank() }
        assertTrue(ocr.isNotEmpty())
        assertTrue(ocr.all { it.invisible && !it.editable })
    }

    @Test
    fun rotatedPageGeometryStaysOnThePage() {
        val items = objects(sample("rotated-90.pdf")).filter { it.text.isNotBlank() }
        assertTrue(items.isNotEmpty())
        items.flatMap { it.units }.forEach { u ->
            assertTrue(u.left >= -.01f && u.right <= 1.01f && u.top >= -.01f && u.bottom <= 1.01f)
        }
        // Text runs down the displayed page on a 90 degree rotation: boxes are taller than wide.
        val all = items.flatMap { it.units }
        assertTrue((all.maxOf { it.bottom } - all.minOf { it.top }) > (all.maxOf { it.right } - all.minOf { it.left }))
    }

    @Test
    fun selectionSpansObjectsInReadingOrder() {
        val items = objects(sample("out-of-order.pdf"))
        val model = NativeSelection.page(0, items)
        val all = ReaderTextSelection(model, 0, model.units.lastIndex)
        val segments = NativeSelection.segments(all)
        assertEquals(items.count { it.text.isNotBlank() }, segments.size)
        // Left column comes before the right column although it was written later.
        val order = segments.map { s -> items.first { it.key == s.key }.text }
        assertTrue(order.toString(), order.indexOf("左栏") < order.indexOf("右栏"))
    }

    @Test
    fun bulkFontChangeOverManyObjectsAndPages() {
        val input = sample("multipage-long.pdf")
        val edits = mutableListOf<NativePdfEditor.Edit>()
        val started = System.nanoTime()
        for (page in 0 until 5) {
            val items = objects(input, page)
            val originals = items.associateBy { it.key }
            edits += NativeEditOps.restyle(emptyList(), originals, NativeSelection.whole(items, items.map { it.key }), NativeStyle(font = "builtin:kai"))
        }
        val inspectMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5 * 19, edits.size)
        val t0 = System.nanoTime()
        val (output, _) = save(input, edits, "multipage-kai.pdf")
        val saveMs = (System.nanoTime() - t0) / 1_000_000
        for (page in 0 until 5) assertEquals(read(input, page), read(output, page))
        File(out, "bulk-timing.json").writeText(JSONObject().put("inspect5PagesMs", inspectMs).put("save95ObjectsMs", saveMs).put("bytes", output.length()).toString())
    }

    @Test
    fun previewOfOnePageMatchesSave() {
        val input = sample("multipage-long.pdf")
        val items = objects(input, 2)
        val target = items[3]
        val runs = NativeTextRuns.replace(NativeTextRuns.initial(target.text), 0, 2, "那是", NativeStyle(font = "CJK"))
        val edit = NativePdfEditor.Edit.of(target, runs)!!
        runBlocking {
            NativeEditSession(app, input).use { session ->
                session.open()
                val t0 = System.nanoTime()
                val shown = session.preview(2, listOf(edit), emptyMap())
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(items.size, shown.objects.size)
                assertEquals(NativeTextRuns.text(runs), shown.objects.first { it.index == target.index }.text)
                File(out, "preview-timing.json").writeText(JSONObject().put("previewMs", ms).toString())
            }
        }
        val (output, _) = save(input, listOf(edit), "multipage-p3.pdf")
        assertTrue(read(output, 2).contains("那是第 3 页的第 3 行"))
        assertEquals(read(input, 1), read(output, 1))
    }

    @Test
    fun standardFontsReEncodeLatinText() {
        val input = sample("standard14.pdf")
        val target = objects(input).first { it.text.startsWith("Standard Helvetica") }
        val runs = NativeTextRuns.replace(NativeTextRuns.initial(target.text), 9, 18, "Arial-like")
        val (output, _) = save(input, listOf(NativePdfEditor.Edit.of(target, runs)!!), "standard-replaced.pdf")
        assertTrue(text(output).contains("Standard Arial-like text"))
        val again = objects(output).first { it.index == target.index }
        assertTrue(abs(box(again)!!.x - box(target)!!.x) < .002f)
    }

    /**
     * A chosen font that lacks part of the text (a Latin font on a Chinese line): Latin takes the chosen font, Chinese
     * keeps the line's own font, nothing is refused or lost, and the text reads back the same.
     */
    @Test
    fun chosenFontWithoutChineseKeepsOriginalForChineseCharacters() {
        val input = sample("mixed-zh-en.pdf")
        val before = objects(input)
        val target = before.first { it.text.startsWith("请于") }
        // A packaged font with Latin letters and digits that lacks 请: Liberation Sans in the full package; in the debug
        // package, the Traditional Chinese face (it has 于, 年 … but not the simplified 请).
        val latin =
            com.tom_roush.pdfbox.pdmodel.PDDocument().use { probe ->
                NativeFontLibrary.available(app).firstOrNull { f ->
                    runCatching {
                        val font = NativeFontLibrary.open(app, f.id)!!.use { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(probe, it, true) }
                        NativePdfEditor.missing(font, "aZ2").isEmpty() && NativePdfEditor.missing(font, "请").isNotEmpty()
                    }.getOrDefault(false)
                }
            }
        assertTrue("a Latin-only built-in font is packaged", latin != null)
        val runs = NativeTextRuns.restyle(NativeTextRuns.initial(target.text), 0, target.text.length, NativeStyle(font = latin!!.id))
        val (output, report) = save(input, listOf(NativePdfEditor.Edit.of(target, runs)!!), "mixed-latin-font.pdf")
        val fonts = report.getJSONArray("edits").getJSONObject(0).getString("actualFont").split(",").filter { it.isNotBlank() }
        assertTrue("both fonts used: $fonts", fonts.distinct().size >= 2)
        
        assertEquals(read(input), read(output))
        assertEquals(before.size, objects(output).size)
    }
}
