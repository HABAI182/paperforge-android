package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.OcrRequest
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*

class AutoExportWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO + BackgroundWork) {
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            var leased = false
            try {
                val job =
                    DocumentJobs.start(repo, "AUTO_EXPORT", inputData, id)
                        ?: return@withContext Result.failure()
                handle = job
                if (job.completed)
                    return@withContext Result.success(
                        workDataOf("path" to job.result!!.path, "mode" to "AUTO_EXPORT")
                    )
                val rule = AutoExportRule.decode(job.input.json.getJSONObject("autoRule"))
                suspend fun skip(detail: String): Result {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        repo.dao.taskState(job.row.id, LocalTaskState.SUCCEEDED, 100, detail, null)
                    }
                    return Result.success(
                        workDataOf("skipped" to true, "detail" to detail, "mode" to "AUTO_EXPORT")
                    )
                }
                suspend fun current(): Boolean {
                    if (job.input.getBoolean("manual", false)) return true
                    val active =
                        AutoExportRules.all().firstOrNull { it.id == rule.id && it.enabled }
                            ?: return false
                    val signal =
                        AutoExportCoordinator.signals(repo).first().firstOrNull {
                            it.document.id == job.row.documentId
                        } ?: return false
                    return signal.ready &&
                        active.matches(signal.document) &&
                        active.fingerprint(signal.fingerprint) ==
                            job.input.getString("automationFingerprint")
                }
                if (!current()) return@withContext skip("已跳过：文档或规则已变化，后续变化由新任务处理")
                DocumentJobs.progress(repo, job, 0, "等待后台资源")
                RuntimeBudget.background.acquire()
                leased = true
                if (!current()) return@withContext skip("已跳过：等待期间文档或规则已变化")
                val snapshot =
                    JSONObject(
                        File(job.input.getString("snapshot") ?: error("自动导出快照缺失")).readText()
                    )
                val document = DocumentCodec.document(snapshot.getJSONObject("document"))
                val directory =
                    File(repo.context.filesDir, "job-items/${job.row.id}").apply { mkdirs() }
                var source = document.pdfPath?.let(::File)
                if (document.kind != "pdf") {
                    val searchable = rule.export.optBoolean("searchable", false)
                    val router =
                        if (searchable) (applicationContext as PaperForgeApp).ocrEngine else null
                    val request =
                        router?.let {
                            OcrRequest.decode(job.input.json.optJSONObject("ocrRequest"), it.id)
                        }
                    val baseSignature =
                        AutoExportRule.digest(
                            "${job.input.getString("sourceFingerprint")}|${rule.export}|${request?.signature.orEmpty()}"
                        )
                    val cached =
                        JSONObject(checkNotNull(repo.dao.task(job.row.id)).payload)
                            .getJSONObject("checkpoint")
                            .optJSONObject("auto-source-pdf")
                    source =
                        cached
                            ?.takeIf { it.optString("signature") == baseSignature }
                            ?.optString("path")
                            ?.let(::File)
                            ?.takeIf { it.isFile && it.length() > 0 }
                    if (source == null) {
                        source = File(directory, "source-${baseSignature.take(20)}.pdf")
                        val settings = rule.export
                        val resolution =
                            settings.optString("resolution", "STANDARD").let {
                                if (it == "CUSTOM") "DPI:${settings.optInt("customDpi",300)}"
                                else it
                            }
                        PdfEngine.scan(
                            HistoryState.pages(snapshot.toString()),
                            source!!,
                            settings.optString("paper", "Auto"),
                            settings.optInt("quality", 90),
                            applicationContext,
                            searchable,
                            resolution,
                            request?.bind(checkNotNull(router)),
                        ) { percent ->
                            DocumentJobs.progress(repo, job, percent / 2, "生成扫描 PDF")
                            setProgress(workDataOf("progress" to percent / 2))
                        }
                        DocumentJobs.checkpointFile(
                            repo,
                            job,
                            "auto-source-pdf",
                            source!!,
                            JSONObject().put("signature", baseSignature),
                        )
                    }
                }
                require(source?.isFile == true) { "保留的输入文件缺失" }
                val input =
                    JobInput(
                        JSONObject(job.input.json.toString())
                            .put("inputs", JSONArray(listOf(source!!.path)))
                            .put("names", JSONArray(listOf(document.title)))
                    )
                val result =
                    RecipeJobs.run(repo, JobHandle(job.row, input)) { percent, detail ->
                        DocumentJobs.progress(repo, job, percent, detail)
                        setProgress(workDataOf("progress" to percent))
                    }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        // Keep automatic outputs in export history; they cannot re-enter matching
                        // rules.
                        repo.recordOutput(result, repo.dao.document(document.id))
                        DocumentJobs.complete(repo, job, result, "自动导出完成 · ${rule.name}")
                    }
                }
                Result.success(workDataOf("path" to result.path, "mode" to "AUTO_EXPORT"))
            } catch (cancel: CancellationException) {
                DocumentJobs.fail(repo, handle, cancel, true)
                throw cancel
            } catch (error: Exception) {
                DocumentJobs.fail(repo, handle, error)
                Result.failure(workDataOf("error" to (error.message ?: "自动导出失败，已完成步骤保留")))
            } finally {
                if (leased) RuntimeBudget.background.release()
            }
        }
}
