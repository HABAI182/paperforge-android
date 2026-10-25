package cn.paperforge.app.pdf

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import cn.paperforge.app.PaperForgeApp
import cn.paperforge.app.data.DocumentResources
import cn.paperforge.app.model.safeFileName
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

class PdfJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            var output: File? = null
            var published = false
            var originFile: File? = null
            var originManifest: File? = null
            var assemblyManifest: File? = null
            try {
                val repo = (applicationContext as PaperForgeApp).repository
                val mode = inputData.getString("mode") ?: error("缺少工具")
                val doc = repo.dao.document(inputData.getString("document") ?: "")
                val source = doc?.pdfPath?.let { File(it) }
                val p = JSONObject(inputData.getString("options") ?: "{}")
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
                if (mode == "assemble" && repo.dao.output(file.path) != null && file.isFile)
                    return@withContext Result.success(
                        workDataOf(
                            "path" to file.path,
                            "mode" to mode,
                            "outputBytes" to file.length(),
                        )
                    )
                val input = source ?: file
                val range = p.optString("range")
                var origin: JSONObject? = null
                when (mode) {
                    "assemble" -> {
                        val manifest = File(p.getString("manifest"))
                        assemblyManifest = manifest
                        val plan = AssemblyPlan.decode(JSONObject(manifest.readText()))
                        origin =
                            PdfAssembly.write(repo, plan, file) {
                                setProgress(workDataOf("progress" to it))
                            }
                    }
                    "merge" ->
                        PdfEngine.merge(
                            inputData.getStringArray("sources")!!.map { File(it) },
                            file,
                        )
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
                            setProgress(workDataOf("progress" to it))
                        }
                    "long-image" ->
                        PdfLongImages.convert(input, file, range) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "extract-images" -> PdfTools.extractImages(input, file)
                    "searchable" ->
                        PdfSearchable.convert(applicationContext, input, file) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "docx-layout" ->
                        PhoneDocx.convert(
                            input,
                            file,
                            (applicationContext as PaperForgeApp).ocrEngine,
                        ) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "pdf-pptx" ->
                        PhoneSlides.convert(applicationContext, input, file) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "txt",
                    "md",
                    "html",
                    "docx",
                    "docx-image" -> PdfTools.exportText(input, file, mode)
                    "compress" ->
                        PdfEngine.compress(input, file, p.optInt("quality", 75)) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "flatten" ->
                        PdfEngine.flatten(input, file, p.optInt("quality", 75)) {
                            setProgress(workDataOf("progress" to it))
                        }
                    "split-each" -> PdfTools.splitEach(input, file)
                    "marks" -> {
                        val data = File(p.getString("marksFile"))
                        try {
                            PdfMarks.save(
                                applicationContext,
                                input,
                                file,
                                PdfMark.decode(org.json.JSONArray(data.readText())),
                                editable = p.optBoolean("editable", true),
                            ) {
                                setProgress(workDataOf("progress" to it))
                            }
                        } finally {
                            data.delete()
                        }
                    }
                    else -> error("未知工具")
                }
                if (mode in listOf("compress", "optimize"))
                    setProgress(workDataOf("progress" to 100))
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    repo.database.withTransaction {
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
                                    File(repo.folder(registered.id), "assembly-origin.json")
                                originFile = provenance
                                provenance.writeText(metadata.toString())
                                DocumentResources.register(
                                    repo,
                                    registered.id,
                                    "assembly-origin",
                                    provenance,
                                )
                                originManifest =
                                    File(repo.folder(registered.id), "document-resources.json")
                            }
                        }
                        repo.recordOutput(file, doc)
                    }
                    published = true
                }
                assemblyManifest?.delete()
                Result.success(
                    workDataOf(
                        "path" to file.path,
                        "inputBytes" to (source?.length() ?: 0),
                        "outputBytes" to file.length(),
                        "mode" to mode,
                    )
                )
            } catch (e: CancellationException) {
                if (!published) {
                    output?.delete()
                    originFile?.delete()
                    originManifest?.delete()
                    originFile?.parentFile?.delete()
                }
                throw e
            } catch (e: Exception) {
                if (!published) {
                    output?.delete()
                    originFile?.delete()
                    originManifest?.delete()
                    originFile?.parentFile?.delete()
                }
                Result.failure(workDataOf("error" to (e.message ?: "处理失败")))
            }
        }
}
