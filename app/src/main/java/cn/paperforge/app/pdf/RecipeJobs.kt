package cn.paperforge.app.pdf

import android.net.Uri
import android.webkit.MimeTypeMap
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import cn.paperforge.app.ocr.OcrRequest
import java.io.File
import kotlinx.coroutines.*
import org.json.*

/** Each completed stage is immutable and referenced by the parent task, including failed runs. */
object RecipeJobs {
    suspend fun run(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val recipe =
            WorkflowRecipe.decode(
                JSONObject(job.input.getString("options") ?: "{}").getJSONObject("recipe")
            )
        recipe.issue()?.let { error(it) }
        var source = File(job.input.getStringArray("inputs")!!.single())
        require(WorkflowRecipes.sourceType(source.extension) == recipe.input) {
            "配方需要 ${recipe.input}，所选文件为 ${WorkflowRecipes.sourceType(source.extension)}"
        }
        val originalName = job.input.getStringArray("names")?.firstOrNull() ?: source.name
        val originalTitle =
            if (originalName.endsWith(".${source.extension}", true))
                originalName.dropLast(source.extension.length + 1)
            else originalName
        val directory =
            File(repo.context.filesDir, "job-items/${job.row.id}/recipe").apply { mkdirs() }
        val router = (repo.context.applicationContext as PaperForgeApp).ocrEngine
        val request = OcrRequest.decode(job.input.json.optJSONObject("ocrRequest"), router.id)
        val report = JSONArray()
        var sourcePages = 1
        suspend fun recordReport() {
            val file = File(directory, "run-report.json")
            val atomic = android.util.AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(
                    JSONObject()
                        .put("recipe", recipe.name)
                        .put("steps", report)
                        .toString(2)
                        .toByteArray(Charsets.UTF_8)
                )
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            DocumentJobs.checkpointFile(repo, job, "recipe-report", file, JSONObject())
        }
        for ((index, step) in recipe.steps.withIndex()) {
            if (source.extension.equals("pdf", true))
                sourcePages =
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(source).use { it.numberOfPages }
            currentCoroutineContext().ensureActive()
            DocumentJobs.checkCurrent(repo, job)
            val operation = WorkflowRecipes.operations.first { it.id == step.operation }
            val key = "recipe-step-${step.id}"
            val signature =
                "recipe1|${PdfAssembly.sha256(source)}|${step.json()}|${if(step.operation in setOf("searchable", "pdf-docx")) request.signature else ""}"
            val cached =
                JSONObject(checkNotNull(repo.dao.task(job.row.id)).payload)
                    .getJSONObject("checkpoint")
                    .optJSONObject(key)
                    ?.takeIf { it.optString("signature") == signature }
            val ready =
                cached
                    ?.takeIf { it.optBoolean("complete") }
                    ?.optString("path")
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0 }
            val started = System.currentTimeMillis()
            if (ready != null) {
                source = ready
                report.put(
                    JSONObject()
                        .put("id", step.id)
                        .put("operation", step.operation)
                        .put("status", "reused")
                        .put("bytes", source.length())
                )
                progress((index + 1) * 95 / recipe.steps.size, "复用步骤 ${index+1}：${operation.title}")
                recordReport()
                continue
            }
            try {
                suspend fun stageProgress(percent: Int) =
                    progress(
                        (index * 95 + percent * 95 / 100) / recipe.steps.size,
                        "步骤 ${index+1}/${recipe.steps.size} · ${operation.title}",
                    )
                stageProgress(0)
                val extension =
                    when (step.operation) {
                        "pdf-txt" -> "txt"
                        "pdf-docx" -> "docx"
                        "pdf-png" -> "zip"
                        "rename",
                        "save" -> source.extension
                        else -> "pdf"
                    }
                val version =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray(Charsets.UTF_8))
                        .take(12)
                        .joinToString("") { "%02x".format(it) }
                val stage = File(File(directory, step.id), version).apply { mkdirs() }
                var result = File(stage, "result.$extension")
                when (step.operation) {
                    "office-pdf" -> {
                        val input =
                            JobInput(
                                JSONObject(job.input.json.toString())
                                    .put("inputs", JSONArray(listOf(source.path)))
                                    .put("name", "$originalTitle.${source.extension}")
                                    .put("options", JSONObject().put("format", "pdf").toString())
                            )
                        result =
                            OfficeJobs.convert(
                                repo,
                                JobHandle(job.row, input),
                                destination = result,
                            ) { percent, _ ->
                                stageProgress(percent)
                            }
                    }
                    "text-pdf" ->
                        LocalConversions.textToPdf(
                            repo.context,
                            Uri.fromFile(source),
                            source.name,
                            result,
                            options = TextPdfOptions.decode(step.options),
                        )
                    "image-pdf" ->
                        PdfEngine.scan(
                            listOf(
                                PageRow(step.id, job.row.id, 0, source.path, filter = "ORIGINAL")
                            ),
                            result,
                            paper = step.options.optString("paper", "Auto"),
                            context = repo.context,
                            lossless = true,
                        ) {
                            stageProgress(it)
                        }
                    "searchable" ->
                        PdfSearchable.convert(repo.context, source, result, request.bind(router)) {
                            stageProgress(it)
                        }
                    "compress" ->
                        PdfImageOptimizer.compress(
                            source,
                            result,
                            step.options.optInt("quality", 84).coerceIn(40, 100),
                        ) {
                            stageProgress(it)
                        }
                    "pdf-txt" -> PdfTools.exportText(source, result, "txt")
                    "pdf-docx" ->
                        PhoneDocx.convert(source, result, request.bind(router)) {
                            stageProgress(it)
                        }
                    "pdf-png" ->
                        PdfTools.images(
                            source,
                            result,
                            step.options.optString("range"),
                            "png",
                            step.options.optInt("edge", 2200).coerceIn(600, 6000),
                        ) {
                            stageProgress(it)
                        }
                    "rename" -> {
                        val name =
                            ScanExportOptions(
                                    naming = step.options.optString("template", "{title}_{date}")
                                )
                                .fileName(
                                    originalTitle,
                                    sourcePages,
                                    JSONObject(job.row.payload)
                                        .optLong("recipeStarted", job.row.created),
                                )
                        result = File(stage, "$name.$extension")
                        val atomic = android.util.AtomicFile(result)
                        val stream = atomic.startWrite()
                        try {
                            source.inputStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    stream.write(buffer, 0, n)
                                }
                            }
                            atomic.finishWrite(stream)
                        } catch (error: Throwable) {
                            atomic.failWrite(stream)
                            throw error
                        }
                    }
                    "save" -> {
                        result = source
                        val store = ExportDestinations(repo.context)
                        val destination =
                            store.get(step.options.optString("destination"))
                                ?: error("请选择本机保存目录，已完成步骤保留")
                        require(store.available(destination)) { "目录授权已失效，请重新选择；已完成步骤保留" }
                        val uri =
                            cached
                                ?.optString("destinationUri")
                                ?.takeIf { it.isNotBlank() }
                                ?.let(Uri::parse)
                                ?: ExportDestinations.create(
                                    repo.context,
                                    destination.uri,
                                    source.name,
                                    MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(source.extension.lowercase())
                                        ?: "application/octet-stream",
                                )
                        DocumentJobs.checkpointFile(
                            repo,
                            job,
                            key,
                            source,
                            JSONObject()
                                .put("signature", signature)
                                .put("complete", false)
                                .put("destinationUri", uri.toString()),
                        )
                        repo.recordOutput(source)
                        repo.saveOutput(source, uri)
                        DocumentJobs.checkpointFile(
                            repo,
                            job,
                            key,
                            source,
                            JSONObject()
                                .put("signature", signature)
                                .put("complete", true)
                                .put("destinationUri", uri.toString()),
                        )
                    }
                }
                require(result.isFile && result.length() > 0) { "此步骤没有生成可用结果" }
                if (step.operation != "save")
                    DocumentJobs.checkpointFile(
                        repo,
                        job,
                        key,
                        result,
                        JSONObject().put("signature", signature).put("complete", true),
                    )
                source = result
                report.put(
                    JSONObject()
                        .put("id", step.id)
                        .put("operation", step.operation)
                        .put("status", "completed")
                        .put("bytes", result.length())
                        .put("elapsedMs", System.currentTimeMillis() - started)
                )
                recordReport()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report.put(
                    JSONObject()
                        .put("id", step.id)
                        .put("operation", step.operation)
                        .put("status", "failed")
                        .put("error", error.message)
                )
                recordReport()
                throw IllegalStateException(
                    "步骤 ${index+1}「${operation.title}」：${error.message}",
                    error,
                )
            }
        }
        progress(100, "配方处理完成")
        return source
    }
}
