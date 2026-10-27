package cn.paperforge.app.pdf

import android.graphics.BitmapFactory
import android.net.Uri
import cn.paperforge.app.data.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class ConversionPreviewSide(
    val original: File,
    val pdf: File,
    val pages: Int,
    val representation: String,
)

data class ConversionPreview(
    val taskId: String,
    val operation: String,
    val left: ConversionPreviewSide,
    val right: ConversionPreviewSide,
)

/**
 * Render each actual file independently; manifests use resource IDs so backup remapping is
 * automatic.
 */
object ConversionPreviews {
    private val office =
        setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val text = setOf("txt", "md", "csv", "tsv")

    fun supported(file: File) = file.extension.lowercase() in office + images + text + "pdf"

    fun files(row: LocalTaskRow): Pair<File, File>? {
        if (row.type !in setOf("PDF", "CONVERT")) return null
        val input = JSONObject(row.payload).optJSONObject("input") ?: return null
        if (input.optString("kind") in setOf("CONVERSION_PREVIEW", "TABLE_ANALYZE", "TABLE_REVIEW"))
            return null
        val resources = row.resourceEntries()
        val source =
            resources.firstOrNull { it.id == "input-0" || it.id == "source" }?.path?.let(::File)
                ?: return null
        val result = resources.firstOrNull { it.id == "result" }?.path?.let(::File) ?: return null
        val imagePackage =
            input.optString("kind") == "OFFICE_IMAGES" ||
                input.optString("mode") in
                    setOf("images-jpg", "images-png", "images-webp", "extract-images")
        return if (
            source.isFile &&
                result.isFile &&
                supported(source) &&
                (supported(result) || result.extension.lowercase() == "zip" && imagePackage)
        )
            source to result
        else null
    }

    suspend fun prepare(repo: Repository, parent: LocalTaskRow): LocalTaskRow {
        val (source, result) = files(parent) ?: error("此结果请使用原有的结构文档或表格预览")
        val hashes = listOf(PdfAssembly.sha256(source), PdfAssembly.sha256(result))
        val signature = "preview-r1|${hashes.joinToString("|")}|${PdfTextLayer.VERSION}"
        val existing =
            repo.dao.allTasks().firstOrNull {
                if (it.type != "CONVERT") false
                else
                    JSONObject(it.payload).optJSONObject("input")?.let { j ->
                        j.optString("kind") == "CONVERSION_PREVIEW" &&
                            j.optString("parentTask") == parent.id &&
                            j.optString("signature") == signature &&
                            (it.state != LocalTaskState.SUCCEEDED ||
                                it.resourceEntries().any { r ->
                                    r.id == "result" && File(r.path).isFile
                                })
                    } == true
            }
        if (existing != null) return existing
        val row =
            DocumentJobs.prepare(
                repo,
                "CONVERT",
                JobInput(
                    BatchConversions.copyGroup(
                        parent,
                        JSONObject()
                            .put("kind", "CONVERSION_PREVIEW")
                            .put("parentTask", parent.id)
                            .put("name", parent.title)
                            .put("operation", parent.title)
                            .put("signature", signature)
                            .put("inputs", JSONArray(listOf(source.path, result.path)))
                            .put("hashes", JSONArray(hashes)),
                    )
                ),
            )
        DocumentJobs.schedule(repo, row)
        return row
    }

