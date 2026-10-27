package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.*
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DocxLayoutFlowTest {
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

    private fun file(row: LocalTaskRow) =
        File(row.resourceEntries().first { it.id == "result" }.path)

    @Test
    fun columnsHeadingsUnequalMergedTableVisibleCropAndMixedPageAppearance() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allTasks().map { it.id }.toSet()
            var docId: String? = null
            val folder =
                File(app.filesDir, "docx-layout-proof-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val source = File(folder, "source.pdf")
                PDDocument().use { pdf ->
                    val page = PDPage(PDRectangle(600f, 800f))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { c ->
                        fun text(
                            text: String,
                            x: Float,
                            y: Float,
                            size: Float = 12f,
                            font: PDFont = PDType1Font.HELVETICA,
                        ) {
                            c.beginText()
                            c.setFont(font, size)
                            c.newLineAtOffset(x, y)
                            c.showText(text)
                            c.endText()
                        }
                        text("DOCUMENT HEADING", 40f, 750f, 24f, PDType1Font.HELVETICA_BOLD)
                        // Deliberately paint row-major. Output must reconstruct column-major order.
                        repeat(4) { i ->
                            text("LEFT ${i+1} paragraph 00123", 40f, 680f - i * 30)
                            text("RIGHT ${i+1} paragraph 00456", 335f, 680f - i * 30)
                        }
                        text("[1] Footnote retained as text", 40f, 30f, 8f)
                    }
                    val second = PDPage(PDRectangle(500f, 650f))
                    pdf.addPage(second)
                    val pixels =
                        Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.GREEN)
                        }
                    Canvas(pixels).drawRect(0f, 0f, 50f, 60f, Paint().apply { color = Color.RED })
                    val image = LosslessFactory.createFromImage(pdf, pixels)
                    pixels.recycle()
                    PDPageContentStream(pdf, second).use { c ->
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA_BOLD, 20f)
                        c.newLineAtOffset(40f, 600f)
                        c.showText("TABLE AND CROPPED IMAGE")
                        c.endText()
                        c.saveGraphicsState()
                        c.addRect(350f, 430f, 50f, 60f)
                        c.clip()
                        c.drawImage(image, 350f, 430f, 100f, 60f)
                        c.restoreGraphicsState()
                        c.setLineWidth(1f)
                        c.addRect(40f, 130f, 420f, 180f)
                        c.stroke()
                        listOf(190f, 250f).forEach { y ->
                            c.moveTo(40f, y)
                            c.lineTo(460f, y)
                            c.stroke()
                        }
                        c.moveTo(160f, 130f)
                        c.lineTo(160f, 250f)
                        c.stroke()
                        fun text(t: String, x: Float, y: Float) {
                            c.beginText()
                            c.setFont(PDType1Font.HELVETICA, 12f)
                            c.newLineAtOffset(x, y)
                            c.showText(t)
                            c.endText()
                        }
                        text("MERGED HEADER", 50f, 275f)
                        text("00123", 50f, 215f)
                        text("00456", 180f, 215f)
                        text("A", 50f, 155f)
                        text("B", 180f, 155f)
                    }
                    pdf.save(source)
                }
                val hash = PdfAssembly.sha256(source)
                val doc = repo.registerPdf(source, "DOCX layout proof", listed = false)
                docId = doc.id
                source.copyTo(File(folder, "source-proof.pdf"))
                suspend fun convert(mode: String): LocalTaskRow {
                    val row =
                        DocumentJobs.prepare(
                            repo,
                            "PDF",
                            JobInput(
                                JSONObject()
                                    .put("document", doc.id)
                                    .put("mode", "docx-layout")
                                    .put("options", JSONObject().put("layoutMode", mode).toString())
                            ),
                        )
                    DocumentJobs.schedule(repo, row)
                    return finished(repo, row.id)
                }
                val editable = convert("editable")
                val appearance = convert("appearance")
                file(editable).copyTo(File(folder, "editable.docx"))
                file(appearance).copyTo(File(folder, "appearance.docx"))
                var report = JSONObject()
                ZipFile(file(editable)).use { zip ->
                    fun text(name: String) =
                        zip.getInputStream(zip.getEntry(name)).bufferedReader().use {
                            it.readText()
                        }
                    val document = text("word/document.xml")
                    report = JSONObject(text("paperforge-conversion.json"))
                    File(folder, "editable-report.json").writeText(report.toString(2))
                    assertTrue(
                        report
                            .getJSONArray("pages")
                            .getJSONObject(0)
                            .getBoolean("twoColumnReadingOrder")
                    )
                    assertEquals(0, report.getJSONArray("pages").getJSONObject(0).getInt("tables"))
                    assertTrue(document.indexOf("LEFT 4") < document.indexOf("RIGHT 1"))
                    assertTrue(document.indexOf("RIGHT 4") < document.indexOf("Footnote"))
                    assertTrue(document.contains("w:pStyle w:val=\"Heading1\""))
                    assertTrue(document.contains("w:pStyle w:val=\"FootnoteText\""))
                    assertTrue(document.contains("<w:gridSpan w:val=\"2\"/>"))
                    assertTrue(document.contains("00123"))
                    assertTrue(document.contains("00456"))
                    assertEquals(
                        1,
                        report.getJSONArray("pages").getJSONObject(1).getInt("pictures"),
                    )
                    val imageEntry =
                        zip.entries().asSequence().first { it.name.startsWith("word/media/") }
                    val bytes = zip.getInputStream(imageEntry).readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertTrue(
                        Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 240
                    )
                    assertTrue(
                        Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) < 20
                    )
                    bitmap.recycle()
                }
                ZipFile(file(appearance)).use { zip ->
                    val raw =
                        zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use {
                            it.readText()
                        }
                    assertEquals(2, Regex("<wp:anchor ").findAll(raw).count())
                    assertFalse(Regex("<w:t(?:\\s|>)").containsMatchIn(raw))
                }
                val editablePreview =
                    ConversionPreviews.load(
                        finished(repo, ConversionPreviews.prepare(repo, editable).id)
                    )
                val appearancePreview =
                    ConversionPreviews.load(
                        finished(repo, ConversionPreviews.prepare(repo, appearance).id)
                    )
                assertEquals(2, appearancePreview.right.pages)
                editablePreview.right.pdf.copyTo(File(folder, "actual-editable-office.pdf"))
                appearancePreview.right.pdf.copyTo(File(folder, "actual-appearance-office.pdf"))
                PDDocument.load(editablePreview.right.pdf).use { pdf ->
                    val text = PDFTextStripper().getText(pdf)
                    assertTrue(text.contains("00123"))
                    assertTrue(text.contains("00456"))
                    assertTrue(text.contains("MERGED HEADER"))
                }
                assertTrue(
                    editable.resourceEntries().any {
                        it.id == "tool-report" && File(it.path).isFile
                    }
                )
                assertTrue(appearance.detail.contains("整页图"))
                assertEquals(hash, PdfAssembly.sha256(source))
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceSha256", hash)
                            .put("sourceUnchanged", true)
                            .put("editablePages", editablePreview.right.pages)
                            .put("appearancePages", appearancePreview.right.pages)
                            .put("editableReport", report)
                            .toString(2)
                    )
                android.util.Log.i("DocxLayoutFlowTest", "Proof: ${File(folder,"proof.json").path}")
            } finally {
                repo.dao
                    .allTasks()
                    .filter { it.id !in before }
                    .forEach { repo.dao.deleteTask(it.id) }
                docId?.let { repo.dao.document(it) }?.let { repo.deleteDocument(it) }
            }
        }
}
