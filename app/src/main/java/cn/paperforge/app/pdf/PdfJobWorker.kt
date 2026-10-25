package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

class PdfJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO + BackgroundWork) {
            var output: File? = null
            var published = false
            var originFile: File? = null
            var originManifest: File? = null
            var assemblyManifest: File? = null
            val repo = (applicationContext as PaperForgeApp).repository
            var handle: JobHandle? = null
            var leased = false
            try {
                val job =
                    DocumentJobs.start(repo, "PDF", inputData, id)
                        ?: return@withContext Result.failure(workDataOf("error" to "任务已停止"))
                handle = job
                val data = job.input
                if (job.completed)
                    return@withContext Result.success(
                        workDataOf(
                            "path" to job.result!!.path,
                            "mode" to (data.getString("mode") ?: "PDF"),
                        )
                    )
                DocumentJobs.progress(repo, job, job.row.progress, "等待后台资源")
                RuntimeBudget.background.acquire()
                leased = true
                DocumentJobs.progress(repo, job, job.row.progress)
                suspend fun report(percent: Int) {
                    DocumentJobs.progress(repo, job, percent)
                    setProgress(workDataOf("progress" to percent))
                }
                val mode = data.getString("mode") ?: error("缺少工具")
                val doc = repo.dao.document(data.getString("document") ?: "")
                val source = doc?.pdfPath?.let { File(it) }
                val p = JSONObject(data.getString("options") ?: "{}")
                val extension =
                    when (mode) {
                        "images-jpg",
                        "images-png",
                        "images-webp",
                        "long-image",
                        "extract-images",
                        "split-each" -> "zip"
                        "docx-image",
                        "docx-layout" -> "docx"
                        "pdf-pptx" -> "pptx"
                        "txt",
                        "md",
                        "html",
                        "docx" -> mode
                        else -> "pdf"
                    }
                val file =
                    File(
                        repo.exports,
                        "${safeFileName(if(mode=="assemble") p.optString("title","组合文档") else doc?.title ?: "合并文档")}-$mode-${id.toString().take(8)}.$extension",
                    )
                output = file
                val input = source ?: file
                val range = p.optString("range")
                var origin: JSONObject? = null
                var originRole = "assembly-origin"
                var compression: JSONObject? = null
                when (mode) {
                    "assemble" -> {
                        val manifest = File(p.getString("manifest"))
                        assemblyManifest = manifest
                        val plan = AssemblyPlan.decode(JSONObject(manifest.readText()))
                        val files =
                            plan.sources
                                .mapIndexed { index, source ->
                                    source.documentId to
                                        (job.row
                                            .resourceEntries()
                                            .firstOrNull { it.id == "assembly-source-$index" }
                                            ?.path
                                            ?.let(::File)
                                            ?: repo.dao
                                                .document(source.documentId)
                                                ?.pdfPath
                                                ?.let(::File)
                                            ?: error("来源 PDF 已移除"))
                                }
                                .toMap()
                        origin =
                            PdfAssembly.writeFiles(applicationContext, plan, files, file) {
                                report(it)
                            }
                    }
                    "merge" ->
                        PdfEngine.merge(data.getStringArray("sources")!!.map { File(it) }, file)
                    "extract" -> PdfEngine.extract(input, range, file, p.optInt("rotation"))
                    "organize" -> {
                        val order = p.getJSONArray("order")
                        val rotations = p.getJSONObject("rotations")
                        PdfEngine.organize(
                            input,
                            file,
                            List(order.length()) { order.getInt(it) },
                            rotations.keys().asSequence().associate {
                                it.toInt() to rotations.getInt(it)
                            },
                        )
                    }
                    "crop" ->
                        PdfTools.crop(input, file, p.optDouble("percent", 5.0).toFloat(), range)
                    "blank" -> PdfTools.insertBlank(input, file, p.optInt("after", 1))
                    "nup2",
                    "nup4" -> PdfTools.nup(input, file, if (mode == "nup2") 2 else 4)
                    "watermark" ->
                        PdfTools.watermark(
                            applicationContext,
                            input,
                            file,
                            p.optString("text"),
                            range,
                            p.optDouble("opacity", .18).toFloat(),
                        )
                    "number" ->
                        PdfTools.watermark(
                            applicationContext,
                            input,
                            file,
                            "",
                            range,
                            numbered = true,
                        )
                    "metadata" ->
                        PdfTools.metadata(
                            input,
                            file,
                            p.optString("title", doc?.title ?: ""),
                            p.optString("author"),
                        )
                    "optimize" -> PdfTools.optimize(input, file)
                    "images-jpg",
                    "images-png",
                    "images-webp" ->
                        PdfTools.images(
                            input,
                            file,
                            range,
                            mode.substringAfter("images-"),
                            p.optInt("edge", 2200),
                        ) {
                            report(it)
                        }
                    "long-image" ->
                        PdfLongImages.convert(input, file, range, LongImageOptions.decode(p)) {
                            report(it)
                        }
                    "extract-images" -> PdfTools.extractImages(input, file)
                    "searchable" ->
                        PdfSearchable.convert(applicationContext, input, file) { report(it) }
                    "docx-layout" ->
                        PhoneDocx.convert(
                            input,
                            file,
                            (applicationContext as PaperForgeApp).ocrEngine,
                        ) {
                            report(it)
                        }
                    "pdf-pptx" ->
                        PhoneSlides.convert(applicationContext, input, file) { report(it) }
                    "txt",
                    "md",
                    "html",
                    "docx",
                    "docx-image" -> PdfTools.exportText(input, file, mode)
                    "compress" -> {
                        compression =
                            if (p.optLong("targetBytes", 0) > 0)
                                PdfSizeTarget.compress(input, file, p.getLong("targetBytes")) {
                                    report(it)
                                }
                            else
                                PdfImageOptimizer.compress(input, file, p.optInt("quality", 75)) {
                                    report(it)
                                }
                        origin = compression
                        originRole = "compression-report"
                    }
                    "flatten" ->
                        PdfEngine.flatten(input, file, p.optInt("quality", 75)) { report(it) }
                    "split-each" -> PdfTools.splitEach(input, file)
                    "marks" -> {
                        val data = File(p.getString("marksFile"))
                        run {
                            PdfMarks.save(
                                applicationContext,
                                input,
                                file,
                                PdfMark.decode(org.json.JSONArray(data.readText())),
                                editable = p.optBoolean("editable", true),
                            ) {
                                report(it)
                            }
                        }
                    }
                    else -> error("未知工具")
                }
                if (mode in listOf("compress", "optimize")) report(100)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
                        DocumentJobs.checkCurrent(repo, job)
                        if (extension == "pdf") {
                            val registered =
                                repo.registerPdf(
                                    file,
                                    if (mode == "assemble") p.optString("title", "组合文档")
                                    else (doc?.title ?: "合并文档") + " · " + mode,
                                    listed = doc == null,
                                )
                            origin?.let { metadata ->
                                val provenance =
                                    File(repo.folder(registered.id), "$originRole.json")
                                originFile = provenance
                                provenance.writeText(metadata.toString())
                                DocumentResources.register(
                                    repo,
                                    registered.id,
                                    originRole,
                                    provenance,
                                )
                                originManifest =
                                    File(repo.folder(registered.id), "document-resources.json")
                            }
                        }
                        repo.recordOutput(file, doc)
                        val unmet =
                            compression?.optLong("targetBytes", 0)?.let {
                                it > 0 && compression?.optBoolean("targetAchieved") != true
                            } == true
                        DocumentJobs.complete(
                            repo,
                            job,
                            file,
                            if (unmet) "未达到目标体积，已保留可用结果" else "已完成",
                            if (unmet) cn.paperforge.app.data.LocalTaskState.PARTIAL
                            else cn.paperforge.app.data.LocalTaskState.SUCCEEDED,
                        )
                    }
                    published = true
                }
                Result.success(
                    workDataOf(
                        "path" to file.path,
                        "inputBytes" to (source?.length() ?: 0),
                        "outputBytes" to file.length(),
                        "mode" to mode,
                        "targetBytes" to (compression?.optLong("targetBytes", 0) ?: 0L),
                        "targetAchieved" to
                            (compression?.optBoolean("targetAchieved", true) ?: true),
                        "selectedQuality" to (compression?.optInt("selectedQuality", -1) ?: -1),
                        "attempts" to (compression?.optJSONArray("attempts")?.length() ?: 0),
                    )
                )
            } catch (e: CancellationException) {
                DocumentJobs.fail(repo, handle, e, true)
                if (!published) {
                    output?.delete()
                    originFile?.delete()
                    originManifest?.delete()
                    originFile?.parentFile?.delete()
                }
                throw e
            } catch (e: Exception) {
                DocumentJobs.fail(repo, handle, e)
                if (!published) {
                    output?.delete()
                    originFile?.delete()
                    originManifest?.delete()
                    originFile?.parentFile?.delete()
                }
                Result.failure(workDataOf("error" to (e.message ?: "处理失败")))
            } finally {
                if (leased) RuntimeBudget.background.release()
            }
        }
}
