package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class DocumentCleanerAcceptanceTest {
    @Test
    fun officePackagesRemoveWatermarksAndRetainBodyAndMedia() {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.cacheDir, "watermark-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val formats =
            mapOf(
                "docx" to
                    ("word/document.xml" to
                        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml"><w:body><w:p><w:r><w:t>BODY</w:t></w:r></w:p><w:p><w:r><w:pict><v:shape id="PowerPlusWaterMarkObject1"><v:textpath string="DRAFT"/></v:shape></w:pict></w:r></w:p><v:shape id="normalLogo"><v:textpath string="KEEP"/></v:shape></w:body></w:document>"""),
                "xlsx" to
                    ("xl/worksheets/sheet1.xml" to
                        """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetData><row><c t="inlineStr"><is><t>BODY</t></is></c></row></sheetData><picture r:id="rId1"/></worksheet>"""),
                "pptx" to
                    ("ppt/slides/slide1.xml" to
                        """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="Watermark"/></p:nvSpPr><p:txBody><a:p><a:r><a:t>DRAFT</a:t></a:r></a:p></p:txBody></p:sp><p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/></p:nvSpPr><p:txBody><a:p><a:r><a:t>BODY</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""),
                "odt" to
                    ("content.xml" to
                        """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><text:p>BODY</text:p><draw:frame draw:name="Watermark"><draw:text-box><text:p>DRAFT</text:p></draw:text-box></draw:frame></office:body></office:document-content>"""),
            )
        for ((ext, fixture) in formats) {
            val input = File(folder, "input.$ext")
            val output = File(folder, "clean.$ext")
            ZipOutputStream(input.outputStream()).use { z ->
                for ((name, bytes) in
                    mapOf(
                        fixture.first to fixture.second.toByteArray(),
                        "media/keep.bin" to byteArrayOf(1, 2, 3, 4),
                    )) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
            val before = input.readBytes()
            assertEquals(1, DocumentWatermarks.office(input, output).removed)
            ZipFile(output).use { z ->
                val xml = z.getInputStream(z.getEntry(fixture.first)).bufferedReader().readText()
                assertTrue(xml.contains("BODY"))
                assertFalse(xml.contains("DRAFT"))
                assertArrayEquals(
                    byteArrayOf(1, 2, 3, 4),
                    z.getInputStream(z.getEntry("media/keep.bin")).readBytes(),
                )
                if (ext == "docx") assertTrue(xml.contains("KEEP"))
            }
            assertArrayEquals(before, input.readBytes())
        }
    }

    @Test
    fun actualWorkerRemovesSelectedPdfTextIncludingNestedForm() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val source = File(app.cacheDir, "watermark-${UUID.randomUUID()}.pdf")
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { c ->
                c.beginText()
                c.setFont(PDType1Font.HELVETICA, 20f)
                c.newLineAtOffset(60f, 600f)
                c.showTextWithPositioning(arrayOf("DR", 15f, "AFT"))
                c.newLineAtOffset(0f, -70f)
                c.showText("BODY")
                c.endText()
                val form = PDFormXObject(pdf)
                form.resources =
                    PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                form.bBox = PDRectangle(200f, 50f)
                form.cosObject.createOutputStream().use {
                    it.write("BT /F1 18 Tf 1 0 0 1 10 10 Tm (DRAFT) Tj ET".toByteArray())
                }
                c.drawForm(form)
            }
            pdf.save(source)
        }
        val original = source.readBytes()
        val id =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "DOCUMENT_CLEAN")
                        .put("name", "课堂.pdf")
                        .put("inputs", JSONArray(listOf(source.path)))
                        .put(
                            "options",
                            JSONObject().put("mode", "TEXT").put("text", "DRAFT").toString(),
                        )
                ),
            )
        withTimeout(45000) {
            while (
                androidx.work.WorkManager.getInstance(app)
                    .getWorkInfoById(id)
                    .get()
                    ?.state
                    ?.isFinished != true
            ) delay(100)
        }
        val info =
            checkNotNull(androidx.work.WorkManager.getInstance(app).getWorkInfoById(id).get())
        assertEquals(
            info.outputData.getString("error"),
            androidx.work.WorkInfo.State.SUCCEEDED,
            info.state,
        )
        val file = File(info.outputData.getString("path")!!)
        PDDocument.load(file).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("BODY"))
            assertFalse(text.contains("DRAFT"))
        }
        assertTrue(info.outputData.getString("notice")!!.contains("2"))
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun regionalImageRepairKeepsPixelsOutsideSelection() {
        val source =
            Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).apply {
            drawRect(130f, 320f, 160f, 335f, Paint().apply { color = Color.GRAY })
            drawText(
                "BODY",
                30f,
                80f,
                Paint().apply {
                    color = Color.BLACK
                    textSize = 28f
                },
            )
        }
        val result =
            DocumentWatermarks.repair(
                source,
                JSONObject()
                    .put(
                        "region",
                        JSONObject()
                            .put("left", .40)
                            .put("top", .77)
                            .put("right", .58)
                            .put("bottom", .87),
                    ),
            )
        assertEquals(source.getPixel(40, 70), result.getPixel(40, 70))
        assertTrue(Color.red(result.getPixel(145, 325)) > 245)
        source.recycle()
        result.recycle()
    }
}
