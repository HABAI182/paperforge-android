package cn.paperforge.app.pdf

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.OcrLayout
import java.io.File
import kotlinx.coroutines.*

class OcrDocumentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO + BackgroundWork) {
            val app = applicationContext as PaperForgeApp
            val repo = app.repository
            var handle: JobHandle? = null
            var leased = false
            var output: File? = null
            var published = false
            try {
                val job =
                    DocumentJobs.start(repo, "OCR", inputData, id)
                        ?: return@withContext Result.failure(workDataOf("error" to "任务已停止"))
                handle = job
                if (job.completed)
                    return@withContext Result.success(
                        workDataOf("path" to job.result!!.path, "mode" to "OCR")
                    )
                DocumentJobs.progress(repo, job, job.row.progress, "等待后台资源")
                RuntimeBudget.background.acquire()
                leased = true
                val doc =
                    repo.dao.document(job.input.getString("document") ?: "") ?: error("来源文档已移除")
                val pages = repo.dao.pages(doc.id)
                require(pages.isNotEmpty()) { "没有可识别的页面" }
                val request =
                    cn.paperforge.app.ocr.OcrRequest.decode(
                        job.input.json.optJSONObject("ocrRequest"),
                        app.ocrEngine.id,
                        job.input.getBoolean("chinese", true),
                    )
                val engine = request.bind(app.ocrEngine)
                val text = mutableListOf<String>()
                pages.forEachIndexed { index, page ->
                    ensureActive()
                    DocumentJobs.checkCurrent(repo, job)
                    val cached =
                        page.ocrLayout
                            ?.let { runCatching { OcrLayout.decode(it) }.getOrNull() }
                            ?.takeIf {
                                it.sourceRevision == page.revision &&
                                    (it.requestSignature == request.signature ||
                                        it.lines.any { line -> line.corrected })
                            }
                    val result = cached ?: repo.cacheOcr(page, engine.layout(page)).second
                    text += "第 ${index+1} 页\n${result.text}"
                    DocumentJobs.checkpoint(repo, job, page.id, page.revision)
                    val percent = (index + 1) * 100 / pages.size
                    DocumentJobs.progress(repo, job, percent, "已识别 ${index+1}/${pages.size} 页")
                    setProgress(workDataOf("progress" to percent))
                }
                val file = File(repo.exports, "PaperForge-OCR-${job.row.id}.txt")
                output = file
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(text.joinToString("\n\n").toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
                ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        repo.recordOutput(file, doc)
                        DocumentJobs.complete(repo, job, file, "${pages.size} 页文字已保存")
                        published = true
                    }
                }
                Result.success(workDataOf("path" to file.path, "mode" to "OCR"))
            } catch (cancel: CancellationException) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, cancel, true)
                throw cancel
            } catch (failure: Exception) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, failure)
                Result.failure(workDataOf("error" to (failure.message ?: "识别未完成")))
            } finally {
                if (leased) RuntimeBudget.background.release()
            }
        }
}

class SaveDocumentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            try {
                val job =
                    DocumentJobs.start(repo, "SAVE", inputData, id)
                        ?: return@withContext Result.failure(workDataOf("error" to "保存已停止"))
                handle = job
                if (job.completed)
                    return@withContext Result.success(
                        workDataOf("path" to job.result!!.path, "mode" to "SAVE")
                    )
                val file = File(job.input.getString("path") ?: error("本地结果已移除"))
                require(file.isFile && file.length() > 0) { "本地结果已移除，请重新生成" }
                val uri = Uri.parse(job.input.getString("uri") ?: error("请重新选择保存位置"))
                DocumentJobs.progress(repo, job, 5, "正在写入所选位置")
                repo.saveOutput(file, uri)
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.complete(repo, job, file, "已写入并核对内容")
                    }
                }
                Result.success(workDataOf("path" to file.path, "mode" to "SAVE"))
            } catch (cancel: CancellationException) {
                DocumentJobs.fail(repo, handle, cancel, true)
                throw cancel
            } catch (failure: Exception) {
                DocumentJobs.fail(repo, handle, failure)
                Result.failure(workDataOf("error" to (failure.message ?: "保存失败，本地结果保留")))
            }
        }
}
