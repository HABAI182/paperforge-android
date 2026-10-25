package cn.paperforge.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.model.PointN
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfDiagnosticsFlowTest {
    @Test
    fun inspectionReportsNativeAndImageResourcesWithoutChangingFile() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val source = File(app.cacheDir, "diagnostics-source.pdf")
            val marked = File(app.repository.exports, "diagnostics-proof-${System.nanoTime()}.pdf")
            try {
                PDDocument().use { pdf ->
                    pdf.documentInformation.title = "Inspection fixture"
                    pdf.documentInformation.author = "PaperForge"
                    repeat(2) { pdf.addPage(PDPage()) }
                    PDPageContentStream(pdf, pdf.getPage(0)).use { c ->
                        c.beginText()
                        c.setFont(PDType1Font.HELVETICA, 18f)
                        c.newLineAtOffset(50f, 700f)
                        c.showText("Native editable text for diagnosis")
                        c.endText()
                    }
                    val bitmap =
                        Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(android.graphics.Color.WHITE)
                        }
                    PDPageContentStream(pdf, pdf.getPage(1)).use {
                        it.drawImage(JPEGFactory.createFromImage(pdf, bitmap), 50f, 50f, 300f, 400f)
                    }
                    bitmap.recycle()
                    val form = PDAcroForm(pdf)
                    pdf.documentCatalog.acroForm = form
                    form.defaultResources =
                        PDResources().apply {
                            put(com.tom_roush.pdfbox.cos.COSName.HELV, PDType1Font.HELVETICA)
                        }
                    form.defaultAppearance = "/Helv 12 Tf 0 g"
                    val amount = PDTextField(form).apply { partialName = "amount" }
                    val signature = PDSignatureField(form).apply { partialName = "unsigned" }
                    form.fields = listOf(amount, signature)
                    amount.widgets.single().apply {
                        rectangle = PDRectangle(50f, 600f, 120f, 20f)
                        page = pdf.getPage(0)
                        pdf.getPage(0).annotations.add(this)
                    }
                    amount.value = "00123"
                    val spec =
                        PDComplexFileSpecification().apply {
                            file = "info.txt"
                            embeddedFile = PDEmbeddedFile(pdf, "attachment".byteInputStream())
                        }
                    pdf.documentCatalog.names =
                        PDDocumentNameDictionary(pdf.documentCatalog).apply {
                            embeddedFiles =
                                PDEmbeddedFilesNameTreeNode().apply {
                                    names = mapOf("info.txt" to spec)
                                }
                        }
                    pdf.save(source)
                }
                PdfMarks.save(
                    app,
                    source,
                    marked,
                    listOf(PdfMark(0, "text", listOf(PointN(.1f, .5f)), text = "中文诊断批注")),
                    editable = true,
                )
                val hash = PdfAssembly.sha256(marked)
                val report = PdfDiagnostics.inspect(marked)
                assertEquals(hash, report.sha256)
                assertEquals(hash, PdfAssembly.sha256(marked))
                assertEquals(2, report.pages.size)
                assertEquals(1, report.imagePages)
                assertEquals(listOf(2), report.ocrCandidates.map { it.page })
                assertEquals(1, report.uniqueImages)
                assertTrue(report.fonts.any { !it.embedded })
                assertTrue(report.fonts.any { it.embedded })
                assertEquals(1, report.fieldTypes["Tx"])
                assertEquals(1, report.fieldTypes["Sig"])
                assertTrue(report.signatures.isEmpty())
                assertEquals(listOf("info.txt"), report.attachments)
                assertEquals(1, report.annotationTypes["FreeText"])
                assertTrue(report.warnings.isEmpty())
                assertEquals("Inspection fixture", report.metadata["标题"])
                assertEquals("not-performed", report.json().getString("signatureValidation"))
                File(app.cacheDir, "diagnostics-report-proof.json")
                    .writeText(report.json().toString(2))
                app.repository.registerPdf(marked, "属性诊断验收 ${System.nanoTime()}")
            } finally {
                source.delete()
            }
        }
}
