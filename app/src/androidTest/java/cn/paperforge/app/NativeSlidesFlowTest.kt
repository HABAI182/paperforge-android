package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeSlidesFlowTest {
    private suspend fun finished(repo: Repository, id: String): LocalTaskRow =
        withTimeout(120000) {
            while (true) {
                val row = repo.dao.task(id)!!
                if (row.state !in LocalTaskRow.active) {
                    assertEquals(row.error, LocalTaskState.SUCCEEDED, row.state)
                    return@withTimeout row
                }
                delay(200)
            }
            error("unreachable")
        }

    private fun result(row: LocalTaskRow) =
        File(row.resourceEntries().first { it.id == "result" }.path)

    @Test
    fun nativeTextImagesPathsPaintingOrderRotationAndExplicitComplexPageModes() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            val folder =
                File(app.filesDir, "native-slides-proof-${UUID.randomUUID()}").apply { mkdirs() }
            var sourceId: String? = null
            try {
                val source = File(folder, "native-input.pdf")
                PDDocument().use { pdf ->
                    val cjk = PdfTextLayer.font(app, pdf)
                    val bitmap =
                        Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.GREEN)
                        }
                    Canvas(bitmap).drawRect(0f, 0f, 24f, 16f, Paint().apply { color = Color.RED })
                    val image = LosslessFactory.createFromImage(pdf, bitmap)
                    bitmap.recycle()
                    val first = PDPage(PDRectangle(600f, 400f))
                    pdf.addPage(first)
                    PDPageContentStream(pdf, first).use {
                        it.setNonStrokingColor(255, 235, 220)
                        it.addRect(30f, 310f, 300f, 65f)
                        it.fill()
                        it.setNonStrokingColor(0, 0, 0)
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA_BOLD, 24f)
                        it.newLineAtOffset(40f, 330f)
                        it.showText("NATIVE 00123")
                        it.endText()
                        it.beginText()
                        it.setFont(cjk, 16f)
                        it.newLineAtOffset(40f, 280f)
                        it.showText("原生文字 00123")
                        it.endText()
                        it.drawImage(image, 30f, 60f, 90f, 60f)
                        it.drawImage(image, 150f, 60f, 90f, 60f)
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA, 20f)
                        it.newLineAtOffset(40f, 180f)
                        it.showText("PAINT ORDER")
                        it.endText()
                        it.addRect(30f, 165f, 240f, 40f)
                        it.fill()
                        it.setStrokingColor(0, 0, 255)
                        it.setLineWidth(2f)
                        it.moveTo(350f, 70f)
                        it.lineTo(500f, 120f)
                        it.curveTo(520f, 140f, 480f, 180f, 420f, 170f)
                        it.stroke()
                    }
                    val second =
                        PDPage(PDRectangle(600f, 400f)).apply {
                            cropBox = PDRectangle(40f, 30f, 520f, 340f)
                            rotation = 90
                        }
                    pdf.addPage(second)
                    PDPageContentStream(pdf, second).use {
                        it.setNonStrokingColor(0, 0, 0)
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA, 20f)
                        it.newLineAtOffset(80f, 300f)
                        it.showText("ROTATED 00456")
                        it.endText()
                        it.drawImage(image, 90f, 100f, 90f, 60f)
                        it.setNonStrokingColor(255, 0, 0)
                        it.addRect(60f, 50f, 80f, 30f)
                        it.fill()
                    }
                    val complex = PDPage(PDRectangle(600f, 400f))
                    pdf.addPage(complex)
                    PDPageContentStream(pdf, complex).use {
                        it.addRect(100f, 100f, 300f, 150f)
                        it.clip()
                        it.setNonStrokingColor(0, 0, 255)
                        it.addRect(0f, 0f, 600f, 400f)
                        it.fill()
                        it.setNonStrokingColor(255, 255, 255)
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA, 24f)
                        it.newLineAtOffset(120f, 180f)
                        it.showText("CLIPPED PAGE")
                        it.endText()
                    }
                    pdf.save(source)
                }
                source.copyTo(File(folder, "source-proof.pdf"))
                val hash = PdfAssembly.sha256(source)
                val doc = repo.registerPdf(source, "Native slides proof", listed = false)
                sourceId = doc.id
                suspend fun queue(prefer: Boolean): LocalTaskRow {
                    val row =
                        DocumentJobs.prepare(
                            repo,
                            "PDF",
                            JobInput(
                                JSONObject()
                                    .put("document", doc.id)
                                    .put("mode", "pdf-pptx-native")
                                    .put(
                                        "options",
                                        JSONObject().put("preferEditable", prefer).toString(),
                                    )
                            ),
                        )
                    DocumentJobs.schedule(repo, row)
                    return finished(repo, row.id)
                }
                val standard = queue(false)
                val pptx = result(standard)
                pptx.copyTo(File(folder, "native-standard.pptx"))
                var standardReport = JSONObject()
                ZipFile(pptx).use { zip ->
                    standardReport =
                        JSONObject(
                            zip.getInputStream(zip.getEntry("paperforge-conversion.json"))
                                .bufferedReader()
                                .use { it.readText() }
                        )
                    File(folder, "standard-report.json").writeText(standardReport.toString(2))
                    val pages = standardReport.getJSONArray("pages")
                    assertEquals(3, pages.length())
                    assertEquals(
                        pages.getJSONObject(0).toString(),
                        "editable-objects",
                        pages.getJSONObject(0).getString("mode"),
                    )
                    assertTrue(pages.getJSONObject(0).getInt("textBoxes") >= 3)
                    assertEquals(2, pages.getJSONObject(0).getInt("pictures"))
                    assertTrue(pages.getJSONObject(0).getInt("paths") >= 3)
                    assertEquals(
                        pages.getJSONObject(1).toString(),
                        "editable-objects",
                        pages.getJSONObject(1).getString("mode"),
                    )
                    assertEquals("page-appearance", pages.getJSONObject(2).getString("mode"))
                    assertEquals(0, pages.getJSONObject(2).getInt("textBoxes"))
                    val first =
                        zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml"))
                            .bufferedReader()
                            .use { it.readText() }
                    assertTrue(first.contains("NATIVE 00123"))
                    assertTrue(first.contains("原生文字 00123"))
                    assertTrue(first.contains("<a:custGeom>"))
                    assertTrue(first.indexOf("PAINT ORDER") < first.lastIndexOf("Editable path"))
                    val rotated =
                        zip.getInputStream(zip.getEntry("ppt/slides/slide2.xml"))
                            .bufferedReader()
                            .use { it.readText() }
                    assertTrue(rotated.contains("ROTATED 00456"))
                    assertTrue(rotated.contains("rot=\"5400000\""))
                }
                assertTrue(
                    standard.resourceEntries().any {
                        it.id == "tool-report" && File(it.path).isFile
                    }
                )
                assertTrue(standard.detail.contains("原生对象 2 页"))
                assertTrue(standard.detail.contains("整页外观 1 页"))
                val preferred = queue(true)
                result(preferred).copyTo(File(folder, "native-edit-preferred.pptx"))
                ZipFile(result(preferred)).use { zip ->
                    val report =
                        JSONObject(
                            zip.getInputStream(zip.getEntry("paperforge-conversion.json"))
                                .bufferedReader()
                                .use { it.readText() }
                        )
                    assertTrue(report.getBoolean("preferEditable"))
                    assertEquals(
                        "editable-simplified",
                        report.getJSONArray("pages").getJSONObject(2).getString("mode"),
                    )
                    File(folder, "preferred-report.json").writeText(report.toString(2))
                }
                val previewRow = finished(repo, ConversionPreviews.prepare(repo, standard).id)
                val preview = ConversionPreviews.load(previewRow)
                assertEquals(3, preview.left.pages)
                assertEquals(3, preview.right.pages)
                preview.right.pdf.copyTo(File(folder, "actual-native-office-preview.pdf"))
                PDDocument.load(preview.right.pdf).use {
                    val text = PDFTextStripper().getText(it)
                    assertTrue(text.contains("00123"))
                    assertTrue(text.contains("00456"))
                    assertTrue(text.replace(" ", "").contains("原生文字"))
                }
                assertEquals(hash, PdfAssembly.sha256(source))
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", hash)
                            .put("sourceUnchanged", true)
                            .put("nativePages", 2)
                            .put("appearancePages", 1)
                            .put("editPreferredSimplifiedPages", 1)
                            .put("sourceAndActualPptxPreviewPages", 3)
                            .put("standardReport", standardReport)
                            .toString(2)
                    )
                android.util.Log.i(
                    "NativeSlidesFlowTest",
                    "Proof: ${File(folder,"proof.json").path}",
                )
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before }
                    .forEach { repo.dao.deleteTask(it.id) }
                sourceId?.let { repo.dao.document(it) }?.let { repo.deleteDocument(it) }
            }
        }
}
