package cn.paperforge.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfSizeTargetFlowTest {
    @Test
    fun targetSearchKeepsNativeFormsLinksAndBinaryDigits() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val source = File(repo.exports, "target-proof-source-${System.nanoTime()}.pdf")
            var task: String? = null
            try {
                PDDocument().use { pdf ->
                    val page = PDPage()
                    pdf.addPage(page)
                    val noise = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
                    val random = java.util.Random(2705)
                    val pixels =
                        IntArray(800 * 1000) {
                            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                        }
                    noise.setPixels(pixels, 0, 800, 0, 0, 800, 1000)
                    noise.setHasAlpha(false)
                    val image = LosslessFactory.createFromImage(pdf, noise)
                    noise.recycle()
                    val binary = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
                    Canvas(binary).apply {
                        drawColor(Color.WHITE)
                        drawText(
                            "00123456789",
                            5f,
                            60f,
                            Paint().apply {
                                color = Color.BLACK
                                textSize = 30f
                                typeface = Typeface.MONOSPACE
                            },
                        )
                    }
                    val digits = PdfImageEncoder.scan(pdf, binary, "BW", 100, 300)
                    binary.recycle()
                    PDPageContentStream(pdf, page).use { c ->
                        c.drawImage(image, 100f, 100f, 320f, 400f)
                        c.drawImage(digits, 40f, 550f, 256f, 96f)
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 18f)
                        c.newLineAtOffset(50f, 750f)
                        c.showText("KEEP_NATIVE_00123")
                        c.endText()
                    }
                    val form = PDAcroForm(pdf)
                    pdf.documentCatalog.acroForm = form
                    form.defaultResources =
                        PDResources().apply { put(COSName.HELV, PDType1Font.HELVETICA) }
                    form.defaultAppearance = "/Helv 12 Tf 0 g"
                    val amount = PDTextField(form).apply { partialName = "amount" }
                    form.fields = listOf(amount)
                    amount.widgets.single().apply {
                        rectangle = PDRectangle(50f, 680f, 130f, 20f)
                        this.page = page
                        page.annotations.add(this)
                    }
                    amount.value = "00077"
                    page.annotations.add(
                        PDAnnotationLink().apply {
                            rectangle = PDRectangle(40f, 720f, 140f, 20f)
                            action = PDActionURI().apply { uri = "https://example.test/verify" }
                        }
                    )
                    pdf.save(source)
                }
                val hash = PdfAssembly.sha256(source)
                fun binaryBytes(file: File) =
                    PDDocument.load(file).use { pdf ->
                        val r = pdf.getPage(0).resources
                        val image =
                            r.xObjectNames
                                .map { r.getXObject(it) }
                                .filterIsInstance<PDImageXObject>()
                                .single { it.bitsPerComponent == 1 }
                        image.cosObject.createRawInputStream().use { it.readBytes() }
                    }
                val digitsBefore = binaryBytes(source)
                val doc = repo.registerPdf(source, "目标体积验收")
                val target = source.length() / 2
                val id =
                    DocumentJobs.enqueue(
                        repo,
                        "PDF",
                        workDataOf(
                            "mode" to "compress",
                            "document" to doc.id,
                            "options" to JSONObject().put("targetBytes", target).toString(),
                        ),
                    )
                val row = repo.dao.allTasks().single { it.workId == id.toString() }
                task = row.id
                val info =
                    withTimeout(60000) {
                        WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                            it.state.isFinished
                        }
                    }
                assertEquals(
                    info.outputData.getString("error"),
                    WorkInfo.State.SUCCEEDED,
                    info.state,
                )
                val output = File(info.outputData.getString("path")!!)
                assertTrue(info.outputData.getBoolean("targetAchieved", false))
                assertTrue(output.length() <= target)
                assertTrue(info.outputData.getInt("attempts", 0) in 1..7)
                assertTrue(PdfEngine.text(output).contains("KEEP_NATIVE_00123"))
                assertArrayEquals(digitsBefore, binaryBytes(output))
                PDDocument.load(output).use { pdf ->
                    assertEquals(
                        "00077",
                        pdf.documentCatalog.acroForm.getField("amount").valueAsString,
                    )
                    assertEquals(
                        "https://example.test/verify",
                        (pdf.getPage(0)
                                .annotations
                                .filterIsInstance<PDAnnotationLink>()
                                .single()
                                .action as PDActionURI)
                            .uri,
                    )
                }
                val registered = repo.dao.documentByPath(output.path)!!
                val report =
                    DocumentResources.entries(repo, registered.id).single {
                        it.role == "compression-report"
                    }
                assertEquals(
                    target,
                    JSONObject(File(report.path).readText()).getLong("targetBytes"),
                )
                assertEquals(hash, PdfAssembly.sha256(source))
                output.copyTo(File(app.cacheDir, "target-size-proof.pdf"), overwrite = true)
                File(report.path)
                    .copyTo(File(app.cacheDir, "target-size-proof.json"), overwrite = true)
            } finally {
                task?.let { id ->
                    repo.dao.task(id)?.let { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, id)
                    }
                }
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before }
                    .forEach { repo.deleteDocument(it) }
            }
        }

    @Test
    fun unattainableTargetReportsPartialAndPreservesSignatureRecordBytes() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val source = File(repo.exports, "target-synthetic-record-${System.nanoTime()}.pdf")
            var task: String? = null
            try {
                PDDocument().use { pdf ->
                    pdf.addPage(PDPage())
                    val form = PDAcroForm(pdf)
                    pdf.documentCatalog.acroForm = form
                    val field = PDSignatureField(form).apply { partialName = "synthetic_record" }
                    form.fields = listOf(field)
                    field.setValue(
                        PDSignature().apply {
                            name = "Synthetic test record, not a verified signature"
                            setByteRange(intArrayOf(0, 10, 20, 10))
                            setContents(byteArrayOf(1, 2, 3))
                        }
                    )
                    pdf.save(source)
                }
                val hash = PdfAssembly.sha256(source)
                val doc = repo.registerPdf(source, "无法达标验收")
                val id =
                    DocumentJobs.enqueue(
                        repo,
                        "PDF",
                        workDataOf(
                            "mode" to "compress",
                            "document" to doc.id,
                            "options" to JSONObject().put("targetBytes", 100).toString(),
                        ),
                    )
                task = repo.dao.allTasks().single { it.workId == id.toString() }.id
                val info =
                    withTimeout(30000) {
                        WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                            it.state.isFinished
                        }
                    }
                assertEquals(WorkInfo.State.SUCCEEDED, info.state)
                assertFalse(info.outputData.getBoolean("targetAchieved", true))
                assertEquals(1, info.outputData.getInt("attempts", 0))
                val row = repo.dao.task(task)!!
                assertEquals(LocalTaskState.PARTIAL, row.state)
                val output = File(info.outputData.getString("path")!!)
                assertEquals(hash, PdfAssembly.sha256(output))
                DocumentJobs.reconcile(repo, row.copy(state = LocalTaskState.RUNNING), info)
                assertEquals(LocalTaskState.PARTIAL, repo.dao.task(row.id)!!.state)
                val derived = repo.dao.documentByPath(output.path)!!
                val report =
                    JSONObject(
                        File(
                                DocumentResources.entries(repo, derived.id)
                                    .single { it.role == "compression-report" }
                                    .path
                            )
                            .readText()
                    )
                assertTrue(report.getBoolean("originalCopied"))
                assertFalse(report.getBoolean("targetAchieved"))
            } finally {
                task?.let { id ->
                    repo.dao.task(id)?.let { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, id)
                    }
                }
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before }
                    .forEach { repo.deleteDocument(it) }
            }
        }
}
