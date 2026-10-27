package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTextPolicyFlowTest {
    @Test
    fun shortNativeMixedImageBrokenUnicodeAndPartialWordDedup() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val folder =
                File(app.filesDir, "native-text-policy-proof-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val input = File(folder, "source.pdf")
            PDDocument().use { pdf ->
                fun page() = PDPage(PDRectangle(600f, 800f)).also { pdf.addPage(it) }
                PDPageContentStream(pdf, page()).use { c ->
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 20f)
                    c.newLineAtOffset(40f, 740f)
                    c.showText("SHORT NATIVE")
                    c.endText()
                }
                val mixed = page()
                val scan =
                    Bitmap.createBitmap(520, 600, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                    }
                Canvas(scan)
                    .drawText(
                        "SCANNED BODY 00123",
                        20f,
                        100f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 28f
                        },
                    )
                val image = LosslessFactory.createFromImage(pdf, scan)
                scan.recycle()
                PDPageContentStream(pdf, mixed).use { c ->
                    c.drawImage(image, 40f, 100f, 520f, 600f)
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 20f)
                    c.newLineAtOffset(40f, 740f)
                    c.showText("HEADER")
                    c.endText()
                    for (y in listOf(680f, 130f)) {
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 10f)
                        c.newLineAtOffset(40f, y)
                        c.showText("IMAGE HEADER FOOTER " + "A".repeat(40))
                        c.endText()
                    }
                }
                val dictionary = COSDictionary(PDType1Font.HELVETICA.cosObject)
                val badFont = PDType1Font(dictionary)
                PDPageContentStream(pdf, page()).use { c ->
                    c.beginText()
                    c.setFont(badFont, 20f)
                    c.newLineAtOffset(50f, 650f)
                    c.showText("BAD LAYER FIXED")
                    c.endText()
                }
                val mapping = pdf.document.createCOSStream()
                mapping.createOutputStream().use { stream ->
                    val codes =
                        (0..255).joinToString("\n") {
                            String.format(java.util.Locale.ROOT, "<%02X><%04X>", it, 0xe000 + it)
                        }
                    stream.write(
                        ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Broken def /CMapType 2 def 1 begincodespacerange <00><FF> endcodespacerange 256 beginbfchar\n$codes\nendbfchar endcmap CMapName currentdict /CMap defineresource pop end end")
                            .toByteArray(Charsets.US_ASCII)
                    )
                }
                dictionary.setItem(COSName.TO_UNICODE, mapping)
                pdf.save(input)
            }
            val hash = PdfAssembly.sha256(input)
            val mixed = NativeTextCoverage.assess(input, 1)
            val native = NativeTextCoverage.assess(input, 0)
            val bad = NativeTextCoverage.assess(input, 2)
            assertFalse(native.needsOcr)
            assertEquals("uncovered-image-region", mixed.reason)
            assertEquals("unreliable-unicode", bad.reason)
            assertTrue(bad.badGlyphs > 5)
            assertTrue(ReaderDocument.text(input, 2).isEmpty())
            PDDocument.load(input).use { assertTrue(PdfTextExport.paragraphs(it, 2).isEmpty()) }
            fun corners(r: RectF) =
                listOf(
                    PointN(r.left, r.top),
                    PointN(r.right, r.top),
                    PointN(r.right, r.bottom),
                    PointN(r.left, r.bottom),
                )
            val header = mixed.regions.first { it.text == "HEADER" }.rectangle
            val missing = RectF(.3f, header.top, .55f, header.bottom)
            val combined =
                OcrLine(
                    "HEADER MISSING",
                    corners(RectF(header.left, header.top, missing.right, header.bottom)),
                    elements =
                        listOf(
                            OcrElement("HEADER", corners(header)),
                            OcrElement("MISSING", corners(missing)),
                        ),
                )
            val body = OcrLine("SCANNED BODY 00123", corners(RectF(.1f, .22f, .8f, .27f)))
            val fixed = OcrLine("BAD LAYER FIXED", corners(RectF(.083f, .165f, .5f, .19f)))
            fun engine(counter: AtomicInteger) =
                object : OcrEngine {
                    override val id = "native-policy-fixture-${UUID.randomUUID()}"

                    override suspend fun layout(
                        page: PageRow,
                        chinese: Boolean,
                        options: OcrOptions,
                    ): OcrLayout = error("unused")

                    override suspend fun recognizeBitmap(
                        source: Bitmap,
                        chinese: Boolean,
                        options: OcrOptions,
                    ) =
                        OcrLayout(
                            source.width,
                            source.height,
                            if (counter.getAndIncrement() == 0) listOf(combined, body)
                            else listOf(fixed),
                        )
                }
            val selectionCalls = AtomicInteger()
            val selectionEngine = engine(selectionCalls)
            assertEquals(1, PageTextSource.load(app, input, 0, selectionEngine).size)
            assertEquals(0, selectionCalls.get())
            val selected = PageTextSource.load(app, input, 1, selectionEngine)
            assertEquals(1, selected.count { it.text == "HEADER" })
            assertTrue(selected.any { it.text == "MISSING" })
            assertTrue(selected.any { it.text == body.text })
            PageTextSource.load(app, input, 1, selectionEngine)
            assertEquals(1, selectionCalls.get())
            assertEquals(
                fixed.text,
                PageTextSource.load(app, input, 2, selectionEngine).single().text,
            )
            assertEquals(2, selectionCalls.get())
            val output = File(folder, "searchable.pdf")
            val exportCalls = AtomicInteger()
            val report = PdfSearchable.convert(app, input, output, engine(exportCalls))
            assertEquals(2, exportCalls.get())
            assertFalse(report.getJSONArray("pages").getJSONObject(0).getBoolean("ocr"))
            assertEquals(2, report.getJSONArray("pages").getJSONObject(1).getInt("addedLines"))
            assertEquals(1, ReaderDocument.text(output, 1).count { it.text == "HEADER" })
            assertTrue(ReaderDocument.text(output, 2).any { it.text == fixed.text })
            PDDocument.load(output).use { pdf ->
                assertTrue(PdfTextExport.paragraphs(pdf, 2).joinToString(" ").contains(fixed.text))
            }
            assertEquals(1, PdfSearch.find(output, fixed.text).size)
            assertFalse(
                PdfEngine.text(output).codePoints().anyMatch {
                    Character.getType(it) == Character.PRIVATE_USE.toInt()
                }
            )
            assertEquals(hash, PdfAssembly.sha256(input))
            File(folder, "proof.json")
                .writeText(
                    JSONObject()
                        .put("sourceSha256", hash)
                        .put("sourceUnchanged", true)
                        .put("selectionOcrCalls", selectionCalls.get())
                        .put("exportOcrCalls", exportCalls.get())
                        .put("cachedReadDidNotRepeatOcr", true)
                        .put("shortNativeSkippedOcr", true)
                        .put("mixedHeaderCount", 1)
                        .put("badGlyphs", bad.badGlyphs)
                        .put("report", report)
                        .toString(2)
                )
            android.util.Log.i(
                "NativeTextPolicyFlowTest",
                "Proof: ${File(folder,"proof.json").path}",
            )
        }
}
