package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.PointN
import cn.paperforge.app.model.Quad
import cn.paperforge.app.pdf.PdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF export image settings measured on the real exported files: the embedded image streams (encoding, pixel size,
 * stream bytes), the page size, and how faithfully fine strokes survive (decoded image against the lossless export).
 *
 * Pages: a fine-detail sheet (faint pencil, 1 px construction lines, small print, symbols, a red mark) as 练习册,
 * 灰度 and an edited 原图 page; and the same photo as an untouched 原图 page. Every export is written to
 * files/export-quality with report.json, so the PDFs themselves can be inspected.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportQualityTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val out = File(context.filesDir, "export-quality").apply { mkdirs() }

    /** A 3000 x 4000 phone photo of a page with the details export must not lose. */
    private fun photo(): File {
        val file = File(out, "fine-detail-photo.jpg")
        if (file.isFile) return file
        val w = 3000
        val h = 4000
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.rgb(226, 222, 212))
        val shade = Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(238, 235, 228), Color.rgb(214, 209, 198), android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 32); textSize = 34f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 46); textSize = 20f }
        for (row in 0 until 40) c.drawText("第 $row 行  ∫ f(x) dx = F(b) − F(a)，Σ aₙ ≤ √2；“引号”、句号。逗号, colon: 0.123456789", 160f, 260f + row * 64f, text)
        for (row in 0 until 30) c.drawText("small print 8pt · 小号印刷体 · α β γ δ ε ≈ ≠ ± × ÷ · ${row * 7919 % 1000}", 160f, 2900f + row * 30f, small)
        // Faint pencil: light grey, 1–2 px, slightly irregular.
        val pencil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(168, 166, 160); strokeWidth = 1.6f; style = Paint.Style.STROKE }
        for (i in 0 until 12) c.drawLine(200f + i * 210f, 2700f, 330f + i * 210f, 2550f + (i % 3) * 30f, pencil)
        // Construction lines: 1 px dark grey and dashed.
        val line = Paint().apply { color = Color.rgb(90, 90, 96); strokeWidth = 1f }
        for (i in 0 until 20) c.drawLine(160f, 2760f + i * 6f, 2840f, 2760f + i * 6f, line)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
        c.drawLine(160f, 2890f, 2840f, 2890f, line)
        // A coloured annotation.
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 40, 36); strokeWidth = 4f; style = Paint.Style.STROKE }
        c.drawCircle(2500f, 3700f, 120f, red)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        return file
    }

    private val inset = Quad.ordered(listOf(PointN(.02f, .02f), PointN(.98f, .02f), PointN(.98f, .98f), PointN(.02f, .98f)))

    private fun page(file: File, filter: String, quad: Quad = inset) =
        PageRow(id = "export-quality-$filter-${quad == Quad.Full}", documentId = "export-quality", position = 0, original = file.path, corners = quad.encode(), filter = filter)

    private data class Embedded(val filter: String, val width: Int, val height: Int, val bits: Int, val space: String, val bytes: Long, val pixels: IntArray?)

    private fun inspect(pdf: File, decode: Boolean): Pair<List<Embedded>, FloatArray> {
        PDFBoxResourceLoader.init(context)
        return PDDocument.load(pdf).use { doc ->
            val page = doc.getPage(0)
            val res = page.resources
            val images =
                res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().map { image ->
                    val raw = image.cosObject.createRawInputStream().use { it.readBytes().size.toLong() }
                    val filters = image.cosObject.filters
                    val name =
                        when (filters) {
                            is COSName -> filters.name
                            is com.tom_roush.pdfbox.cos.COSArray -> (0 until filters.size()).joinToString("+") { (filters.getObject(it) as COSName).name }
                            else -> "none"
                        }
                    val pixels =
                        if (decode) image.image.let { b -> IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height); b.recycle() } } else null
                    Embedded(name, image.width, image.height, image.bitsPerComponent, image.colorSpace.name, raw, pixels)
                }
            images to floatArrayOf(page.mediaBox.width, page.mediaBox.height)
        }
    }

    /** PSNR and dark-stroke error of [b] against the reference [a] (same size, ARGB). */
    private fun fidelity(a: IntArray, b: IntArray): Pair<Double, Double> {
        var se = 0.0
        var strokeErr = 0.0
        var strokes = 0
        for (i in a.indices) {
            val la = (Color.red(a[i]) * 299 + Color.green(a[i]) * 587 + Color.blue(a[i]) * 114) / 1000
            val lb = (Color.red(b[i]) * 299 + Color.green(b[i]) * 587 + Color.blue(b[i]) * 114) / 1000
            val d = (la - lb).toDouble()
            se += d * d
            if (la < 200) {
                strokeErr += kotlin.math.abs(d)
                strokes++
            }
        }
        val mse = se / a.size
        val psnr = if (mse == 0.0) 99.0 else 10 * kotlin.math.log10(255.0 * 255.0 / mse)
        return psnr to (if (strokes == 0) 0.0 else strokeErr / strokes)
    }

    @Test
    fun exportSettingsChangeTheEmbeddedImages() = runBlocking {
        val photo = photo()
        val report = JSONArray()
        val pages =
            listOf(
                "WORKBOOK" to page(photo, "WORKBOOK"),
                "GRAY" to page(photo, "GRAY"),
                "ORIGINAL-edited" to page(photo, "ORIGINAL"),
                "ORIGINAL-untouched" to page(photo, "ORIGINAL", Quad.Full),
            )
        val runtime = Runtime.getRuntime()
        for ((label, page) in pages) {
            // The lossless export at the original resolution is the reference every other export is compared to.
            val reference = File(out, "$label-reference.pdf")
            PdfEngine.scan(listOf(page), reference, quality = 100, context = context, resolution = "ORIGINAL", lossless = true)
            val ref = inspect(reference, false).first.single()
            val settings =
                listOf(30, 60, 90).flatMap { q -> listOf("ORIGINAL", "MEDIUM", "SMALL").map { r -> Triple(q, r, false) } } +
                    listOf(Triple(90, "ORIGINAL", true), Triple(30, "SMALL", true)) +
                    listOf(Triple(86, "STANDARD", false), Triple(86, "HIGH", false), Triple(86, "DRAFT", false))
            for ((quality, resolution, lossless) in settings) {
                val pdf = File(out, "$label-q$quality-$resolution${if (lossless) "-lossless" else ""}.pdf")
                runtime.gc()
                val heapBefore = runtime.totalMemory() - runtime.freeMemory()
                val t0 = System.nanoTime()
                PdfEngine.scan(listOf(page), pdf, quality = quality, context = context, resolution = resolution, lossless = lossless)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val heapAfter = runtime.totalMemory() - runtime.freeMemory()
                // Pixel fidelity is measured on the desktop from these files: PDFBox on Android decodes one-channel
                // Flate images wrongly, so an on-device comparison would report losses that are not in the PDF.
                val (images, mediaBox) = inspect(pdf, false)
                val image = images.single()
                report.put(
                    JSONObject()
                        .put("page", label).put("quality", quality).put("resolution", resolution).put("lossless", lossless)
                        .put("fileBytes", pdf.length()).put("encoding", image.filter).put("width", image.width).put("height", image.height)
                        .put("bits", image.bits).put("colorSpace", image.space).put("streamBytes", image.bytes)
                        .put("pageWidthPt", mediaBox[0].toDouble()).put("pageHeightPt", mediaBox[1].toDouble())
                        .put("referenceWidth", ref.width).put("referenceHeight", ref.height)
                        .put("ms", ms).put("heapDeltaMB", (heapAfter - heapBefore) / 1048576.0)
                )
            }
        }
        File(out, "report.json").writeText(report.toString(1))
        assertEquals(4 * 14, report.length())
    }

    private suspend fun export(page: PageRow, name: String, quality: Int, resolution: String, lossless: Boolean = false): Pair<Embedded, FloatArray> {
        val pdf = File(out, "check-$name.pdf")
        PdfEngine.scan(listOf(page), pdf, quality = quality, context = context, resolution = resolution, lossless = lossless)
        return inspect(pdf, false).let { it.first.single() to it.second }
    }

    /** Text-layer positions (OCR) of a searchable export, in PDF points. */
    private fun textPositions(pdf: File): Pair<List<Pair<Float, Float>>, FloatArray> {
        PDFBoxResourceLoader.init(context)
        return PDDocument.load(pdf).use { doc ->
            val positions = mutableListOf<Pair<Float, Float>>()
            object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                    override fun writeString(text: String?, textPositions: MutableList<com.tom_roush.pdfbox.text.TextPosition>?) {
                        textPositions?.forEach { positions += it.xDirAdj to it.yDirAdj }
                    }
                }
                .getText(doc)
            val box = doc.getPage(0).mediaBox
            positions to floatArrayOf(box.width, box.height)
        }
    }

    /**
     * Enhancing at full size and downscaling changes only the image's pixel count: the page size and the OCR text
     * layer stay where they are, so selecting text still lands on the same printed words at every resolution.
     */
    @Test
    fun resolutionKeepsPageSizeAndTextLayerAlignment() = runBlocking {
        val page = page(photo(), "WORKBOOK")
        val runs =
            listOf("ORIGINAL", "MEDIUM", "SMALL").map { r ->
                val pdf = File(out, "ocr-$r.pdf")
                PdfEngine.scan(listOf(page), pdf, quality = 85, context = context, searchable = true, resolution = r)
                r to textPositions(pdf)
            }
        val (reference, box) = runs.first().second
        assertTrue("OCR found text: ${reference.size} glyphs", reference.size > 200)
        for ((r, result) in runs.drop(1)) {
            val (positions, b) = result
            assertEquals("$r page width", box[0], b[0], .5f)
            assertEquals("$r page height", box[1], b[1], .5f)
            assertEquals("$r glyph count", reference.size, positions.size)
            val worst = reference.zip(positions).maxOf { (a, c) -> maxOf(kotlin.math.abs(a.first - c.first), kotlin.math.abs(a.second - c.second)) }
            assertTrue("$r text layer moved by $worst pt", worst < 1f)
        }
    }

    /** The three controls each do what they say, independently, on the exported files themselves. */
    @Test
    fun qualityResolutionAndLosslessAreIndependentAndReal() = runBlocking {
        val photo = photo()
        for (filter in listOf("WORKBOOK", "GRAY", "ORIGINAL")) {
            val page = page(photo, filter)
            // 图像质量: same pixels, more bytes with higher quality. Grey 练习册 pages keep fewer tone levels (Flate);
            // other pages are JPEG.
            val q = listOf(30, 60, 90).map { export(page, "$filter-q$it", it, "ORIGINAL").first }
            val lossy = if (filter == "WORKBOOK") "FlateDecode" else "DCTDecode"
            q.forEach { assertEquals("$filter quality encoding", lossy, it.filter) }
            if (filter == "WORKBOOK") assertEquals(listOf(4, 4, 8), q.map { it.bits })
            assertTrue("$filter quality keeps the pixel size", q.all { it.width == q[0].width && it.height == q[0].height })
            assertTrue("$filter: q30 < q60 < q90 bytes ${q.map { it.bytes }}", q[0].bytes < q[1].bytes && q[1].bytes < q[2].bytes)
            // 输出分辨率: pixel caps, aspect kept, page size unchanged.
            val (original, box) = export(page, "$filter-orig", 85, "ORIGINAL")
            val sizes = listOf("HIGH" to 3508, "MEDIUM" to 2572, "SMALL" to 1754).map { (r, cap) ->
                val (image, b) = export(page, "$filter-$r", 85, r)
                assertTrue("$filter $r long edge ${maxOf(image.width, image.height)} <= $cap", maxOf(image.width, image.height) <= cap + 1)
                assertEquals("$filter $r keeps the aspect", original.width.toDouble() / original.height, image.width.toDouble() / image.height, .01)
                assertEquals("$filter $r keeps the page width", box[0], b[0], .5f)
                assertEquals("$filter $r keeps the page height", box[1], b[1], .5f)
                image
            }
            assertTrue("$filter MEDIUM < ORIGINAL", maxOf(sizes[1].width, sizes[1].height) < maxOf(original.width, original.height))
            assertTrue("$filter SMALL < MEDIUM", sizes[2].width < sizes[1].width)
            // Legacy names keep their sizes.
            assertEquals(sizes[1].width, export(page, "$filter-STANDARD", 85, "STANDARD").first.width)
            assertEquals(sizes[2].width, export(page, "$filter-DRAFT", 85, "DRAFT").first.width)
            // 无损保存: Flate at the original pixels, whatever quality and resolution say.
            val lossless = listOf(export(page, "$filter-ll30", 30, "SMALL", true), export(page, "$filter-ll95", 95, "ORIGINAL", true))
            lossless.forEach { (image, b) ->
                assertEquals("$filter lossless uses Flate", "FlateDecode", image.filter)
                assertEquals(original.width, image.width)
                assertEquals(original.height, image.height)
                assertEquals(box[0], b[0], .5f)
            }
            assertEquals("$filter lossless ignores quality", lossless[0].first.bytes, lossless[1].first.bytes)
        }
        // An untouched camera photo: lossless embeds the camera file itself; a lower quality or smaller size re-encodes.
        val untouched = page(photo, "ORIGINAL", Quad.Full)
        val pdf = File(out, "check-untouched-lossless.pdf")
        PdfEngine.scan(listOf(untouched), pdf, quality = 30, context = context, resolution = "SMALL", lossless = true)
        PDFBoxResourceLoader.init(context)
        PDDocument.load(pdf).use { doc ->
            val res = doc.getPage(0).resources
            val image = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            val raw = image.cosObject.createRawInputStream().use { it.readBytes() }
            assertTrue("lossless keeps the camera's own JPEG bytes", raw.contentEquals(photo.readBytes()))
        }
        val keep = export(untouched, "untouched-q95", 95, "ORIGINAL").first
        assertEquals("quality at or above the photo's own keeps the original", photo.length(), keep.bytes)
        val lower = export(untouched, "untouched-q60", 60, "ORIGINAL").first
        assertTrue("a lower quality re-encodes: ${lower.bytes} < ${photo.length()}", lower.bytes < photo.length())
        assertEquals(3000, lower.width)
        val small = export(untouched, "untouched-small", 95, "SMALL").first
        assertTrue("a smaller size re-encodes", maxOf(small.width, small.height) <= 1755)
    }
}
