package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfArchiveFlowTest {
    @Test
    fun completePhoneProfilesRejectOrdinaryPdfAndApproveQueuedAppearanceCopy() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            assertTrue("Install the updated signed format engine", PdfArchive.available(app))
            val folder = File(app.filesDir, "archive-proof-${UUID.randomUUID()}").apply { mkdirs() }
            val source = File(folder, "original.pdf")
            PDDocument().use { pdf ->
                listOf(PDRectangle(500f, 700f), PDRectangle(300f, 500f)).forEachIndexed {
                    index,
                    rectangle ->
                    val page = PDPage(rectangle)
                    if (index == 1) {
                        page.cropBox = PDRectangle(25f, 30f, 250f, 440f)
                        page.rotation = 270
                    }
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.setNonStrokingColor(.15f, .4f, .75f)
                        stream.addRect(40f, 65f, 160f, 60f)
                        stream.fill()
                        stream.setNonStrokingColor(0f, 0f, 0f)
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 16f)
                        stream.newLineAtOffset(45f, rectangle.height - 100f)
                        stream.showText("ARCHIVE ORIGINAL ${index + 1}")
                        stream.endText()
                    }
                }
                pdf.save(source)
            }
            val sourceHash = PdfAssembly.sha256(source)
            val doc = repo.registerPdf(source, "归档校验验收")
            val tasks = mutableListOf<LocalTaskRow>()
            var outputDoc: DocumentRow? = null
            try {
                suspend fun queue(mode: String): Pair<LocalTaskRow, File> {
                    val row =
                        DocumentJobs.prepare(
                            repo,
                            "PDF",
                            JobInput(
                                JSONObject()
                                    .put("document", doc.id)
                                    .put("mode", mode)
                                    .put("options", "{\"target\":\"2b\"}")
                            ),
                        )
                    tasks += row
                    DocumentJobs.schedule(repo, row)
                    val info =
                        withTimeout(60000) {
                            WorkManager.getInstance(app)
                                .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                                .filterNotNull()
                                .first { it.state.isFinished }
                        }
                    assertEquals(info.outputData.toString(), WorkInfo.State.SUCCEEDED, info.state)
                    return row to File(info.outputData.getString("path")!!)
                }
                val ordinary = queue("archive-check")
                val negative = JSONObject(ordinary.second.readText())
                assertFalse(negative.getBoolean("compliant"))
                assertEquals(sourceHash, negative.getString("sourceSha256"))
                assertTrue(negative.getJSONArray("failedRules").length() > 0)
                assertTrue(negative.getJSONObject("diagnostics").getJSONArray("fonts").length() > 0)
                assertTrue(
                    DocumentResources.entries(repo, doc.id).any { it.role == "archive-report" }
                )
                ordinary.second.copyTo(File(folder, "ordinary-report.json"))
                val archived = queue("archive-create")
                val output = archived.second
                outputDoc = repo.dao.documentByPath(output.path)
                val reportEntry =
                    DocumentResources.entries(repo, outputDoc!!.id).single {
                        it.role == "archive-report"
                    }
                val positive = JSONObject(File(reportEntry.path).readText())
                assertTrue(positive.toString(), positive.getBoolean("compliant"))
                assertEquals("NORMAL", positive.getString("jobEndStatus"))
                assertEquals(PdfAssembly.sha256(output), positive.getString("sourceSha256"))
                assertEquals(sourceHash, positive.getString("originalSha256"))
                assertTrue(positive.getInt("assertions") > 100)
                assertEquals(LocalTaskState.SUCCEEDED, repo.dao.task(archived.first.id)!!.state)
                assertEquals(sourceHash, PdfAssembly.sha256(source))
                output.copyTo(File(folder, "archive-2b.pdf"))
                File(reportEntry.path).copyTo(File(folder, "archive-report.json"))
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("sourceUnchanged", true)
                            .put("negativeRejected", true)
                            .put("queuedCompatibleCopyCompliant", true)
                            .put("engine", positive.getString("engine"))
                            .put("sourceSha256", sourceHash)
                            .toString(2)
                    )
                File(app.filesDir, "archive-latest.txt").writeText(folder.name)
                val demoFile = File(folder, "ui-original.pdf")
                source.copyTo(demoFile)
                val demo = repo.registerPdf(demoFile, "PDF/A 归档 · 验收样例")
                File(folder, "ui-document.json")
                    .writeText(JSONObject().put("id", demo.id).toString())
            } finally {
                tasks.forEach { row -> DocumentJobs.forget(repo, row.id) }
                outputDoc?.let { repo.deleteDocument(it) }
                repo.deleteDocument(doc)
            }
        }
}
