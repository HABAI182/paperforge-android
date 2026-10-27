package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.paperforge.app.data.DocumentJobs
import cn.paperforge.app.data.DocumentResources
import cn.paperforge.app.data.JobInput
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfRedactionFlowTest {
    @Test
    fun nativePartialTextAndExplicitRasterFallbackProduceInspectableCopies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val folder = File(app.filesDir, "redaction-proof-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "native-source.pdf")
        val first = File(folder, "native-tj.pdf")
        val second = File(folder, "native-tj-array.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 300f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.beginText()
                s.setFont(PDType1Font.HELVETICA, 14f)
                s.newLineAtOffset(20f, 240f)
                s.showText("PUBLIC SECRET KEEP")
                s.newLineAtOffset(0f, -30f)
                s.setCharacterSpacing(1f)
                s.showTextWithPositioning(arrayOf<Any>("BEFORE PRIVATE AFTER", -30f, " END"))
                s.endText()
                s.addRect(20f, 20f, 30f, 30f)
                s.stroke()
            }
            doc.save(source)
        }
        val originalHash = PdfAssembly.sha256(source)
        fun lastGlyphX(file: File, glyph: String): Float {
            var x: Float? = null
            PDDocument.load(file).use { pdf ->
                val stripper =
                    object : PDFTextStripper() {
                        override fun writeString(
                            text: String,
                            positions: MutableList<TextPosition>,
                        ) {
                            positions.lastOrNull { it.unicode == glyph }?.let { x = it.xDirAdj }
                        }
                    }
                stripper.getText(pdf)
            }
            return requireNotNull(x)
        }
        val keepX = lastGlyphX(source, "K")
        val afterX = lastGlyphX(source, "A")
        val native = PdfRedaction.semantic(source, first, 0, "SECRET")
        assertEquals("语义文字删除", native.mode)
        PDDocument.load(first).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertFalse(text.contains("SECRET"))
            assertTrue(text.contains("PUBLIC"))
            assertTrue(text.contains("KEEP"))
            assertTrue(text.contains("PRIVATE"))
            val streams = pdf.document.objects.mapNotNull { it.`object` as? COSStream }
            assertTrue(streams.isNotEmpty())
            streams.forEach { stream ->
                val bytes = stream.createInputStream().use { it.readBytes() }
                assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("SECRET"))
            }
        }
        assertFalse(first.readBytes().toString(Charsets.ISO_8859_1).contains("SECRET"))
        assertEquals(keepX, lastGlyphX(first, "K"), .1f)
        PdfRedaction.semantic(first, second, 0, "PRIVATE")
        PDDocument.load(second).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertFalse(text.contains("SECRET"))
            assertFalse(text.contains("PRIVATE"))
            assertTrue(text.contains("BEFORE"))
            assertTrue(text.contains("AFTER"))
        }
        assertEquals(afterX, lastGlyphX(second, "A"), .1f)
        assertEquals(originalHash, PdfAssembly.sha256(source))
        val fullwidthSource = File(folder, "fullwidth-source.pdf")
        val fullwidthOutput = File(folder, "fullwidth-redacted.pdf")
        val unicodeMapping = PdfTextLayer.Mapping()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 300f))
            doc.addPage(page)
            val font = PdfTextLayer.font(app, doc)
            val line = "前 ００１２３ 后"
            unicodeMapping.record(font, line)
            PDPageContentStream(doc, page).use { s ->
                s.beginText()
                s.setFont(font, 14f)
                s.newLineAtOffset(20f, 240f)
                s.showText(line)
                s.endText()
            }
            doc.save(fullwidthSource)
        }
        unicodeMapping.finish(fullwidthSource)
        assertEquals(1, PdfSearch.find(fullwidthSource, "00123").size)
        PdfRedaction.semantic(fullwidthSource, fullwidthOutput, 0, "00123")
        PDDocument.load(fullwidthOutput).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertFalse(
                java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .contains("00123")
            )
            assertTrue(text.contains("前"))
            assertTrue(text.contains("后"))
        }
        val mixedSource = File(folder, "mixed-source.pdf")
        PDDocument.load(source).use { doc ->
            val page = PDPage(PDRectangle(300f, 300f))
            doc.addPage(page)
            val bitmap =
                Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE)
                }
            val picture = LosslessFactory.createFromImage(doc, bitmap)
            bitmap.recycle()
            val form =
                PDFormXObject(doc).apply {
                    bBox = PDRectangle(20f, 20f)
                    resources = PDResources()
                }
            form.cosObject.createOutputStream().use { stream ->
                PDPageContentStream(doc, form, stream).use { content ->
                    content.addRect(0f, 0f, 20f, 20f)
                    content.stroke()
                }
            }
            PDPageContentStream(doc, page).use { s ->
                s.beginText()
                s.setFont(PDType1Font.HELVETICA, 14f)
                s.newLineAtOffset(20f, 240f)
                s.showText("OTHER SECRET PAGE")
                s.endText()
                s.drawImage(picture, 20f, 120f, 40f, 40f)
                s.drawForm(form)
            }
            doc.save(mixedSource)
        }
        val mixedHash = PdfAssembly.sha256(mixedSource)
        val repo = app.repository
        val sourceDoc = repo.registerPdf(mixedSource, "脱敏队列验收")
        val work =
            DocumentJobs.enqueue(
                repo,
                "PDF",
                JobInput(
                    JSONObject()
                        .put("document", sourceDoc.id)
                        .put("mode", "redact-semantic")
                        .put(
                            "options",
                            JSONObject().put("page", 0).put("target", "SECRET").toString(),
                        )
                ),
            )
        val result =
            withTimeout(90000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(work).filterNotNull().first {
                    it.state.isFinished
                }
            }
        assertEquals(result.outputData.getString("error"), WorkInfo.State.SUCCEEDED, result.state)
        val mixedOutput = File(requireNotNull(result.outputData.getString("path")))
        mixedOutput.copyTo(File(folder, "mixed-redacted.pdf"))
        PDDocument.load(mixedOutput).use { pdf ->
            val firstText =
                PDFTextStripper()
                    .apply {
                        startPage = 1
                        endPage = 1
                    }
                    .getText(pdf)
            val secondText =
                PDFTextStripper()
                    .apply {
                        startPage = 2
                        endPage = 2
                    }
                    .getText(pdf)
            assertFalse(firstText.contains("SECRET"))
            assertTrue(firstText.contains("PUBLIC"))
            assertTrue(secondText.contains("SECRET"))
            val resources = pdf.getPage(1).resources
            val xobjects = resources.xObjectNames.map { resources.getXObject(it) }
            assertTrue(xobjects.any { it is PDImageXObject })
            assertTrue(xobjects.any { it is PDFormXObject })
        }
        val registered = requireNotNull(repo.dao.documentByPath(mixedOutput.path))
        val reportFile =
            File(
                DocumentResources.entries(repo, registered.id)
                    .single { it.role == "redaction-report" }
                    .path
            )
        val savedReport = reportFile.readText()
        assertTrue(savedReport.contains("语义文字删除"))
        assertFalse(savedReport.contains("SECRET"))
        reportFile.copyTo(File(folder, "queue-report.json"))
        assertEquals(mixedHash, PdfAssembly.sha256(mixedSource))
        val imageSource = File(folder, "image-source.pdf")
        val raster = File(folder, "raster-redacted.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 300f))
            doc.addPage(page)
            val image =
                Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            PDPageContentStream(doc, page).use { s ->
                s.drawImage(LosslessFactory.createFromImage(doc, image), 60f, 180f, 60f, 60f)
                s.beginText()
                s.setFont(PDType1Font.HELVETICA, 8f)
                s.newLineAtOffset(62f, 210f)
                s.showText("SECRET")
                s.endText()
            }
            image.recycle()
            doc.save(imageSource)
        }
        val imageHash = PdfAssembly.sha256(imageSource)
        val rejected = runCatching {
            PdfRedaction.semantic(imageSource, File(folder, "should-not-exist.pdf"), 0, "SECRET")
        }
        assertTrue(rejected.isFailure)
        assertFalse(File(folder, "should-not-exist.pdf").exists())
        val imageDoc = repo.registerPdf(imageSource, "图片脱敏队列验收")
        val rasterOptions =
            JSONObject()
                .put("page", 0)
                .put("left", .18)
                .put("top", .18)
                .put("right", .42)
                .put("bottom", .42)
        val rasterWork =
            DocumentJobs.enqueue(
                repo,
                "PDF",
                JobInput(
                    JSONObject()
                        .put("document", imageDoc.id)
                        .put("mode", "redact-raster")
                        .put("options", rasterOptions.toString())
                ),
            )
        val rasterResult =
            withTimeout(90000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(rasterWork).filterNotNull().first {
                    it.state.isFinished
                }
            }
        assertEquals(
            rasterResult.outputData.getString("error"),
            WorkInfo.State.SUCCEEDED,
            rasterResult.state,
        )
        val rasterOutput = File(requireNotNull(rasterResult.outputData.getString("path")))
        rasterOutput.copyTo(raster)
        val rasterDoc = requireNotNull(repo.dao.documentByPath(rasterOutput.path))
        val rasterReportFile =
            File(
                DocumentResources.entries(repo, rasterDoc.id)
                    .single { it.role == "redaction-report" }
                    .path
            )
        val rasterReport = rasterReportFile.readText()
        assertTrue(rasterReport.contains("整份栅格脱敏"))
        assertFalse(rasterReport.contains("SECRET"))
        rasterReportFile.copyTo(File(folder, "raster-report.json"))
        assertEquals(imageHash, PdfAssembly.sha256(imageSource))
        PDDocument.load(raster).use {
            assertFalse(PDFTextStripper().getText(it).contains("SECRET"))
        }
        ParcelFileDescriptor.open(raster, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val out = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                    page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixel = out.getPixel(90, 90)
                    assertTrue(
                        Color.red(pixel) < 40 && Color.green(pixel) < 40 && Color.blue(pixel) < 40
                    )
                    out.recycle()
                }
            }
        }
        File(folder, "report.txt")
            .writeText(
                "${native.mode}: ${native.detail}\n$rasterReport\nsource SHA256: $originalHash\nimage source SHA256: $imageHash\n"
            )
        android.util.Log.i("PdfRedactionFlowTest", "Inspectable outputs: ${folder.absolutePath}")
        Unit
    }
}
