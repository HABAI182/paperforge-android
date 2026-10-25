package cn.paperforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import cn.paperforge.app.data.*
import cn.paperforge.app.pdf.PdfAssembly
import com.tom_roush.pdfbox.pdmodel.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundBudgetFlowTest {
    @Test
    fun queuedWorkerCanBeCancelledWithoutLeakingBackgroundSlot() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val source = File(repo.exports, "budget-${System.nanoTime()}.pdf")
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.save(source)
            }
            val doc = repo.registerPdf(source, "后台预算验收")
            val hash = PdfAssembly.sha256(source)
            var held = false
            var taskId: String? = null
            try {
                RuntimeBudget.background.acquire()
                held = true
                val id =
                    DocumentJobs.enqueue(
                        repo,
                        "PDF",
                        workDataOf(
                            "mode" to "extract",
                            "document" to doc.id,
                            "options" to "{\"range\":\"1\"}",
                        ),
                    )
                val row =
                    withTimeout(10000) {
                        repo.dao
                            .observeTasks()
                            .first { rows ->
                                rows.any { it.workId == id.toString() && it.detail == "等待后台资源" }
                            }
                            .single { it.workId == id.toString() }
                    }
                taskId = row.id
                DocumentJobs.cancel(repo, row)
                withTimeout(10000) {
                    WorkManager.getInstance(app).getWorkInfoByIdFlow(id).filterNotNull().first {
                        it.state.isFinished
                    }
                }
                assertEquals(LocalTaskState.CANCELLED, repo.dao.task(row.id)!!.state)
                RuntimeBudget.background.release()
                held = false
                val retry = DocumentJobs.retry(repo, row)
                val finished =
                    withTimeout(10000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(retry)
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
                assertEquals(hash, PdfAssembly.sha256(source))
                assertEquals(1, RuntimeBudget.background.availablePermits)
            } finally {
                if (held) RuntimeBudget.background.release()
                taskId?.let {
                    repo.dao.task(it)?.let { row ->
                        if (row.state in LocalTaskRow.active) DocumentJobs.cancel(repo, row)
                        DocumentJobs.forget(repo, it)
                    }
                }
                repo.deleteDocument(doc)
            }
        }
}
