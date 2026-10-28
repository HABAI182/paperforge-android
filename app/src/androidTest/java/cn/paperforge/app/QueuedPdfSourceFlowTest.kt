package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuedPdfSourceFlowTest {
    @Test
    fun waitingPdfTaskUsesItsRetainedSourceAfterLibraryReplacement() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val folder =
                File(app.filesDir, "queued-pdf-proof-${UUID.randomUUID()}").apply { mkdirs() }
            fun source(name: String): File =
                File(folder, "$name.pdf").also { file ->
                    PDDocument().use { pdf ->
                        val page = PDPage()
                        pdf.addPage(page)
                        PDPageContentStream(pdf, page).use { stream ->
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, 20f)
                            stream.newLineAtOffset(50f, 600f)
                            stream.showText(name)
                            stream.endText()
                        }
                        pdf.save(file)
                    }
                }
            val original = source("QUEUED_ORIGINAL")
            val replacement = source("LATER_REPLACEMENT")
            val doc = repo.registerPdf(original, "排队原件固定验收")
            val row =
                DocumentJobs.prepare(
                    repo,
                    "PDF",
                    JobInput(
                        JSONObject()
                            .put("document", doc.id)
                            .put("mode", "extract")
                            .put("options", "{\"range\":\"1\"}")
                    ),
                )
            var held = false
            var registered: DocumentRow? = null
            try {
                RuntimeBudget.background.acquire()
                held = true
                DocumentJobs.schedule(repo, row)
                withTimeout(15000) {
                    repo.dao.observeTasks().first { tasks ->
                        tasks.any { it.id == row.id && it.detail == "等待后台资源" }
                    }
                }
                repo.dao.putDocument(doc.copy(pdfPath = replacement.path))
                assertEquals(
                    original.path,
                    repo.dao.task(row.id)!!.resourceEntries().single { it.id == "source" }.path,
                )
                RuntimeBudget.background.release()
                held = false
                val info =
                    withTimeout(15000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(UUID.fromString(row.workId))
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                assertEquals(WorkInfo.State.SUCCEEDED, info.state)
                val output = File(info.outputData.getString("path")!!)
                registered = repo.dao.documentByPath(output.path)
                val text = PdfEngine.text(output)
                assertTrue(text.contains("QUEUED_ORIGINAL"))
                assertFalse(text.contains("LATER_REPLACEMENT"))
                assertEquals(replacement.path, repo.dao.document(doc.id)!!.pdfPath)
                output.copyTo(File(folder, "actual-result.pdf"))
                File(folder, "proof.json")
                    .writeText(
                        JSONObject()
                            .put("actualText", text)
                            .put("libraryReplacementPreserved", true)
                            .put("taskRetainedOriginal", true)
                            .put("workManager", info.state.name)
                            .toString(2)
                    )
                File(app.filesDir, "queued-pdf-latest.txt").writeText(folder.name)
            } finally {
                if (held) RuntimeBudget.background.release()
                repo.dao.task(row.id)?.let {
                    if (it.state in LocalTaskRow.active) DocumentJobs.cancel(repo, it)
                }
                DocumentJobs.forget(repo, row.id)
                registered?.let { repo.deleteDocument(it) }
                repo.deleteDocument(doc)
            }
        }
}
