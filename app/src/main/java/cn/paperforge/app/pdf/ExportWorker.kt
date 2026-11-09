package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.data.*
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
                suspend fun report(percent: Int, detail: String = "正在生成 PDF") {
                    DocumentJobs.progress(repo, job, percent, detail)
                    setProgress(workDataOf("progress" to percent))
                }
                val documentId = data.getString("document") ?: error("缺少文档")
                var lastAdvance = android.os.SystemClock.elapsedRealtime()
                var previousCaptureProgress = ""
                var previousWait = ""
                while (true) {
                    // Every page waits for its finished appearance: a page exported at 20% has only its
                    // framing, so it came out unflattened, with curved rules and raw paper.
                    val waiting =
                        repo.dao.allTasks().filter {
                            it.type == "CAPTURE" &&
                                it.documentId == documentId &&
                                it.state in LocalTaskRow.active
                        }
                    if (waiting.isEmpty()) break
                    val total = repo.dao.pages(documentId).size.coerceAtLeast(waiting.size)
                    val ready = (total - waiting.size).coerceAtLeast(0)
                    val signature = waiting.joinToString { "${it.id}:${it.progress}" }
                    if (signature != previousCaptureProgress) {
                        lastAdvance = android.os.SystemClock.elapsedRealtime()
                        previousCaptureProgress = signature
                    }
                    val percent =
                        ((ready * 100 + waiting.sumOf { it.progress.coerceIn(0, 99) }) * 12 /
                                (total.coerceAtLeast(1) * 100))
                            .coerceIn(0, 12)
                    val detail = "准备扫描页面 $ready/$total，可在任务中心查看"
                    val displayState = "$detail:$percent"
                    if (displayState != previousWait) {
                        report(percent, detail)
                        previousWait = displayState
                    }
                    check(android.os.SystemClock.elapsedRealtime() - lastAdvance < 120000) {
                        "部分扫描页尚未保存或处理停滞，请到任务中心查看具体页面后重试导出"
                    }
                    kotlinx.coroutines.delay(150)
                    DocumentJobs.checkCurrent(repo, job)
                }
                RuntimeBudget.background.acquire(job.row.id)
                leased = true
                val doc =
                    repo.dao.document(data.getString("document") ?: error("缺少文档")) ?: error("草稿不存在")
                val format = data.getString("format") ?: "PDF"
                val file =
                    File(
                        File(repo.exports, job.row.id).apply { mkdirs() },
                        OutputNames.withExtension(
                            data.getString("fileName") ?: data.getString("title") ?: doc.title,
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
                            PdfImageEncoder.VERSION,
                            cn.paperforge.app.imaging.ScanConfig.fingerprint(),
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
                        // v200 feedback 1: an exported PDF used to be registered with listed=false,
                        // and every library query filters listed=1 ("最近" and "全部" both do), so
                        // the finished PDF existed on disk but could not be found in the app. It is
                        // now a normal library document under the export name, which also gives the
                        // result dialog a real "在文档库打开" destination.
                        if (format == "PDF")
                            ExportRegistration.pdf(
                                repo,
                                file,
                                data.getString("title") ?: doc.title,
                            )
                        repo.recordOutput(file, doc)
                        DocumentJobs.complete(repo, job, file)
                        published = true
                    }
                }
                Result.success(
                    workDataOf(
                        "path" to file.path,
                    )
                )
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
