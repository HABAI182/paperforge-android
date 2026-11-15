package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Watermark detection and removal on the generated set. Writes candidates, timings and outputs to files/wm-out for
 * scoring against truth.json with an independent parser; asserts the safety properties here.
 */
class WatermarkFlowTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val out = File(app.filesDir, "wm-out").apply { mkdirs() }

    private fun sample(name: String) =
        File(out, "src-$name").also { f ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("watermark/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        }

    private val structural = listOf("wm-diagonal.pdf", "wm-tiled.pdf", "wm-acrobat.pdf", "wm-image.pdf", "wm-layer-annot.pdf", "neg-furniture.pdf")

    @Test
    fun structuralCandidatesAndRemoval() {
        val report = JSONObject()
        for (name in structural) {
            val input = sample(name)
            val t0 = System.nanoTime()
            val scan = PdfWatermarkObjects.scan(input)
            val scanMs = (System.nanoTime() - t0) / 1_000_000
            val entry =
                JSONObject()
                    .put("scanMs", scanMs)
                    .put(
                        "candidates",
                        JSONArray(
                            scan.candidates.map { c ->
                                JSONObject()
                                    .put("id", c.id)
                                    .put("kind", c.kind.name)
                                    .put("label", c.label)
                                    .put("pages", JSONArray(c.pages.toList()))
                                    .put("units", c.units.size)
                                    .put("score", c.score)
                                    .put("suggested", c.suggested)
                                    .put("reasons", JSONArray(c.reasons))
                            }
                        ),
                    )
            val chosen = scan.candidates.filter { it.suggested }.map { it.id }.toSet()
            if (chosen.isNotEmpty()) {
                val output = File(out, "clean-$name")
                val t1 = System.nanoTime()
                val removal = PdfWatermarkObjects.apply(input, output, chosen, null)
                entry.put("applyMs", (System.nanoTime() - t1) / 1_000_000).put("removed", removal.units)
                PDDocument.load(output).use { a ->
                    PDDocument.load(input).use { b ->
                        assertEquals(b.numberOfPages, a.numberOfPages)
                        for (i in 0 until a.numberOfPages) {
                            assertEquals(b.getPage(i).mediaBox.toString(), a.getPage(i).mediaBox.toString())
                            // Fonts are kept (no resource is dropped).
                            assertEquals(b.getPage(i).resources.fontNames.count(), a.getPage(i).resources.fontNames.count())
                        }
                    }
                }
                // Removal is complete: nothing left to suggest.
                assertTrue(name, PdfWatermarkObjects.scan(output).candidates.none { it.suggested })
            }
            report.put(name, entry)
        }
        File(out, "structural.json").writeText(report.toString(1))
        // The negative file has nothing suggested.
        assertTrue(report.getJSONObject("neg-furniture.pdf").getJSONArray("candidates").let { a -> (0 until a.length()).none { a.getJSONObject(it).getBoolean("suggested") } })
    }

    @Test
    fun scannedOverlaysAreRestoredInTheImagesOnly() {
        val report = JSONObject()
        for ((name, region) in listOf("scan-notes-gray.pdf" to null, "scan-drawing-red.pdf" to null, "scan-single-red.pdf" to floatArrayOf(.05f, .2f, .95f, .9f))) {
            val input = sample(name)
            val output = File(out, "clean-$name")
            val rt = Runtime.getRuntime()
            val before = rt.totalMemory() - rt.freeMemory()
            val t0 = System.nanoTime()
            val r = ScanWatermarkRestore.apply(input, output, null, region, 0)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val after = rt.totalMemory() - rt.freeMemory()
            report.put(
                name,
                JSONObject().put("ms", ms).put("pages", r.pages).put("coverage", r.coverage).put("unrecoverable", r.unrecoverable)
                    .put("heapDeltaMb", (after - before) / 1e6).put("skipped", JSONArray(r.skipped))
                    .put("stagesMs", JSONObject(r.millis as Map<*, *>)),
            )
            assertTrue(r.pages >= 1)
            // The OCR text layer is untouched.
            val a = runCatching { NativePdfEditor.objects(PDDocument.load(input), 0) }.getOrDefault(emptyList())
            val b = runCatching { NativePdfEditor.objects(PDDocument.load(output), 0) }.getOrDefault(emptyList())
            assertEquals(a.map { it.text }, b.map { it.text })
        }
        File(out, "scan.json").writeText(report.toString(1))
    }
}
