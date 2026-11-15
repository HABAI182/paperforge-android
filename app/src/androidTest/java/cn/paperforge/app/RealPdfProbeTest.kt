package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.pdf.*
import java.io.File
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
        val marked = setOf("camscanner-en.pdf", "camscanner-zh.pdf", "edge-report.pdf", "word-watermark.pdf")
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

    /** Share of pixels whose grey level differs by more than 24. */
    private fun diff(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        fun grey(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10
        return pa.indices.count { kotlin.math.abs(grey(pa[it]) - grey(pb[it])) > 24 }.toDouble() / pa.size
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
