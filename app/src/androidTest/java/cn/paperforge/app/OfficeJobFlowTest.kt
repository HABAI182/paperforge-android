package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficeJobFlowTest {
    @Test
    fun backgroundOfficePdfRetainsOriginalAndReusesCompletedStage() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val input = File(app.filesDir, "office-job-proof.docx")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("office-job-input.docx")
            .use { original -> input.outputStream().use { original.copyTo(it) } }
        val sourceHash = PdfAssembly.sha256(input)
        val work =
            DocumentJobs.enqueue(
                repo,
                "CONVERT",
                JobInput(
                    JSONObject()
                        .put("kind", "OFFICE")
                        .put("name", "Office 后台验收.docx")
                        .put("inputs", JSONArray(listOf(input.path)))
                        .put("names", JSONArray(listOf("Office 后台验收.docx")))
                        .put("options", JSONObject().put("format", "pdf").toString())
                ),
            )
        val info =
            withTimeout(90000) {
                WorkManager.getInstance(app).getWorkInfoByIdFlow(work).filterNotNull().first {
                    it.state.isFinished
                }
            }
        assertEquals(info.outputData.getString("error"), WorkInfo.State.SUCCEEDED, info.state)
        val output = File(info.outputData.getString("path")!!)
        val text =
            PDDocument.load(output).use {
                assertEquals(2, it.numberOfPages)
                PDFTextStripper().getText(it)
            }
        assertTrue(text.contains("00123"))
        assertTrue(text.contains("200.50"))
        assertTrue(text.replace(Regex("\\s+"), "").contains("纸匠Office排版验收"))
        assertEquals(sourceHash, PdfAssembly.sha256(input))
        val row = checkNotNull(repo.dao.task(info.outputData.getString("taskId")!!))
        assertEquals(LocalTaskState.SUCCEEDED, row.state)
        assertTrue(row.resourceEntries().any { it.id == "office-output" && it.path == output.path })
        val reused =
            OfficeJobs.convert(
                repo,
                JobHandle(row, JobInput(JSONObject(row.payload).getJSONObject("input"))),
            ) { _, _ ->
                error("已有结果不应重跑引擎")
            }
        assertEquals(output.path, reused.path)
        assertNotNull(repo.dao.output(output.path))
        File(app.cacheDir, "office-job-proof.json")
            .writeText(
                JSONObject()
                    .put("task", row.id)
                    .put("output", output.path)
                    .put("sourceSha256", sourceHash)
                    .put("pages", 2)
                    .put("completedStageReused", true)
                    .put("text", text)
                    .toString(2)
            )
    }
}
