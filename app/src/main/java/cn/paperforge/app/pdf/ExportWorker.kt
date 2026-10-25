package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            var output: File? = null
            var published = false
            try {
                val job =
                    DocumentJobs.start(repo, "SCAN_EXPORT", inputData, id)
                        ?: return@withContext Result.failure(workDataOf("error" to "任务已停止"))
                handle = job
                val data = job.input
                if (job.completed)
                    return@withContext Result.success(workDataOf("path" to job.result!!.path))
                suspend fun report(percent: Int, detail: String = "正在生成 PDF") {
                    DocumentJobs.progress(repo, job, percent, detail)
                    setProgress(workDataOf("progress" to percent))
                }
                var doc =
                    repo.dao.document(data.getString("document") ?: error("缺少文档")) ?: error("草稿不存在")
                data.getString("title")?.let { title ->
                    repo.rename(doc, title)
                    doc = doc.copy(title = safeFileName(title))
                }
                val file =
                    File(repo.exports, "${safeFileName(doc.title)}-${id.toString().take(8)}.pdf")
                output = file
                if (data.getBoolean("searchable", false)) {
                    val engine = (applicationContext as PaperForgeApp).ocrEngine
                    val pages = repo.dao.pages(doc.id)
                    pages.forEachIndexed { index, page ->
                        val cached =
                            page.ocrLayout?.let {
                                runCatching { cn.paperforge.app.model.OcrLayout.decode(it) }
                                    .getOrNull()
                            }
                        if (
                            cached == null ||
                                cached.sourceRevision != page.revision ||
                                (cached.engineId != engine.id && cached.lines.none { it.corrected })
                        )
                            repo.cacheOcr(
                                page,
                                engine.layout(
                                    page,
                                    cn.paperforge.app.UserSettings.string(
                                        "ocrLanguage",
                                        "CHINESE",
                                    ) != "LATIN",
                                ),
                            )
                        DocumentJobs.checkpoint(repo, job, page.id, page.revision)
                        report((index + 1) * 30 / pages.size, "文字层 ${index+1}/${pages.size} 页")
                    }
                }
                PdfEngine.scan(
                    repo.dao.pages(doc.id),
                    file,
                    data.getString("paper") ?: "Auto",
                    data.getInt("quality", 90),
                    applicationContext,
                    data.getBoolean("searchable", false),
                    data.getString("resolution") ?: "STANDARD",
                ) {
                    report(if (data.getBoolean("searchable", false)) 30 + it * 70 / 100 else it)
                }
                ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        repo.registerPdf(file, doc.title, listed = false)
                        repo.recordOutput(file, doc)
                        DocumentJobs.complete(repo, job, file)
                        published = true
                    }
                }
                Result.success(workDataOf("path" to file.path))
            } catch (e: CancellationException) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, e, true)
                throw e
            } catch (e: Exception) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, e)
                Result.failure(workDataOf("error" to (e.message ?: "PDF 导出失败")))
            }
        }
}