    suspend fun run(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val paths = checkNotNull(job.input.getStringArray("inputs")).map(::File)
        val expected = checkNotNull(job.input.getStringArray("hashes"))
        val directory =
            File(repo.context.filesDir, "job-items/${job.row.id}/preview").apply { mkdirs() }
        val sides = JSONArray()
        for ((index, source) in paths.withIndex()) {
            currentCoroutineContext().ensureActive()
            require(PdfAssembly.sha256(source) == expected[index]) { "原件或结果已经变化，请从原任务重新打开对照" }
            val key = if (index == 0) "preview-left" else "preview-right"
            val type = source.extension.lowercase()
            val representation =
                when (type) {
                    "pdf" -> "原始 PDF 页面"
                    in office -> "手机 Office 引擎排版预览"
                    in images -> "实际图像预览（最长边 2400 px）"
                    "zip" -> "实际图片包静态预览（最长边 2400 px）"
                    else -> "实际正文的排版预览"
                }
            val signature = "render-r1|${expected[index]}|$type|${PdfTextLayer.VERSION}"
            val cached =
                JSONObject(checkNotNull(repo.dao.task(job.row.id)).payload)
                    .getJSONObject("checkpoint")
                    .optJSONObject(key)
            val ready =
                cached
                    ?.takeIf { it.optString("signature") == signature }
                    ?.optString("path")
                    ?.let(::File)
                    ?.takeIf { it.isFile }
            val rendered =
                ready
                    ?: if (type == "pdf") source
                    else {
                        val destination = File(directory, "$key-${job.row.workId}.pdf")
                        progress(
                            index * 45 + 5,
                            "${if(index == 0) "原件" else "结果"} · $representation",
                        )
                        if (type in office) {
                            val atomic = android.util.AtomicFile(destination)
                            val stream = atomic.startWrite()
                            try {
                                OfficeBridge.convert(
                                    repo.context,
                                    "${job.row.workId}-$key",
                                    source,
                                    stream,
                                    "pdf",
                                ) { percent, detail ->
                                    progress(index * 45 + percent * 40 / 100, detail)
                                }
                                currentCoroutineContext().ensureActive()
                                atomic.finishWrite(stream)
                            } catch (failure: Throwable) {
                                atomic.failWrite(stream)
                                throw failure
                            }
                        } else if (type == "zip") {
                            val part = File(destination.path + ".part")
                            try {
                                PDDocument().use { pdf ->
                                    ZipFile(source).use { zip ->
                                        val index =
                                            zip.getEntry("images.json")?.let { entry ->
                                                JSONObject(
                                                        zip.getInputStream(entry)
                                                            .bufferedReader()
                                                            .use { it.readText() }
                                                    )
                                                    .getJSONArray("pages")
                                            }
                                        val entries =
                                            if (index != null)
                                                (0 until index.length()).map {
                                                    checkNotNull(
                                                        zip.getEntry(
                                                            index
                                                                .getJSONObject(it)
                                                                .getString("file")
                                                        )
                                                    )
                                                }
                                            else
                                                zip.entries()
                                                    .asSequence()
                                                    .filter {
                                                        !it.isDirectory &&
                                                            it.name
                                                                .substringAfterLast('.')
                                                                .lowercase() in images
                                                    }
                                                    .sortedWith(
                                                        compareBy<java.util.zip.ZipEntry> {
                                                                Regex("[0-9]+")
                                                                    .find(
                                                                        it.name.substringAfterLast(
                                                                            '/'
                                                                        )
                                                                    )
                                                                    ?.value
                                                                    ?.toLongOrNull() ?: 0L
                                                            }
                                                            .thenBy { it.name }
                                                    )
                                                    .toList()
                                        require(entries.isNotEmpty()) { "图片包中没有可预览页面" }
                                        for (entry in entries) {
                                            currentCoroutineContext().ensureActive()
                                            val bounds =
                                                BitmapFactory.Options().apply {
                                                    inJustDecodeBounds = true
                                                }
                                            zip.getInputStream(entry).use {
                                                BitmapFactory.decodeStream(it, null, bounds)
                                            }
                                            var sample = 1
                                            while (
                                                maxOf(bounds.outWidth, bounds.outHeight) / sample >
                                                    2400
                                            ) sample *= 2
                                            val bitmap =
                                                zip.getInputStream(entry).use {
                                                    BitmapFactory.decodeStream(
                                                        it,
                                                        null,
                                                        BitmapFactory.Options().apply {
                                                            inSampleSize = sample
                                                        },
                                                    )
                                                } ?: error("图片无法读取：${entry.name}")
                                            try {
                                                val image =
                                                    LosslessFactory.createFromImage(pdf, bitmap)
                                                val page =
                                                    PDPage(
                                                        PDRectangle(
                                                            image.width.toFloat(),
                                                            image.height.toFloat(),
                                                        )
                                                    )
                                                pdf.addPage(page)
                                                PDPageContentStream(pdf, page).use {
                                                    it.drawImage(
                                                        image,
                                                        0f,
                                                        0f,
                                                        page.mediaBox.width,
                                                        page.mediaBox.height,
                                                    )
                                                }
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        }
                                    }
                                    pdf.save(part)
                                }
                                currentCoroutineContext().ensureActive()
                                check(part.renameTo(destination))
                            } finally {
                                part.delete()
                            }
                        } else if (type in images) {
                            val part = File(destination.path + ".part")
                            try {
                                PDDocument().use { pdf ->
                                    val bitmap =
                                        cn.paperforge.app.imaging.ScanProcessor.decode(source, 2400)
                                    try {
                                        val image = LosslessFactory.createFromImage(pdf, bitmap)
                                        val page =
                                            PDPage(
                                                PDRectangle(
                                                    image.width.toFloat(),
                                                    image.height.toFloat(),
                                                )
                                            )
                                        pdf.addPage(page)
                                        PDPageContentStream(pdf, page).use {
                                            it.drawImage(
                                                image,
                                                0f,
                                                0f,
                                                page.mediaBox.width,
                                                page.mediaBox.height,
                                            )
                                        }
                                        pdf.save(part)
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                                currentCoroutineContext().ensureActive()
                                check(part.renameTo(destination))
                            } finally {
                                part.delete()
                            }
                        } else {
                            require(type in text) { "此格式没有可用的真实预览引擎" }
                            LocalConversions.textToPdf(
                                repo.context,
                                Uri.fromFile(source),
                                source.name,
                                destination,
                            )
                        }
                        destination
                    }
            val count =
                PDDocument.load(rendered, MemoryUsageSetting.setupTempFileOnly()).use {
                    it.numberOfPages
                }
            require(count > 0) { "预览未生成页面" }
            DocumentJobs.checkpointFile(
                repo,
                job,
                key,
                rendered,
                JSONObject().put("signature", signature),
            )
            sides.put(
                JSONObject()
                    .put("source", "input-$index")
                    .put("pdf", key)
                    .put("pages", count)
                    .put("representation", representation)
            )
        }
        val manifest = File(repo.exports, "PaperForge-conversion-preview-${job.row.id}.pfpreview")
        TableJobs.atomic(
            manifest,
            JSONObject()
                .put("format", 1)
                .put("operation", job.input.getString("operation"))
                .put("sides", sides),
        )
        progress(100, "对照已就绪")
        return manifest
    }

    fun load(row: LocalTaskRow): ConversionPreview {
        val resources = row.resourceEntries().associateBy { it.id }
        val manifest = JSONObject(File(checkNotNull(resources["result"]).path).readText())
        fun side(index: Int): ConversionPreviewSide {
            val j = manifest.getJSONArray("sides").getJSONObject(index)
            return ConversionPreviewSide(
                File(checkNotNull(resources[j.getString("source")]).path),
                File(checkNotNull(resources[j.getString("pdf")]).path),
                j.getInt("pages"),
                j.getString("representation"),
            )
        }
        return ConversionPreview(row.id, manifest.getString("operation"), side(0), side(1))
    }
}
