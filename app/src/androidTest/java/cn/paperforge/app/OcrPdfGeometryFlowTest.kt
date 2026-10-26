package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Known, corrected OCR geometry isolates PDF writing from recognition quality. */
class OcrPdfGeometryFlowTest {
    @Test
    fun scanAndMixedRotatedPdfKeepUnicodeSearchAndOriginalFonts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "ocr-pdf-geometry-proof").apply { mkdirs() }
        fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
            OcrLine(
                text,
                listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b)),
                corrected = true,
            )
        val text = "用户 第二页 ００１２３ ①"
        val horizontal = line(text, .12f, .25f, .88f, .31f)
        val vertical =
            OcrLine(
                "旋转 页 00123",
                listOf(PointN(.7f, .2f), PointN(.7f, .75f), PointN(.64f, .75f), PointN(.64f, .2f)),
                corrected = true,
            )
        val horizontalLayout = OcrLayout(800, 1100, listOf(horizontal), sourceRevision = 0)
        val verticalLayout = OcrLayout(800, 1100, listOf(vertical), sourceRevision = 0)
        val engine =
            object : OcrEngine {
                override val id = "pdf-geometry-fixture"

                override suspend fun layout(page: PageRow, chinese: Boolean, options: OcrOptions) =
                    if (page.position == 0) horizontalLayout else verticalLayout

                override suspend fun recognizeBitmap(
                    source: Bitmap,
                    chinese: Boolean,
                    options: OcrOptions,
                ) =
                    OcrLayout(
                        source.width,
                        source.height,
                        listOf(line("第二页 00123", .15f, .35f, .8f, .43f)),
                    )
            }
        val typeface = Typeface.createFromAsset(app.assets, "fonts/PaperForgeCJK-Regular.ttf")
        val original = File(folder, "scan.png")
        val verticalSource = File(folder, "scan-vertical.png")
        fun drawSource(line: OcrLine, output: File, vertical: Boolean) {
            val bitmap = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val width = if (vertical) 605f else 608f
            val height = if (vertical) 48f else 66f
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    this.typeface = typeface
                    textSize = 100f
                }
            val initial = paint.fontMetrics
            paint.textSize = height / (initial.descent - initial.ascent) * 100f
            paint.textScaleX = width / paint.measureText(line.text)
            val canvas = Canvas(bitmap)
            canvas.translate(line.corners[0].x * 800, line.corners[0].y * 1100)
            if (vertical) canvas.rotate(90f)
            canvas.drawText(line.text, 0f, -paint.fontMetrics.ascent, paint)
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        drawSource(horizontal, original, false)
        drawSource(vertical, verticalSource, true)
        val pages =
            listOf(
                PageRow(
                    "horizontal",
                    "fixture",
                    0,
                    original.path,
                    filter = "ORIGINAL",
                    ocrLayout = horizontalLayout.encode(),
                ),
                PageRow(
                    "vertical",
                    "fixture",
                    1,
                    verticalSource.path,
                    filter = "ORIGINAL",
                    ocrLayout = verticalLayout.encode(),
                ),
            )
        val scan = File(folder, "scan-searchable.pdf")
        PdfEngine.scan(
            pages,
            scan,
            context = app,
            searchable = true,
            lossless = true,
            ocrEngine = engine,
        )
        val nativeText = ReaderDocument.text(scan, 0).joinToString("\n") { it.text }
        assertTrue(nativeText, nativeText.contains(text))
        val hit = PdfSearch.find(scan, "00123").first { it.page == 0 }
        assertTrue(hit.left in .1f..0.9f && hit.top in .22f..0.34f)
        val rotatedHit = PdfSearch.find(scan, "00123").single { it.page == 1 }
        assertTrue(rotatedHit.left in .62f..0.74f && rotatedHit.top in .18f..0.77f)
        assertTrue(
            "Rotated text is a narrow vertical region",
            rotatedHit.bottom - rotatedHit.top > rotatedHit.right - rotatedHit.left,
        )
        val selection = ReaderDocument.text(scan, 1).first { it.text.contains("00123") }
        assertEquals(rotatedHit.left, selection.units.takeLast(5).minOf { it.left }, .005f)
        val mixed = File(folder, "mixed-original.pdf")
        PDDocument().use { pdf ->
            val page =
                PDPage(PDRectangle(500f, 700f)).apply {
                    cropBox = PDRectangle(30f, 40f, 400f, 600f)
                    rotation = 90
                }
            pdf.addPage(page)
            val font = PdfTextLayer.font(app, pdf)
            val display = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            display.eraseColor(Color.WHITE)
            val bodyPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 100f
                    this.typeface = typeface
                }
            val metrics = bodyPaint.fontMetrics
            bodyPaint.textSize = 64f / (metrics.descent - metrics.ascent) * 100f
            bodyPaint.textScaleX = 780f / bodyPaint.measureText("第二页 00123")
            Canvas(display)
                .drawText("第二页 00123", 180f, 280f - bodyPaint.fontMetrics.ascent, bodyPaint)
            val raw =
                Bitmap.createBitmap(
                    display,
                    0,
                    0,
                    display.width,
                    display.height,
                    Matrix().apply { postRotate(-90f) },
                    true,
                )
            PDPageContentStream(pdf, page).use { stream ->
                stream.drawImage(LosslessFactory.createFromImage(pdf, raw), 30f, 40f, 400f, 600f)
                stream.beginText()
                stream.setFont(font, 18f)
                stream.newLineAtOffset(70f, 570f)
                stream.showText("原生文字甲乙")
                stream.endText()
            }
            raw.recycle()
            display.recycle()
            pdf.save(mixed)
        }
        fun nativeCmap(file: File) =
            PDDocument.load(file).use { pdf ->
                val stream =
                    pdf.getPage(0)
                        .resources
                        .getFont(COSName.getPDFName("F1"))
                        .cosObject
                        .getDictionaryObject(COSName.TO_UNICODE)
                        as com.tom_roush.pdfbox.cos.COSStream
                stream.createInputStream().use { it.readBytes() }
            }
        val originalHash = PdfAssembly.sha256(mixed)
        val cmap = nativeCmap(mixed)
        val searchable = File(folder, "mixed-searchable.pdf")
        PdfSearchable.convert(app, mixed, searchable, engine)
        assertEquals(originalHash, PdfAssembly.sha256(mixed))
        assertArrayEquals(cmap, nativeCmap(searchable))
        val mixedText = ReaderDocument.text(searchable, 0).joinToString("\n") { it.text }
        assertTrue(mixedText, mixedText.contains("原生文字甲乙"))
        assertTrue(mixedText, mixedText.contains("第二页 00123"))
        val mixedHit = PdfSearch.find(searchable, "00123").single()
        assertTrue(
            "Displayed rotated/cropped hit $mixedHit",
            mixedHit.left in .1f..0.85f && mixedHit.top in .30f..0.46f,
        )
        val nativeHit = PdfSearch.find(searchable, "原生文字").single()
        assertTrue(nativeHit.left > .7f && nativeHit.top < .2f)
        PDDocument.load(searchable).use { pdf ->
            assertEquals(1, NativeTextCoverage.regions(pdf, 0).count { it.text.contains("第二页") })
        }
        fun json(hit: PdfSearch.Hit) =
            JSONObject()
                .put("page", hit.page)
                .put("box", JSONArray(listOf(hit.left, hit.top, hit.right, hit.bottom)))
        File(folder, "proof.json")
            .writeText(
                JSONObject()
                    .put("text", nativeText)
                    .put("horizontal", json(hit))
                    .put("vertical", json(rotatedHit))
                    .put("mixed", json(mixedHit))
                    .put("native", json(nativeHit))
                    .put("originalFontCmapUnchanged", true)
                    .put("sourceUnchanged", true)
                    .toString(2)
            )
    }
}
