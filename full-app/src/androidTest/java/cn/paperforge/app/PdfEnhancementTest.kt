package cn.paperforge.app

import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfEnhancementTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
    private val proof = File(app.getExternalFilesDir(null), "issue13").apply { mkdirs() }

    private fun fixture(name: String = "source.pdf", single: Boolean = false): File {
        val file = File(proof, name)
        PDDocument().use { doc ->
            val b = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.rgb(180, 180, 180))
            c.drawText(
                "Pencil 012345",
                55f,
                200f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(145, 145, 145)
                    textSize = 30f
                },
            )
            val image = LosslessFactory.createFromImage(doc, b)
            b.recycle()
            val shared = PDResources()
            shared.put(COSName.getPDFName("Scan"), image)
            repeat(if (single) 1 else 3) { i ->
                val page = PDPage(PDRectangle(300f, 400f))
                doc.addPage(page)
                page.rotation = if (i == 2) 90 else 0
                page.resources = shared
                PDPageContentStream(doc, page).use { stream ->
                    if (i == 2) {
                        val form = PDFormXObject(doc)
                        form.bBox = PDRectangle(300f, 400f)
                        form.resources = PDResources()
                        PDPageContentStream(doc, form, form.contentStream.createOutputStream())
                            .use { it.drawImage(image, 0f, 0f, 300f, 400f) }
                        stream.drawForm(form)
                    } else stream.drawImage(image, 0f, 0f, 300f, 400f)
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(20f, 350f)
                    stream.showText("NATIVE PAGE ${i+1}")
                    stream.endText()
                    stream.beginText()
                    stream.setRenderingMode(RenderingMode.NEITHER)
                    stream.setFont(PDType1Font.HELVETICA, 9f)
                    stream.newLineAtOffset(20f, 320f)
                    stream.showText("OCR HIDDEN ${i+1}")
                    stream.endText()
                    stream.setStrokingColor(1f, 0f, 0f)
                    stream.addRect(10f, 10f, 280f, 380f)
                    stream.stroke()
                }
                page.annotations =
                    listOf(
                        PDAnnotationText().apply {
                            contents = "Original note $i"
                            rectangle = PDRectangle(10f, 10f, 15f, 15f)
                        }
                    )
            }
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.fields.add(PDTextField(form).apply { partialName = "original-field" })
            doc.save(file)
        }
        return file
    }

    private fun firstImage(doc: PDDocument, page: Int): PDImageXObject {
        val resources = doc.getPage(page).resources
        return resources.xObjectNames
            .map { resources.getXObject(it) }
            .filterIsInstance<PDImageXObject>()
            .first()
    }

    @Test
    fun rangesSharedResourcesNativeOcrVectorsFormsAndSignedFiles() = runBlocking {
        val source = fixture()
        val hash = PdfAssembly.sha256(source)
        val output = File(proof, "selected.pdf")
        val report = PdfImageEnhancement.enhance(source, output, "BW", "2-3")
        File(proof, "report.json").writeText(report.toString(2))
        assertTrue(report.getInt("imageBindings") >= 2)
        PDDocument.load(source).use { old ->
            PDDocument.load(output).use { new ->
                assertEquals(3, new.numberOfPages)
                assertEquals(PDFTextStripper().getText(old), PDFTextStripper().getText(new))
                assertTrue(
                    PDFTextStripper().getText(new).replace(Regex("\\s+"), "").contains("OCRHIDDEN3")
                )
                assertArrayEquals(
                    firstImage(old, 0).cosObject.createInputStream().use { it.readBytes() },
                    firstImage(new, 0).cosObject.createInputStream().use { it.readBytes() },
                )
                assertEquals(1, firstImage(new, 1).bitsPerComponent)
                for (i in 0..2) {
                    assertArrayEquals(
                        old.getPage(i).mediaBox.cosArray.toFloatArray(),
                        new.getPage(i).mediaBox.cosArray.toFloatArray(),
                        0f,
                    )
                    assertEquals(old.getPage(i).rotation, new.getPage(i).rotation)
                    assertArrayEquals(
                        old.getPage(i).contents.use { it.readBytes() },
                        new.getPage(i).contents.use { it.readBytes() },
                    )
                    assertEquals("Original note $i", new.getPage(i).annotations.single().contents)
                }
                assertNotNull(new.documentCatalog.acroForm.getField("original-field"))
            }
        }
        assertEquals(hash, PdfAssembly.sha256(source))
        val single = fixture("single.pdf", true)
        for (preset in PdfImageEnhancement.presets.keys) {
            val target = File(proof, "single-$preset.pdf")
            assertEquals(
                1,
                PdfImageEnhancement.enhance(single, target, preset).getInt("uniqueImages"),
            )
        }
        val signed = File(proof, "signed.pdf")
        PDDocument.load(single).use { doc ->
            val acro = doc.documentCatalog.acroForm
            acro.fields.add(
                PDSignatureField(acro).apply {
                    partialName = "sig"
                    signature = PDSignature()
                }
            )
            doc.save(signed)
        }
        assertNotNull(PdfImageEnhancement.inspect(signed).blocked)
        assertTrue(
            runCatching {
                    PdfImageEnhancement.enhance(signed, File(proof, "signed-copy.pdf"), "BW")
                }
                .isFailure
        )
        assertFalse(File(proof, "signed-copy.pdf").exists())
    }

    @Test
    fun cancelledStagesResumeAndWorkerRegistersIndependentCopy() = runBlocking {
        val source = fixture("resume-source.pdf")
        val database = Room.inMemoryDatabaseBuilder(app, PaperDatabase::class.java).build()
        val repo = Repository(app, database)
        try {
            val row =
                DocumentJobs.prepare(
                    repo,
                    "PDF",
                    JobInput(JSONObject().put("mode", "enhance-images")),
                )
            val handle = JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input")))
            val first = JobFileStages(repo, handle)
            val interrupted = File(proof, "interrupted.pdf")
            var cancelled = false
            try {
                PdfImageEnhancement.enhance(source, interrupted, "GRAY", stages = first) {
                    if (it in 1..89) throw CancellationException("after first completed image")
                }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertFalse(interrupted.exists())
            assertFalse(File(interrupted.path + ".part").exists())
            assertEquals(1, first.generated)
            val resumedRow =
                repo.dao
                    .task(row.id)!!
                    .copy(workId = UUID.randomUUID().toString(), state = LocalTaskState.RUNNING)
            repo.dao.putTask(resumedRow)
            val next = JobFileStages(repo, JobHandle(resumedRow, handle.input))
            PdfImageEnhancement.enhance(source, File(proof, "resumed.pdf"), "GRAY", stages = next)
            assertEquals(1, next.reused)
            File(proof, "resume.json")
                .writeText(
                    JSONObject()
                        .put("reused", next.reused)
                        .put("generated", next.generated)
                        .toString(2)
                )
        } finally {
            database.close()
        }
        val document = app.repository.registerPdf(source, "Issue13 test", listed = false)
        try {
            val work =
                DocumentJobs.enqueue(
                    app.repository,
                    "PDF",
                    JobInput(
                        JSONObject()
                            .put("mode", "enhance-images")
                            .put("document", document.id)
                            .put(
                                "options",
                                JSONObject().put("preset", "BW").put("range", "1").toString(),
                            )
                    ),
                )
            val result =
                withTimeout(60000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(work).filterNotNull().first {
                        it.state.isFinished
                    }
                }
            assertEquals(
                result.outputData.getString("error"),
                WorkInfo.State.SUCCEEDED,
                result.state,
            )
            val output = File(result.outputData.getString("path")!!)
            assertTrue(output.isFile)
            assertNotEquals(source.canonicalPath, output.canonicalPath)
            assertEquals(source.path, app.repository.dao.document(document.id)!!.pdfPath)
        } finally {
            app.repository.dao.deleteDocument(document.id)
        }
    }
}
