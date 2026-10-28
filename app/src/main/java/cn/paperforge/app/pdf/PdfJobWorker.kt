package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.*
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
                val source =
                    job.row.resourceEntries().firstOrNull { it.id == "source" }?.path?.let(::File)
                        ?: doc?.pdfPath?.let(::File)
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
                        "pdf-pptx",
                        "pdf-pptx-native" -> "pptx"
                        "txt",
                        "md",
                        "html",
                        "docx" -> mode
                        else -> "pdf"
                    }
                val file =
                    File(
                        File(repo.exports, job.row.id).apply { mkdirs() },
                        OutputNames.withExtension(
                            if (mode == "assemble") p.optString("title", "组合文档")
                            else doc?.title ?: "合并文档",
                            extension,
                        ),
                    )
                output = file
                val input = source ?: file
                val range = p.optString("range")
                var origin: JSONObject? = null
                var originRole = "assembly-origin"
                var compression: JSONObject? = null
                var redaction: PdfRedaction.Report? = null
                var conversionNotice = ""
                when (mode) {
                    "certificate-sign" -> {
                        origin = PdfCertificates.sign(input, file, p)
                        originRole = "certificate-report"
                        report(100)
                    }
                    "forms-fill" -> {
                        val requestFile =
                            job.row
                                .resourceEntries()
                                .firstOrNull { it.id == "form-request" }
                                ?.path
                                ?.let(::File) ?: File(p.getString("formFile"))
                        origin =
                            PdfForms.fill(
                                applicationContext,
                                input,
                                file,
                                JSONObject(requestFile.readText()),
                            )
                        originRole = "form-fill-report"
                        report(100)
                    }
                    "native-edit" -> {
                        val requestFile =
                            job.row
                                .resourceEntries()
                                .firstOrNull { it.id == "native-edit" }
                                ?.path
                                ?.let(::File) ?: File(p.getString("editFile"))
                        val fonts =
                            job.row
                                .resourceEntries()
                                .filter { it.id.startsWith("native-font-") }
                                .associate { it.id.removePrefix("native-font-") to File(it.path) }
                        origin =
                            NativePdfEditor.apply(
                                applicationContext,
                                input,
                                file,
                                JSONObject(requestFile.readText()),
                                fonts,
                            ) {
                                report(it)
                            }
                        originRole = "native-edit-report"
                        conversionNotice = "仅改写所选文字对象，原件与其他页面保留；修改记录随副本保存。"
                    }
                    "redact-semantic",
                    "redact-raster" -> {
                        val sourceFile = requireNotNull(source) { "来源 PDF 已移除" }
                        require(sourceFile.isFile) { "来源 PDF 已移除" }
                        // A previous interrupted attempt may have written this job's unregistered
                        // output.
                        val part = File(file.path + ".part")
                        if (sourceFile.canonicalFile == file.canonicalFile) {
                            output = null
                            error("输出不能覆盖来源 PDF")
                        }
                        require(sourceFile.canonicalFile != part.canonicalFile) { "临时输出不能覆盖来源 PDF" }
                        if (file.exists()) check(file.delete()) { "无法清理中断的任务输出" }
                        if (part.exists()) check(part.delete()) { "无法清理中断的临时文件" }
                        report(5)
                        val result =
                            if (mode == "redact-semantic") {
                                PdfRedaction.semantic(
                                    input,
                                    file,
                                    p.getInt("page"),
                                    p.getString("target"),
                                )
                            } else {
                                PdfRedaction.raster(
                                    applicationContext,
                                    input,
                                    file,
                                    p.getInt("page"),
                                    p.getDouble("left").toFloat(),
                                    p.getDouble("top").toFloat(),
                                    p.getDouble("right").toFloat(),
                                    p.getDouble("bottom").toFloat(),
                                    stages = JobFileStages(repo, job),
                                ) {
                                    report(it.coerceAtLeast(5))
                                }
                            }
                        redaction = result
                        origin =
                            JSONObject()
                                .put("mode", result.mode)
                                .put("detail", result.detail)
                                .put("page", p.getInt("page") + 1)
                        originRole = "redaction-report"
                        report(100)
                    }
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
                            stages = JobFileStages(repo, job),
                        ) {
                            report(it)
                        }
                    "long-image" ->
                        PdfLongImages.convert(
                            input,
                            file,
                            range,
                            LongImageOptions.decode(p),
                            stages = JobFileStages(repo, job),
                        ) {
                            report(it)
                        }
                    "extract-images" -> PdfTools.extractImages(input, file)
                    "searchable" -> {
                        val result =
                            PdfSearchable.convert(
                                applicationContext,
                                input,
                                file,
                                cn.paperforge.app.ocr.OcrRequest.decode(
                                        data.json.optJSONObject("ocrRequest"),
                                        (applicationContext as PaperForgeApp).ocrEngine.id,
                                    )
                                    .bind((applicationContext as PaperForgeApp).ocrEngine),
                                stages = JobFileStages(repo, job),
                            ) {
                                report(it)
                            }
                        origin = result
                        originRole = "ocr-coverage-report"
                        val decisions = result.getJSONArray("pages")
                        val recognized =
                            (0 until decisions.length()).count {
                                decisions.getJSONObject(it).getBoolean("ocr")
                            }
                        conversionNotice =
                            "保留原生文字 ${decisions.length()-recognized} 页 · 补识别 $recognized 页，附逐页文字覆盖记录。"
                    }
                    "docx-layout" -> {
                        val result =
                            PhoneDocx.convert(
                                input,
                                file,
                                cn.paperforge.app.ocr.OcrRequest.decode(
                                        data.json.optJSONObject("ocrRequest"),
                                        (applicationContext as PaperForgeApp).ocrEngine.id,
                                    )
                                    .bind((applicationContext as PaperForgeApp).ocrEngine),
                                p.optString("layoutMode", "editable"),
                            ) {
                                report(it)
                            }
                        origin = result
                        originRole = "docx-report"
                        conversionNotice =
                            if (result.getString("mode") == "appearance")
                                "外观优先：${result.getJSONArray("pages").length()} 页整页图，文字和表格不作为独立可编辑对象。"
                            else "编辑优先：标题、正文、表格与可见图片裁片；多栏按阅读顺序展开，附逐页记录。"
                    }
                    "pdf-pptx" ->
                        PhoneSlides.convert(applicationContext, input, file) { report(it) }
                    "pdf-pptx-native" -> {
                        val result =
                            NativeSlides.convert(
                                applicationContext,
                                input,
                                file,
                                p.optBoolean("preferEditable"),
                            ) {
                                report(it)
                            }
                        origin = result
                        originRole = "slides-report"
                        conversionNotice =
                            "原生对象 ${result.getInt("editablePages")} 页 · 编辑简化 ${result.getInt("simplifiedPages")} 页 · 整页外观 ${result.getInt("appearancePages")} 页。详细转换记录保存在 PPTX 内。"
                    }
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
                        PdfEngine.flatten(
                            input,
                            file,
                            p.optInt("quality", 75),
                            stages = JobFileStages(repo, job),
                        ) {
                            report(it)
                        }
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
                                stages = JobFileStages(repo, job),
                            ) {
                                report(it)
                            }
                        }
                    }
                    else -> error("未知工具")
                }
                if (mode in listOf("compress", "optimize")) report(100)
                currentCoroutineContext().ensureActive()
                if (extension != "pdf" && origin != null) {
                    val metadata =
                        File(repo.context.filesDir, "job-items/${job.row.id}/$originRole.json")
                    originFile = metadata
                    TableJobs.atomic(metadata, checkNotNull(origin))
                    DocumentJobs.checkpointFile(
                        repo,
                        job,
                        "tool-report",
                        metadata,
                        JSONObject().put("role", originRole),
                    )
                }
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
                            if (unmet) "未达到目标体积，已保留可用结果"
                            else if (conversionNotice.isNotBlank()) conversionNotice
                            else redaction?.let { "${it.mode}：${it.detail}" } ?: "已完成",
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
                        "notice" to conversionNotice,
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
