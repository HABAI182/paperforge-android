package cn.paperforge.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import cn.paperforge.app.reader.StudyNotes
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfAssemblyFlowTest {
    @Test
    fun workerRestoresDuplexOrderKeepsNativePagesAndMapsCoverContentsLinksAndDraftBackup() =
        runBlocking {
            val app =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as PaperForgeApp
            val repo = app.repository
            val before = repo.dao.allDocuments().map { it.id }.toSet()
            val preferences = LibraryPreferences(app)
            val oldPreferences = preferences.export()
            val oldDraft = AssemblyDraft.read(app)
            val notes = StudyNotes.load(app).map { it.id }.toSet()
            val archive = File(repo.exports, "assembly-backup-${System.nanoTime()}.zip")
            val manifest =
                File(app.filesDir, "assembly-jobs/${UUID.randomUUID()}.json").apply {
                    parentFile!!.mkdirs()
                }
            val prefix = "assembly-${UUID.randomUUID().toString().take(8)}"
            var resultFile: File? = null
            var job: UUID? = null
            suspend fun source(name: String, numbers: List<Int>): DocumentRow {
                val doc = repo.newScan().copy(title = "$prefix-$name")
                val file = File(repo.folder(doc.id), "source.pdf")
                PDDocument().use { pdf ->
                    for (number in numbers) {
                        val page = PDPage(PDRectangle(300f, 500f)).also(pdf::addPage)
                        page.cropBox = PDRectangle(10f, 20f, 280f, 460f)
                        PDPageContentStream(pdf, page).use { c ->
                            c.beginText()
                            c.setFont(PDType1Font.HELVETICA, 14f)
                            c.newLineAtOffset(35f, 430f)
                            c.showText(if (number == 5) "HIDDEN_PAGE_9876" else "PAGE_$number")
                            c.endText()
                        }
                    }
                    if (name == "front") {
                        pdf.getPage(1).rotation = 90
                        val source = pdf.getPage(0)
                        source.annotations.add(
                            PDAnnotationLink().apply {
                                rectangle = PDRectangle(30f, 390f, 100f, 20f)
                                action =
                                    PDActionGoTo().apply {
                                        destination =
                                            PDPageFitDestination().apply { page = pdf.getPage(1) }
                                    }
                            }
                        )
                        source.annotations.add(
                            PDAnnotationLink().apply {
                                rectangle = PDRectangle(30f, 360f, 100f, 20f)
                                action =
                                    PDActionGoTo().apply {
                                        destination =
                                            PDPageFitDestination().apply { page = pdf.getPage(2) }
                                    }
                            }
                        )
                        source.annotations.add(
                            PDAnnotationLink().apply {
                                rectangle = PDRectangle(30f, 330f, 100f, 20f)
                                action =
                                    PDActionURI().apply { uri = "https://example.com/paperforge" }
                            }
                        )
                        val form = PDAcroForm(pdf)
                        pdf.documentCatalog.acroForm = form
                        form.defaultResources =
                            PDResources().apply {
                                put(
                                    com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"),
                                    PDType1Font.HELVETICA,
                                )
                            }
                        form.defaultAppearance = "/Helv 12 Tf 0 g"
                        val field =
                            PDTextField(form).apply {
                                partialName = "amount"
                                defaultAppearance = "/Helv 12 Tf 0 g"
                            }
                        form.fields.add(field)
                        field.widgets.single().apply {
                            rectangle = PDRectangle(35f, 300f, 150f, 25f)
                            page = pdf.getPage(1)
                            pdf.getPage(1).annotations.add(this)
                        }
                        field.value = "00123"
                    }
                    pdf.save(file)
                }
                return doc.copy(kind = "pdf", pdfPath = file.path).also {
                    repo.dao.updateDocument(it)
                }
            }
            try {
                val front = source("front", listOf(1, 3, 5))
                val back = source("back", listOf(4, 2))
                val a = AssemblySource(front.id, "正面", 3, PdfAssembly.sha256(File(front.pdfPath!!)))
                val b = AssemblySource(back.id, "反面", 2, PdfAssembly.sha256(File(back.pdfPath!!)))
                val initial =
                    AssemblyPlan(
                        listOf(a, b),
                        listOf(
                            AssemblyPage(sourceId = a.documentId, page = 0),
                            AssemblyPage(sourceId = a.documentId, page = 1),
                            AssemblyPage(sourceId = b.documentId, page = 0),
                            AssemblyPage(sourceId = b.documentId, page = 1),
                        ),
                        title = "组合验收 00123",
                        subtitle = "正反面与原生文字",
                        cover = true,
                        contents = true,
                        formMode = "APPEARANCE",
                    )
                val mixed = initial.interleave(a.documentId, b.documentId, true)
                assertEquals(
                    listOf(
                        a.documentId to 0,
                        b.documentId to 1,
                        a.documentId to 1,
                        b.documentId to 0,
                    ),
                    mixed.pages.map { it.sourceId to it.page },
                )
                val plan =
                    mixed.copy(
                        pages =
                            mixed.pages +
                                AssemblyPage() +
                                mixed.pages
                                    .first()
                                    .copy(key = UUID.randomUUID().toString(), rotation = 90)
                    )
                AssemblyDraft.write(app, plan)
                manifest.writeText(plan.json().toString())
                val large =
                    plan.copy(
                        pages = List(1200) { AssemblyPage(sourceId = a.documentId, page = it % 2) }
                    )
                assertTrue(large.json().toString().toByteArray().size > 10 * 1024)
                val request =
                    OneTimeWorkRequestBuilder<PdfJobWorker>()
                        .setInputData(
                            workDataOf(
                                "mode" to "assemble",
                                "document" to front.id,
                                "options" to
                                    JSONObject()
                                        .put("manifest", manifest.path)
                                        .put("title", prefix)
                                        .toString(),
                            )
                        )
                        .build()
                job = request.id
                val manager = WorkManager.getInstance(app)
                manager.enqueue(request)
                val completed =
                    withTimeout(60000) {
                        manager.getWorkInfoByIdFlow(request.id).filterNotNull().first {
                            it.state.isFinished
                        }
                    }
                assertEquals(
                    completed.outputData.getString("error"),
                    WorkInfo.State.SUCCEEDED,
                    completed.state,
                )
                val file = File(completed.outputData.getString("path")!!).also { resultFile = it }
                assertTrue(file.isFile)
                file.copyTo(File(app.cacheDir, "assembly-proof.pdf"), overwrite = true)
                assertTrue(manifest.exists()) // Durable task retains its retry input.
                PDDocument.load(file).use { pdf ->
                    assertEquals(8, pdf.numberOfPages)
                    fun text(page: Int) =
                        PDFTextStripper()
                            .apply {
                                startPage = page + 1
                                endPage = page + 1
                            }
                            .getText(pdf)
                            .replace(Regex("\\s+"), "")
                    assertTrue(text(0).contains("组合验收"))
                    assertTrue(text(1).contains("目录"))
                    assertEquals(
                        listOf("PAGE_1", "PAGE_2", "PAGE_3", "PAGE_4"),
                        (2..5).map { page ->
                            Regex("PAGE_\\d").find(text(page))?.value ?: "missing: ${text(page)}"
                        },
                    )
                    assertTrue(text(4).contains("00123"))
                    assertTrue(text(6).isBlank())
                    assertTrue(text(7).contains("PAGE_1"))
                    assertEquals(90, pdf.getPage(4).rotation)
                    assertEquals(90, pdf.getPage(7).rotation)
                    assertEquals(280f, pdf.getPage(2).cropBox.width, .01f)
                    val toc = pdf.getPage(1).annotations.filterIsInstance<PDAnnotationLink>()
                    assertEquals(2, toc.size)
                    assertEquals(
                        2,
                        pdf.pages.indexOf(
                            ((toc.first().action as PDActionGoTo).destination
                                    as PDPageFitDestination)
                                .page
                        ),
                    )
                    val links = pdf.getPage(2).annotations.filterIsInstance<PDAnnotationLink>()
                    assertEquals(2, links.size)
                    assertEquals(
                        4,
                        pdf.pages.indexOf(
                            ((links.first { it.action is PDActionGoTo }.action as PDActionGoTo)
                                    .destination as PDPageFitDestination)
                                .page
                        ),
                    )
                    assertEquals(
                        "https://example.com/paperforge",
                        (links.first { it.action is PDActionURI }.action as PDActionURI).uri,
                    )
                    assertNotNull(pdf.documentCatalog.documentOutline.firstChild)
                    assertFalse(PDFTextStripper().getText(pdf).contains("HIDDEN_PAGE_9876"))
                    assertFalse(
                        pdf.document.objects
                            .mapNotNull { it.`object` as? COSStream }
                            .any { stream ->
                                stream.createInputStream().use {
                                    it.readBytes()
                                        .toString(Charsets.ISO_8859_1)
                                        .contains("HIDDEN_PAGE_9876")
                                }
                            }
                    )
                }
                assertEquals(a.sha256, PdfAssembly.sha256(File(front.pdfPath!!)))
                PDDocument.load(File(front.pdfPath!!)).use {
                    assertEquals(
                        "00123",
                        it.documentCatalog.acroForm.getField("amount").valueAsString,
                    )
                }
                val outputDoc = repo.dao.documentByPath(file.path)!!
                val origin =
                    DocumentResources.entries(repo, outputDoc.id).single {
                        it.role == "assembly-origin"
                    }
                val record = JSONObject(File(origin.path).readText())
                assertEquals(1, record.getInt("formsSavedAsAppearance"))
                assertTrue(record.getInt("omittedLinksToUnselectedPages") >= 1)
                BackupStore.backup(repo, archive)
                BackupStore.restore(repo, Uri.fromFile(archive))
                val restored = AssemblyDraft.read(app)
                assertEquals(6, restored.pages.size)
                assertTrue(restored.sources.none { it.documentId in setOf(front.id, back.id) })
                assertTrue(restored.sources.all { repo.dao.document(it.documentId) != null })
                assertEquals(listOf(a.sha256, b.sha256), restored.sources.map { it.sha256 })
                assertEquals(plan.pages.map { it.page }, restored.pages.map { it.page })
            } finally {
                job?.let { WorkManager.getInstance(app).cancelWorkById(it) }
                manifest.delete()
                archive.delete()
                repo.dao
                    .allDocuments()
                    .filter { it.id !in before }
                    .forEach { repo.deleteDocument(it) }
                resultFile?.let {
                    repo.dao.removeOutput(it.path)
                    it.delete()
                }
                preferences.restore(oldPreferences)
                AssemblyDraft.write(app, oldDraft)
                StudyNotes.load(app)
                    .filter { it.id !in notes }
                    .forEach { StudyNotes.remove(app, it.id) }
            }
        }
}
