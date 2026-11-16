package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.pdf.*
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Reads real-world PDFs pushed to /data/local/tmp/corpus (copied to files/corpus) and records how the editor sees
 * them: text objects or lines, characters per unit, editable share and the reasons. Writes files/corpus-out/probe.json.
 * Measurement only; it asserts nothing about the documents.
 */
class RealPdfProbeTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun probeCorpus() {
        val dir = File(app.filesDir, "corpus")
        val out = File(app.filesDir, "corpus-out").apply { mkdirs() }
        val report = JSONObject()
        for (file in dir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }) {
            val pages = JSONArray()
            runCatching {
                val (count, _) = runBlocking { NativePdfEditor.inspect(file, 0) }
                for (page in 0 until minOf(count, 2)) {
                    val t0 = System.nanoTime()
                    val objects = runBlocking { NativePdfEditor.inspect(file, page).second }.filter { it.text.isNotBlank() }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val reasons = objects.filter { !it.editable }.groupingBy { it.reason }.eachCount()
                    pages.put(
                        JSONObject()
                            .put("page", page)
                            .put("ms", ms)
                            .put("objects", objects.size)
                            .put("editable", objects.count { it.editable })
                            .put("chars", objects.sumOf { it.text.length })
                            .put("charsPerObject", if (objects.isEmpty()) 0.0 else objects.sumOf { it.text.length }.toDouble() / objects.size)
                            .put("reasons", JSONObject(reasons as Map<*, *>))
                            .put("sample", objects.take(12).joinToString("|") { it.text.take(12) })
                    )
                }
            }.onFailure { pages.put(JSONObject().put("error", it.toString())) }
            report.put(file.name, pages)
        }
        File(out, "probe.json").writeText(report.toString(1))
    }

    /** Watermark candidates on every real document; suggested ones removed into files/corpus-out/wm-*.pdf. */
    @Test
    fun watermarksOnRealDocuments() {
        val dir = File(app.filesDir, "corpus")
        val out = File(app.filesDir, "corpus-out").apply { mkdirs() }
        val report = JSONObject()
        for (file in dir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }) {
            val entry = JSONObject()
            runCatching {
                val t0 = System.nanoTime()
                val scan = PdfWatermarkObjects.scan(file)
                entry.put("scanMs", (System.nanoTime() - t0) / 1_000_000)
                entry.put(
                    "candidates",
                    JSONArray(scan.candidates.map { c ->
                        JSONObject().put("kind", c.kind.name).put("label", c.label).put("pages", c.pages.size).put("units", c.units.size)
                            .put("score", c.score).put("suggested", c.suggested).put("reasons", JSONArray(c.reasons))
                    }),
                )
                val chosen = scan.candidates.filter { it.suggested }.map { it.id }.toSet()
                if (chosen.isNotEmpty()) {
                    val t1 = System.nanoTime()
                    val removal = PdfWatermarkObjects.apply(file, File(out, "wm-" + file.name).apply { delete() }, chosen, null)
                    entry.put("removed", removal.units).put("applyMs", (System.nanoTime() - t1) / 1_000_000)
                    // What the user previews is what is exported, and the export really changed the page.
                    val page = scan.candidates.filter { it.suggested }.flatMap { it.pages }.min()
                    val preview = File(out, "preview-" + file.name).apply { delete() }
                    PdfWatermarkObjects.preview(file, preview, page, chosen)
                    val original = render(file, page)
                    val exported = render(File(out, "wm-" + file.name), page)
                    val previewed = render(preview, 0)
                    entry
                        .put("previewVsExportDiff", diff(previewed, exported))
                        .put("exportVsOriginalDiff", diff(exported, original))
                }
            }.onFailure { entry.put("error", it.toString()) }
            report.put(file.name, entry)
        }
        File(out, "watermarks.json").writeText(report.toString(1))
        val marked = setOf("camscanner-en.pdf", "camscanner-zh.pdf", "edge-report.pdf", "word-watermark.pdf", "wm-guide.pdf")
        val clean = setOf("word-plain.pdf", "wps-export.pdf", "arxiv-latex.pdf", "form-wrapped.pdf")
        for (name in report.keys()) {
            val e = report.getJSONObject(name)
            org.junit.Assert.assertFalse("$name: ${e.optString("error")}", e.has("error"))
            if (name in marked) {
                org.junit.Assert.assertTrue("$name: watermark found and removed", e.optInt("removed") > 0)
                org.junit.Assert.assertTrue("$name: preview equals export", e.getDouble("previewVsExportDiff") < .001)
                org.junit.Assert.assertTrue("$name: export differs from the input", e.getDouble("exportVsOriginalDiff") > .0005)
            }
            if (name in clean) org.junit.Assert.assertFalse("$name: nothing suggested on a clean document", e.has("removed"))
        }
    }

    /**
     * Inserting text into a full-width paragraph line must keep it inside its column (squeezed), or report the
     * overflow: it may never silently run into the gutter or the next column. Writes widen.json and widened-*.pdf.
     */
    @Test
    fun widenParagraphLines() {
        val dir = File(app.filesDir, "corpus")
        val out = File(app.filesDir, "corpus-out").apply { mkdirs() }
        val report = JSONObject()
        val problems = mutableListOf<String>()
        for (file in dir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }) {
            val entry = JSONObject()
            runCatching {
                val lines = runBlocking { NativePdfEditor.inspect(file, 0).second }.filter { it.units.isNotEmpty() }
                fun left(o: NativePdfEditor.TextObject) = o.units.minOf { it.left }
                fun right(o: NativePdfEditor.TextObject) = o.units.maxOf { it.right }
                // A full-width paragraph line: another line starts and ends where it does.
                val full =
                    lines.filter { o ->
                        o.editable && o.text.trim().length >= 16 &&
                            lines.any { p -> p !== o && abs(left(p) - left(o)) < .01f && abs(right(p) - right(o)) < .01f }
                    }
                val target = full.getOrNull(full.size / 2) ?: return@runCatching entry.put("skipped", "no full-width paragraph line").let { }
                val han = target.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
                val insert = if (han) "修订修订" else "EDIT EDIT"
                val at = target.text.length / 2
                val font =
                    runBlocking {
                        NativeEditSession(app, file).use { s ->
                            s.open()
                            if (s.missingInOriginal(target.key, insert).isEmpty()) null else NativeFontLibrary.substitute(app, target, insert)?.id
                        }
                    }
                val edits =
                    NativeEditOps.replace(emptyList(), lines.associateBy { it.key }, listOf(NativeSegment(target.key, at, at)), insert) {
                        font?.let { NativeStyle(font = it) }
                    }
                val output = File(out, "widened-" + file.name)
                val result =
                    runBlocking {
                        NativePdfEditor.apply(
                            app, file, output,
                            JSONObject().put("sourceSha256", PdfAssembly.sha256(file)).put("edits", JSONArray(edits.map { it.json() })),
                        )
                    }
                val outcome = result.getJSONArray("edits").getJSONObject(0)
                val expected = target.text.substring(0, at) + insert + target.text.substring(at)
                val after = runBlocking { NativePdfEditor.inspect(output, 0).second }
                val edited = after.firstOrNull { it.text.replace(" ", "") == expected.replace(" ", "") }
                val overflow = outcome.optBoolean("overflow")
                val grewBy = edited?.let { right(it) - right(target) } ?: Float.NaN
                entry.put("line", target.text).put("found", edited != null).put("rightBefore", right(target).toDouble())
                    .put("rightAfter", edited?.let { right(it).toDouble() }).put("grewBy", grewBy.toDouble())
                    .put("scale", outcome.optDouble("actualHorizontalScale")).put("overflow", overflow)
                if (edited == null) problems += "${file.name}: edited line not found"
                else if (grewBy > .004f && !overflow) problems += "${file.name}: grew ${"%.3f".format(grewBy)} of the page width without reporting overflow"
            }.onFailure { entry.put("error", it.toString()); problems += "${file.name}: $it" }
            report.put(file.name, entry)
        }
        File(out, "widen.json").writeText(report.toString(1))
        org.junit.Assert.assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun render(file: File, page: Int): android.graphics.Bitmap =
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                r.openPage(page).use { p ->
                    val bmp = android.graphics.Bitmap.createBitmap(p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    /** Share of pixels whose grey level differs by more than 4 (faint watermarks change pixels by only ~20). */
    private fun diff(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        fun grey(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10
        return pa.indices.count { kotlin.math.abs(grey(pa[it]) - grey(pb[it])) > 4 }.toDouble() / pa.size
    }

    /** Replaces [count] characters at [at] of [target] in [file] with [text] (a built-in font when needed), saves to [output]. */
    private fun editLine(file: File, output: File, lines: List<NativePdfEditor.TextObject>, target: NativePdfEditor.TextObject, at: Int, count: Int, text: String): String {
        val font =
            runBlocking {
                NativeEditSession(app, file).use { s ->
                    s.open()
                    if (s.missingInOriginal(target.key, text).isEmpty()) null else NativeFontLibrary.substitute(app, target, text)?.id
                }
            }
        val edits =
            NativeEditOps.replace(emptyList(), lines.associateBy { it.key }, listOf(NativeSegment(target.key, at, at + count)), text) {
                font?.let { NativeStyle(font = it) }
            }
        runBlocking {
            NativePdfEditor.apply(
                app, file, output,
                JSONObject().put("sourceSha256", PdfAssembly.sha256(file)).put("edits", JSONArray(edits.map { it.json() })),
            )
        }
        return target.text.substring(0, at) + text + target.text.substring(at + count)
    }

    /**
     * Persistent editing: edit a line, save, reopen the saved file, edit the same line again and another line, save
     * again. Both generations must read back exactly; the first edit leaves no ghost text; all other lines stay.
     */
    @Test
    fun reEditSavedDocuments() {
        val dir = File(app.filesDir, "corpus")
        val out = File(app.filesDir, "corpus-out").apply { mkdirs() }
        val report = JSONObject()
        val problems = mutableListOf<String>()
        fun squash(s: String) = s.replace(" ", "")
        for (file in dir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }) {
            val entry = JSONObject()
            runCatching {
                val original = runBlocking { NativePdfEditor.inspect(file, 0).second }
                val editable = original.filter { it.editable && it.text.trim().length >= 8 }
                if (editable.size < 2) return@runCatching entry.put("skipped", "fewer than two editable lines").let { }
                val a = editable[editable.size / 2]
                val b = editable.first { it !== a }
                val han = a.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
                val first = File(out, "reedit1-" + file.name)
                val once = editLine(file, first, original, a, a.text.length / 2, 2, if (han) "修订" else "EDIT")
                val reopened = runBlocking { NativePdfEditor.inspect(first, 0).second }
                val a2 = reopened.firstOrNull { squash(it.text) == squash(once) } ?: error("first edit not found after reopening")
                val b2 = reopened.firstOrNull { squash(it.text) == squash(b.text) } ?: error("second line not found after reopening")
                val second = File(out, "reedit2-" + file.name)
                // Same line again (characters before the first edit), then the other line, in two saves.
                val twice = editLine(first, second, reopened, a2, 1, 2, if (han) "再改" else "AGAIN")
                val reopened2 = runBlocking { NativePdfEditor.inspect(second, 0).second }
                val b3 = reopened2.firstOrNull { squash(it.text) == squash(b.text) } ?: error("second line lost after the second save")
                val third = File(out, "reedit3-" + file.name)
                val other = editLine(second, third, reopened2, b3, 0, 1, if (han) "新" else "N")
                val final = runBlocking { NativePdfEditor.inspect(third, 0).second }
                val texts = final.map { squash(it.text) }
                val expectA = squash(twice)
                val expectB = squash(other)
                val untouched = original.filter { it !== a && it !== b }.map { squash(it.text) }.sorted()
                val rest = texts.toMutableList().apply { remove(expectA); remove(expectB) }.sorted()
                entry.put("lineA", a.text).put("after", twice).put("lineB", b.text).put("afterB", other)
                    .put("foundA", expectA in texts).put("foundB", expectB in texts).put("othersUnchanged", rest == untouched)
                    .put("lines", JSONArray(listOf(original.size, reopened.size, reopened2.size, final.size)))
                if (expectA !in texts) problems += "${file.name}: re-edited line not found"
                if (expectB !in texts) problems += "${file.name}: second line edit not found"
                if (rest != untouched) problems += "${file.name}: other lines changed or ghost text: ${(rest - untouched.toSet()).take(3)}"
            }.onFailure { entry.put("error", it.toString()); problems += "${file.name}: $it" }
            report.put(file.name, entry)
        }
        File(out, "reedit.json").writeText(report.toString(1))
        org.junit.Assert.assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * On every real document: replace two characters in the middle of a mid-page line (with a built-in font when the
     * embedded subset lacks them), save, read back. Writes edit.json and the edited PDFs to files/corpus-out.
     */
    @Test
    fun editEachRealDocument() {
        val dir = File(app.filesDir, "corpus")
        val out = File(app.filesDir, "corpus-out").apply { mkdirs() }
        val report = JSONObject()
        for (file in dir.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }) {
            val entry = JSONObject()
            runCatching {
                val lines = runBlocking { NativePdfEditor.inspect(file, 0).second }
                val candidates = lines.filter { it.editable && it.text.trim().length >= 8 }
                val target = candidates.getOrNull(candidates.size / 2) ?: error("no editable line")
                // Geometry the line writer uses to decide how much the line may grow.
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { pdf ->
                    val model = PdfTextModel.read(pdf, 0)
                    val line = model.lines.firstOrNull { it.index == target.index } ?: model.lines.firstOrNull { it.text == target.text }
                    if (line != null) {
                        val g = line.glyphs
                        entry.put(
                            "geometry",
                            JSONObject()
                                .put("room", model.room(line).toDouble())
                                .put("drawnSize", g.last().drawnSize.toDouble())
                                .put("first", "${g.first().origin.x},${g.first().origin.y}")
                                .put("last", "${g.last().origin.x},${g.last().origin.y}")
                                .put("lastAdvance", model.advanceOnPage(g.last()).toDouble())
                                .put("direction", "${g.first().direction.x},${g.first().direction.y}")
                                .put("block", line.block)
                                .put("blockLines", model.lines.count { it.block == line.block })
                                .put("lineCount", model.lines.size),
                        )
                    }
                }
                val at = target.text.length / 2
                val replacement = if (target.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "修订" else "EDIT"
                val font =
                    runBlocking {
                        NativeEditSession(app, file).use { s ->
                            s.open()
                            if (s.missingInOriginal(target.key, replacement).isEmpty()) null
                            else NativeFontLibrary.substitute(app, target, replacement)?.id
                        }
                    }
                val edits =
                    NativeEditOps.replace(
                        emptyList(),
                        lines.associateBy { it.key },
                        listOf(NativeSegment(target.key, at, at + 2)),
                        replacement,
                    ) { font?.let { NativeStyle(font = it) } }
                val expected = target.text.substring(0, at) + replacement + target.text.substring(at + 2)
                val output = File(out, "edited-" + file.name)
                val t0 = System.nanoTime()
                val result =
                    runBlocking {
                        NativePdfEditor.apply(
                            app,
                            file,
                            output,
                            JSONObject().put("sourceSha256", PdfAssembly.sha256(file)).put("edits", JSONArray(edits.map { it.json() })),
                        )
                    }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val after = runBlocking { NativePdfEditor.inspect(output, 0).second }
                val edited = after.firstOrNull { it.text.replace(" ", "") == expected.replace(" ", "") }
                val others = lines.filter { it.key != target.key }.map { it.text }
                val othersAfter = after.filter { it !== edited }.map { it.text }
                entry
                    .put("line", target.text)
                    .put("expected", expected)
                    .put("found", edited != null)
                    .put("font", font ?: "ORIGINAL")
                    .put("othersUnchanged", others.sorted() == othersAfter.sorted())
                    .put("missingOthers", JSONArray(others.filter { it !in othersAfter }.take(5)))
                    .put("saveMs", ms)
                    .put("report", result.getJSONArray("edits").getJSONObject(0))
            }.onFailure { entry.put("error", it.toString()) }
            report.put(file.name, entry)
        }
        File(out, "edit.json").writeText(report.toString(1))
    }
}
