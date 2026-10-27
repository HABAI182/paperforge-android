package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class OcrWritingFlowTest {
    @Test
    fun realVerticalModelOrderFrozenRequestNativeSelectionAndSearchableGeometry() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val folder =
                File(app.filesDir, "ocr-writing-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val oldOrder = UserSettings.string("ocrOrder", "AUTO")
            val bitmap =
                Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
            try {
                val font = Typeface.createFromAsset(app.assets, "fonts/PaperForgeCJK-Regular.ttf")
                val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 82f
                        typeface = font
                    }
                val canvas = Canvas(bitmap)
                listOf("紙匠掃描測試", "保留原始文件", "閱讀順序正確").forEachIndexed { col, text ->
                    text.forEachIndexed { row, c ->
                        canvas.drawText(c.toString(), 600f - col * 220, 150f + row * 110, paint)
                    }
                }
                File(folder, "source.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val engine = app.ocrEngine.snapshot()
                assertTrue(
                    "Preinstall the documented Paddle v5 Mobile pack",
                    engine.id.startsWith("paddle:"),
                )
                val options = OcrOptions(OcrMode.FAST, false, OcrOrder.AUTO)
                val result = engine.recognizeBitmap(bitmap, true, options)
                assertEquals(3, result.lines.size)
                assertTrue(result.lines.first().text.endsWith("掃描測試"))
                assertEquals("保留原始文件", result.lines[1].text)
                assertEquals("閱讀順序正確", result.lines.last().text)
                assertTrue(
                    result.lines.zipWithNext().all { (a, b) ->
                        a.corners.map { it.x }.average() > b.corners.map { it.x }.average()
                    }
                )
                val raw = JSONObject(result.rawRecognition!!).getJSONArray("lines")
                assertEquals(
                    (0 until raw.length()).map { raw.getJSONObject(it).getString("text") }.sorted(),
                    result.lines.map { it.text }.sorted(),
                )
                result.lines.forEach { line ->
                    val p = line.corners
                    assertTrue((p[1].y - p[0].y) * 1000 > (p[1].x - p[0].x) * 800)
                    assertTrue(
                        (0 until raw.length())
                            .map { raw.getJSONObject(it) }
                            .any { it.getString("text") == line.text }
                    )
                }
                val frozen =
                    OcrRequest(
                        engine.id,
                        true,
                        OcrOptions(OcrMode.FAST, false, OcrOrder.VERTICAL_LTR),
                    )
                UserSettings.set("ocrOrder", "ROWS")
                val decoded = OcrRequest.decode(frozen.json(), engine.id)
                assertEquals(OcrOrder.VERTICAL_LTR, decoded.options.order)
                assertEquals(
                    OcrOrder.AUTO,
                    OcrRequest.decode(JSONObject().put("engine", engine.id), engine.id)
                        .options
                        .order,
                )
                val ltr = decoded.bind(app.ocrEngine).recognizeBitmap(bitmap)
                assertEquals("閱讀順序正確", ltr.lines.first().text)
                assertEquals(decoded.signature, ltr.requestSignature)
                val bundled =
                    BundledOcrEngine()
                        .recognizeBitmap(
                            bitmap,
                            true,
                            OcrOptions(OcrMode.BALANCED, false, OcrOrder.AUTO),
                        )
                assertTrue(OcrReadingOrder.vertical(bundled))
                assertEquals(1, bundled.attempts.size)
                val source = File(folder, "source.pdf")
                val output = File(folder, "searchable.pdf")
                PDDocument().use { pdf ->
                    val page = PDPage(PDRectangle(800f, 1000f))
                    pdf.addPage(page)
                    val image = LosslessFactory.createFromImage(pdf, bitmap)
                    PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, 800f, 1000f) }
                    pdf.save(source)
                }
                val hash = PdfAssembly.sha256(source)
                val mapping = PdfTextLayer.Mapping()
                PDDocument.load(source).use { pdf ->
                    PdfTextLayer.append(
                        pdf,
                        pdf.getPage(0),
                        PdfTextLayer.font(app, pdf),
                        result,
                        0f,
                        0f,
                        800f,
                        1000f,
                        mapping,
                    )
                    pdf.save(output)
                }
                mapping.finish(output)
                val blocks = ReaderDocument.text(output, 0)
                val selected = ReaderSelectionPage(0, blocks, 0)
                assertTrue(selected.ordered.first().text.endsWith("掃描測試"))
                assertTrue(selected.ordered.last().text.endsWith("順序正確"))
                val hit = PdfSearch.find(output, result.lines.first().text).single()
                assertTrue(hit.bottom - hit.top > (hit.right - hit.left) * 2)
                assertEquals(hash, PdfAssembly.sha256(source))
                fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
                    OcrLine(text, listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b)))
                val columns =
                    OcrLayout(
                        1000,
                        1000,
                        buildList {
                            add(line("TITLE", .02f, .02f, .98f, .07f))
                            repeat(3) { row ->
                                repeat(3) { col ->
                                    add(
                                        line(
                                            "${col+1}:${row+1}",
                                            .03f + col * .33f,
                                            .15f + row * .1f,
                                            .28f + col * .33f,
                                            .19f + row * .1f,
                                        )
                                    )
                                }
                            }
                            add(line("FOOTER", .02f, .8f, .98f, .85f))
                        },
                    )
                val ordered = OcrReadingOrder.arrange(columns, OcrOrder.AUTO)
                assertEquals(
                    listOf(
                        "TITLE",
                        "1:1",
                        "1:2",
                        "1:3",
                        "2:1",
                        "2:2",
                        "2:3",
                        "3:1",
                        "3:2",
                        "3:3",
                        "FOOTER",
                    ),
                    ordered.lines.map { it.text },
                )
                assertEquals(
                    columns.lines.size,
                    OcrReadingOrder.arrange(columns, OcrOrder.COLUMNS_RTL).lines.size,
                )
                File(folder, "result.json").writeText(result.encode())
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put(
                                "purpose",
                                "authored real-model writing-order and PDF geometry sample",
                            )
                            .put("engine", engine.id)
                            .put("sourceSha256", hash)
                            .put("sourceUnchanged", true)
                            .put("rawTextPreserved", true)
                            .put("frozenOrder", decoded.options.order.name)
                            .put("bundledVerticalBalancedNativePasses", bundled.attempts.size)
                            .put(
                                "nativeSelectionOrder",
                                JSONArray(selected.ordered.map { it.text }),
                            )
                            .put("threeColumnOrder", JSONArray(ordered.lines.map { it.text }))
                            .put(
                                "searchRegion",
                                JSONArray(listOf(hit.left, hit.top, hit.right, hit.bottom)),
                            )
                            .put("result", JSONObject(result.encode()))
                            .toString(2)
                    )
                android.util.Log.i("OcrWritingFlowTest", "Proof: ${File(folder,"proof.json").path}")
            } finally {
                bitmap.recycle()
                UserSettings.set("ocrOrder", oldOrder)
            }
        }
}
