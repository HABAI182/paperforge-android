package cn.paperforge.app.pdf

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.*
import org.json.*

class ConversionJobWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO + BackgroundWork) {
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            var leased = false
            var output: File? = null
            var published = false
            try {
                val job =
                    DocumentJobs.start(repo, "CONVERT", inputData, id)
                        ?: return@withContext Result.failure(workDataOf("error" to "转换已停止"))
                handle = job
                if (job.completed)
                    return@withContext Result.success(
                        workDataOf(
                            "path" to job.result!!.path,
                            "mode" to
                                when (job.input.getString("kind")) {
                                    "TABLE_ANALYZE" -> "TABLE_REVIEW"
                                    "MARKUP" -> "MARKUP"
                                    "PDF_DIFF" -> "PDF_DIFF"
                                    else -> "CONVERT"
                                },
                            "taskId" to job.row.id,
                        )
                    )
                require(!job.input.getBoolean("draft", false)) { "请先完成草稿编辑" }
                DocumentJobs.progress(repo, job, job.row.progress, "等待后台资源")
                RuntimeBudget.background.acquire()
                leased = true
                val inputs = job.input.getStringArray("inputs")?.map(::File).orEmpty()
                val names = job.input.getStringArray("names").orEmpty()
                val options = JSONObject(job.input.getString("options") ?: "{}")
                val title = job.input.getString("name") ?: "PaperForge"
                var failed = 0
                var completed = 0
                val file: File
                when (job.input.getString("kind")) {
                    "RECIPE" -> {
                        file =
                            RecipeJobs.run(repo, job) { percent, detail ->
                                DocumentJobs.progress(repo, job, percent, detail)
                                setProgress(workDataOf("progress" to percent))
                            }
                        // Stage resources belong to this durable task even if publication stops.
                        completed = 1
                    }
                    "OFFICE" -> {
                        file =
                            OfficeJobs.convert(repo, job) { percent, detail ->
                                DocumentJobs.progress(repo, job, percent, detail)
                                setProgress(workDataOf("progress" to percent))
                            }
                        output = file
                        completed = 1
                    }
                    "PDF_DIFF" -> {
                        file = File(repo.exports, "PaperForge-comparison-${job.row.workId}.zip")
                        output = file
                        PdfComparison.run(repo, job, file) { percent, detail ->
                            DocumentJobs.progress(repo, job, percent, detail)
                            setProgress(workDataOf("progress" to percent))
                        }
                        completed = 1
                    }
                    "MARKUP" -> {
                        val epub = options.optString("target") == "EPUB"
                        file =
                            File(
                                repo.exports,
                                "PaperForge-markup-${job.row.workId}.${if (epub) "epub" else "zip"}",
                            )
                        output = file
                        DocumentJobs.progress(repo, job, 10, "正在转换文档结构与资源")
                        if (epub) {
                            val router = (applicationContext as PaperForgeApp).ocrEngine
                            EpubDocuments.write(
                                applicationContext,
                                inputs.single(),
                                title,
                                file,
                                options,
                                cn.paperforge.app.ocr.OcrRequest.decode(
                                        job.input.json.optJSONObject("ocrRequest"),
                                        router.id,
                                    )
                                    .bind(router),
                            ) { percent, detail ->
                                DocumentJobs.progress(repo, job, percent, detail)
                                setProgress(workDataOf("progress" to percent))
                            }
                        } else
                            MarkupDocuments.convert(
                                applicationContext,
                                inputs.single(),
                                title,
                                file,
                                options.optString("target", "HTML"),
                                TextPdfOptions.decode(
                                    options.optJSONObject("print") ?: JSONObject()
                                ),
                            )
                        completed = 1
                    }
                    "TABLE_ANALYZE" -> {
                        file =
                            TableJobs.analyze(
                                repo,
                                job,
                                (applicationContext as PaperForgeApp).ocrEngine,
                            ) { percent, detail ->
                                DocumentJobs.progress(repo, job, percent, detail)
                                setProgress(workDataOf("progress" to percent))
                            }
                        output = file
                        completed = TableData.decode(JSONObject(file.readText())).size
                    }
                    "TABLE_EXPORT" -> {
                        file = TableJobs.export(repo, job)
                        output = file
                        completed = 1
                    }
                    "TEXT_PDF" -> {
                        file =
                            File(
                                repo.exports,
                                "${safeFileName(title.substringBeforeLast('.'))}-${id.toString().take(8)}.pdf",
                            )
                        output = file
                        val edited = File(job.input.getString("edited") ?: error("正文草稿缺失"))
                        DocumentJobs.progress(repo, job, 10, "正在排版文字")
                        LocalConversions.textToPdf(
                            applicationContext,
                            Uri.fromFile(edited),
                            "edited.txt",
                            file,
                            options = TextPdfOptions.decode(options),
                        )
                        completed = 1
                    }
                    "LONG_IMAGES" -> {
                        file = File(repo.exports, "PaperForge-long-${id.toString().take(8)}.zip")
                        output = file
                        ImageLongImages.convert(
                            inputs,
                            names.toList(),
                            file,
                            LongImageOptions.decode(options),
                            LongImageDraft.decode(job.row).orderedSeams(),
                        ) { percent ->
                            DocumentJobs.progress(repo, job, percent, "正在拼接长图")
                            setProgress(workDataOf("progress" to percent))
                        }
                        completed = inputs.size
                    }
                    "IMAGES" -> {
                        require(inputs.isNotEmpty()) { "没有选择图片" }
                        val settings = ImageConversionOptions.decode(options)
                        val items =
                            File(applicationContext.filesDir, "job-items/${job.row.id}").apply {
                                mkdirs()
                            }
                        val successes = mutableListOf<Pair<Int, File>>()
                        val report = JSONArray()
                        for ((index, input) in inputs.withIndex()) {
                            ensureActive()
                            DocumentJobs.checkCurrent(repo, job)
                            val percent = index * 85 / inputs.size
                            DocumentJobs.progress(
                                repo,
                                job,
                                percent,
                                "转换 ${index+1}/${inputs.size}",
                            )
                            setProgress(workDataOf("progress" to percent))
                            try {
                                val fingerprint =
                                    PdfAssembly.sha256(input) + settings.json().toString()
                                val latest = checkNotNull(repo.dao.task(job.row.id))
                                val cached =
                                    JSONObject(latest.payload)
                                        .getJSONObject("checkpoint")
                                        .optJSONObject("item-$index")
                                val cachedFile =
                                    cached?.optString("path")?.let(::File)?.takeIf {
                                        it.isFile &&
                                            it.length() > 0 &&
                                            cached.optString("fingerprint") == fingerprint
                                    }
                                val encoded =
                                    cachedFile
                                        ?: File(
                                                items,
                                                "$index-${settings.format}.${settings.format}",
                                            )
                                            .also {
                                                LocalConversions.image(
                                                    applicationContext,
                                                    Uri.fromFile(input),
                                                    it,
                                                    settings,
                                                )
                                                withContext(NonCancellable) {
                                                    repo.database.withTransaction {
                                                        DocumentJobs.checkCurrent(repo, job)
                                                        val row =
                                                            checkNotNull(repo.dao.task(job.row.id))
                                                        val payload = JSONObject(row.payload)
                                                        payload
                                                            .getJSONObject("checkpoint")
                                                            .put(
                                                                "item-$index",
                                                                JSONObject()
                                                                    .put("path", it.path)
                                                                    .put("fingerprint", fingerprint),
                                                            )
                                                        repo.dao.putTask(
                                                            row.copy(
                                                                payload = payload.toString(),
                                                                resources =
                                                                    DocumentResources.encode(
                                                                            row.resourceEntries()
                                                                                .filterNot { r ->
                                                                                    r.id ==
                                                                                        "item-$index"
                                                                                } +
                                                                                PageResource(
                                                                                    "item-$index",
                                                                                    "conversion-checkpoint",
                                                                                    it.path,
                                                                                )
                                                                        )
                                                                        .toString(),
                                                                updated = System.currentTimeMillis(),
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                successes += index to encoded
                                report.put(
                                    JSONObject()
                                        .put("index", index + 1)
                                        .put("source", names.getOrNull(index) ?: input.name)
                                        .put("status", "completed")
                                        .put("reused", cachedFile != null)
                                )
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                failed++
                                report.put(
                                    JSONObject()
                                        .put("index", index + 1)
                                        .put("source", names.getOrNull(index) ?: input.name)
                                        .put("status", "failed")
                                        .put("error", failure.message)
                                )
                            }
                        }
                        require(successes.isNotEmpty()) { "所有图片均未转换成功；输入保留，可在任务中心重试" }
                        completed = successes.size
                        val extension = if (inputs.size > 1) "zip" else settings.format
                        file =
                            File(
                                repo.exports,
                                "PaperForge-images-${id.toString().take(8)}.$extension",
                            )
                        output = file
                        val part = File(file.path + ".part")
                        try {
                            if (inputs.size == 1)
                                successes.single().second.inputStream().use { source ->
                                    java.io.FileOutputStream(part).use { target ->
                                        source.copyTo(target)
                                        target.fd.sync()
                                    }
                                }
                            else {
                                ZipOutputStream(part.outputStream().buffered()).use { zip ->
                                    for ((index, image) in successes) {
                                        ensureActive()
                                        zip.putNextEntry(
                                            ZipEntry(
                                                "image-%03d.%s".format(index + 1, settings.format)
                                            )
                                        )
                                        image.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                    }
                                    zip.putNextEntry(ZipEntry("conversion-report.json"))
                                    zip.write(
                                        JSONObject()
                                            .put("options", settings.json())
                                            .put("items", report)
                                            .toString(2)
                                            .toByteArray(Charsets.UTF_8)
                                    )
                                    zip.closeEntry()
                                }
                                ZipFile(part).use { check(it.size() == successes.size + 1) }
                            }
                            ensureActive()
                            check(part.renameTo(file)) { "无法发布转换结果" }
                        } finally {
                            part.delete()
                        }
                    }
                    else -> error("未知转换类型")
                }
                ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        if (file.extension == "pdf") {
                            val registered =
                                repo.registerPdf(
                                    file,
                                    "${title.substringBeforeLast('.')} · ${if(job.input.getString("kind") == "OFFICE") "PDF" else "文字 PDF"}",
                                )
                            inputs.forEach { input ->
                                DocumentResources.register(
                                    repo,
                                    registered.id,
                                    "conversion-source",
                                    input,
                                )
                            }
                        }
                        if (job.input.getString("kind") != "TABLE_ANALYZE") repo.recordOutput(file)
                        DocumentJobs.complete(
                            repo,
                            job,
                            file,
                            "完成 $completed 项" + if (failed > 0) " · $failed 项失败，可重试" else "",
                            if (failed > 0) LocalTaskState.PARTIAL else LocalTaskState.SUCCEEDED,
                        )
                        published = true
                    }
                }
                Result.success(
                    workDataOf(
                        "path" to file.path,
                        "mode" to
                            when (job.input.getString("kind")) {
                                "TABLE_ANALYZE" -> "TABLE_REVIEW"
                                "MARKUP" -> "MARKUP"
                                "PDF_DIFF" -> "PDF_DIFF"
                                else -> "CONVERT"
                            },
                        "taskId" to job.row.id,
                        "partial" to (failed > 0),
                        "completed" to completed,
                        "failed" to failed,
                    )
                )
            } catch (cancel: CancellationException) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, cancel, true)
                throw cancel
            } catch (failure: Exception) {
                if (!published) output?.delete()
                DocumentJobs.fail(repo, handle, failure)
                Result.failure(workDataOf("error" to (failure.message ?: "转换失败")))
            } finally {
                if (leased) RuntimeBudget.background.release()
            }
        }
}
