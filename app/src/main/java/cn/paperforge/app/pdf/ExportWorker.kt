package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.SettingKeys
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
        withContext(
            Dispatchers.IO +
                BackgroundWork +
                DocumentJobs.memoryContext(
                    (applicationContext as PaperForgeApp).repository,
                    inputData,
                )
        ) {
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            var leased = false
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
                DocumentJobs.progress(repo, job, job.row.progress, "等待后台资源")
                RuntimeBudget.background.acquire(job.row.id)
                leased = true
                DocumentJobs.progress(repo, job, job.row.progress)
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
                val format = data.getString("format") ?: "PDF"
                val file =
                    File(
                        File(repo.exports, job.row.id).apply { mkdirs() },
                        OutputNames.withExtension(
                            data.getString("fileName") ?: doc.title,
                            if (format == "PDF") "pdf" else "zip",
                        ),
                    )
                output = file
                val request =
                    if (data.getBoolean("searchable", false))
                        cn.paperforge.app.ocr.OcrRequest.decode(
                            data.json.optJSONObject("ocrRequest"),
                            (applicationContext as PaperForgeApp).ocrEngine.id,
                        )
                    else null
                val engine = request?.bind((applicationContext as PaperForgeApp).ocrEngine)
                if (data.getBoolean("searchable", false)) {
                    val pages = repo.dao.pages(doc.id)
                    pages.forEachIndexed { index, page ->
                        val cached =
                            page.ocrLayout?.let {
                                runCatching { cn.paperforge.app.model.OcrLayout.decode(it) }
                                    .getOrNull()
                            }
                        if (
                            cached == null ||
                                !cached.matches(page) ||
                                (cached.requestSignature != request!!.signature &&
                                    cached.lines.none { it.corrected })
                        )
                            repo.cacheOcr(
                                page,
                                checkNotNull(engine)
                                    .layout(
                                        page,
                                        cn.paperforge.app.UserSettings[SettingKeys.OcrLanguage] !=
                                            "LATIN",
                                    ),
                            )
                        DocumentJobs.checkpoint(repo, job, page.id, page.revision)
                        report((index + 1) * 30 / pages.size, "文字层 ${index+1}/${pages.size} 页")
                    }
                }
                val pages = repo.dao.pages(doc.id)
                val pageStages = JobFileStages(repo, job)
                val fingerprint =
                    listOf(
                            pages.joinToString { "${it.id}:${it.revision}" },
                            data.getString("paper"),
                            data.getString("resolution"),
                            data.getInt("quality", 90),
                            data.getBoolean("searchable", false),
                            if (data.getBoolean("searchable", false)) PdfTextLayer.VERSION else "",
                            request?.signature.orEmpty(),
                            pages.joinToString { JobFileStages.digest(it.ocrLayout.orEmpty()) },
                        )
                        .joinToString("|")
                val cached =
                    org.json
                        .JSONObject(job.row.payload)
                        .getJSONObject("checkpoint")
                        .optJSONObject("render-stage")
                val stage =
                    if (format == "PDF") file
                    else if (
                        cached?.optString("fingerprint") == fingerprint &&
                            cached.optString("path").isNotBlank()
                    )
                        File(cached.getString("path"))
                    else File(file.parentFile, "render-source.pdf")
                if (
                    format == "PDF" ||
                        cached?.optString("fingerprint") != fingerprint ||
                        !stage.isFile ||
                        stage.length() == 0L
                ) {
                    ScanPageStages.scan(
                        applicationContext,
                        pages,
                        stage,
                        pageStages,
                        data.getString("paper") ?: "Auto",
                        data.getInt("quality", 90),
                        data.getBoolean("searchable", false),
                        data.getString("resolution") ?: "STANDARD",
                        engine,
                        lossless = format != "PDF",
                    ) {
                        report(
                            if (format != "PDF") it * 60 / 100
                            else if (data.getBoolean("searchable", false)) 30 + it * 70 / 100
                            else it
                        )
                    }
                    if (format != "PDF")
                        DocumentJobs.checkpointFile(
                            repo,
                            job,
                            "render-stage",
                            stage,
                            org.json.JSONObject().put("fingerprint", fingerprint),
                        )
                }
                if (format != "PDF")
                    PdfTools.images(
                        stage,
                        file,
                        "",
                        format.lowercase(java.util.Locale.ROOT),
                        quality = data.getInt("quality", 90),
                        dpi =
                            cn.paperforge.app.imaging.ScanConfig.dpi(
                                data.getString("resolution") ?: "STANDARD"
                            ),
                        stages = pageStages,
                    ) {
                        report(60 + it * 40 / 100, "正在打包页面图片")
                    }
                ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        if (format == "PDF") repo.registerPdf(file, doc.title, listed = false)
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
            } finally {
                if (leased) RuntimeBudget.background.release()
            }
        }
}
