package cn.paperforge.app.pdf

import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

object FormatJobs {
    @Suppress("DEPRECATION")
    fun installed(context: android.content.Context) =
        runCatching {
                context.packageManager
                    .getPackageInfo("cn.paperforge.formats", 0)
                    .versionName
                    .orEmpty()
            }
            .getOrNull()

    @Suppress("DEPRECATION")
    suspend fun convert(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val source = File(job.input.getStringArray("inputs")!!.single())
        val type = source.extension.lowercase()
        require(type in setOf("ofd", "djvu", "djv")) { "请选择 OFD 或 DjVu 文件" }
        val options = JSONObject(job.input.getString("options") ?: "{}")
        val dpi = options.optInt("dpi", 200).coerceIn(72, 600)
        val hash = PdfAssembly.sha256(source)
        val signature = "format-rpc1|$hash|$dpi"
        fun version(value: String) =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
                .take(24)
        val pageVersion = version(signature)
        val pageKey = "layout-pages-$pageVersion"
        val directory =
            File(repo.context.filesDir, "job-items/${job.row.id}/layout/$pageVersion").apply {
                mkdirs()
            }
        val checkpoint =
            JSONObject(requireNotNull(repo.dao.task(job.row.id)).payload)
                .getJSONObject("checkpoint")
                .optJSONObject(pageKey)
        val pagesFile =
            checkpoint
                ?.takeIf { it.optString("signature") == signature }
                ?.optString("path")
                ?.let(::File)
                ?.takeIf { it.isFile } ?: File(directory, "pages.zip")
        if (checkpoint?.optString("signature") != signature || !pagesFile.isFile) {
            require(installed(repo.context) != null) { "请先安装本次版本附带的「PaperForge 格式引擎」，再从任务中心继续" }
            val atomic = android.util.AtomicFile(pagesFile)
            val output = atomic.startWrite()
            try {
                FormatBridge.convert(
                    repo.context,
                    job.row.workId ?: job.row.id,
                    source,
                    output,
                    JSONObject().put("type", type).put("dpi", dpi).toString(),
                ) { percent, detail ->
                    progress(percent * 85 / 100, detail)
                }
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            DocumentJobs.checkpointFile(
                repo,
                job,
                pageKey,
                pagesFile,
                JSONObject().put("signature", signature),
            )
        }
        val title = job.input.getString("name")?.substringBeforeLast('.') ?: "PaperForge"
        val images = options.optString("target", "PDF") == "IMAGES"
        val outputKey =
            "layout-output-${version(signature + if (images) "images" else PdfTextLayer.VERSION)}"
        val outputFolder = File(repo.exports, "${job.row.id}/$outputKey").apply { mkdirs() }
        val output = File(outputFolder, safeFileName("$title.${if(images)"zip" else "pdf"}"))
        val previous =
            JSONObject(requireNotNull(repo.dao.task(job.row.id)).payload)
                .getJSONObject("checkpoint")
                .optJSONObject(outputKey)
        val savedReport = previous?.optString("report")?.takeIf { it.isNotBlank() }?.let(::File)
        val cachedOutput = previous?.optString("path")?.takeIf { it.isNotBlank() }?.let(::File)
        if (cachedOutput?.isFile == true && savedReport?.isFile == true) {
            DocumentJobs.checkpointFile(
                repo,
                job,
                "layout-report",
                savedReport,
                JSONObject()
                    .put("warningCount", previous.optInt("warningCount"))
                    .put("pages", previous.optInt("pages")),
            )
            return cachedOutput
        }
        ZipFile(pagesFile).use { zip ->
            val report =
                JSONObject(
                    zip.getInputStream(requireNotNull(zip.getEntry("document.json")))
                        .bufferedReader()
                        .readText()
                )
            require(report.getString("sourceSha256") == hash) { "版面结果与原件不匹配" }
            val pages = report.getJSONArray("pages")
            val warnings = JSONArray()
            for (index in 0 until pages.length()) pages
                .getJSONObject(index)
                .optJSONArray("warnings")
                ?.let { a ->
                    for (i in 0 until a.length()) if (
                        (0 until warnings.length()).none {
                            warnings.getString(it) == a.getString(i)
                        }
                    )
                        warnings.put(a.getString(i))
                }
            report
                .put("name", job.input.getString("name"))
                .put(
                    "output",
                    if (images) "PNG page package"
                    else "Raster appearance PDF with original text zones where available",
                )
                .put(
                    "signatureStatus",
                    "Appearance copy only; original OFD signatures/certificates are retained in the unchanged source, not transferred or validated",
                )
                .put("warnings", warnings)
            val reportFile = File(outputFolder, "layout-report.json")
            reportFile.writeText(report.toString(2))
            DocumentJobs.checkpointFile(
                repo,
                job,
                "$outputKey-report",
                reportFile,
                JSONObject().put("warningCount", warnings.length()).put("pages", pages.length()),
            )
            DocumentJobs.checkpointFile(
                repo,
                job,
                "layout-report",
                reportFile,
                JSONObject().put("warningCount", warnings.length()).put("pages", pages.length()),
            )
            if (images) {
                val atomic = android.util.AtomicFile(output)
                val stream = atomic.startWrite()
                try {
                    java.util.zip.ZipOutputStream(stream).apply {
                        putNextEntry(java.util.zip.ZipEntry("document.json"))
                        write(report.toString(2).toByteArray(Charsets.UTF_8))
                        closeEntry()
                        for (index in 0 until pages.length()) {
                            currentCoroutineContext().ensureActive()
                            val name = pages.getJSONObject(index).getString("image")
                            putNextEntry(java.util.zip.ZipEntry(name))
                            zip.getInputStream(requireNotNull(zip.getEntry(name))).use {
                                it.copyTo(this)
                            }
                            closeEntry()
                        }
                        finish()
                        flush()
                    }
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            } else {
                val unicode = mutableMapOf<Int, String>()
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                    var font: PDType0Font? = null
                    for (index in 0 until pages.length()) {
                        currentCoroutineContext().ensureActive()
                        val info = pages.getJSONObject(index)
                        val w = info.getDouble("widthPt").toFloat()
                        val h = info.getDouble("heightPt").toFloat()
                        val page = PDPage(PDRectangle(w, h))
                        pdf.addPage(page)
                        val bytes =
                            zip.getInputStream(
                                    requireNotNull(zip.getEntry(info.getString("image")))
                                )
                                .use { it.readBytes() }
                        val image =
                            PDImageXObject.createFromByteArray(pdf, bytes, "page-${index+1}")
                        PDPageContentStream(pdf, page).use { it.drawImage(image, 0f, 0f, w, h) }
                        val zones = info.optJSONArray("zones") ?: JSONArray()
                        val lines =
                            (0 until zones.length()).mapNotNull { z ->
                                val value = zones.getJSONObject(z)
                                val l = value.getDouble("left").toFloat().coerceIn(0f, 1f)
                                val t = value.getDouble("top").toFloat().coerceIn(0f, 1f)
                                val r = value.getDouble("right").toFloat().coerceIn(0f, 1f)
                                val b = value.getDouble("bottom").toFloat().coerceIn(0f, 1f)
                                if (r <= l || b <= t) null
                                else
                                    OcrLine(
                                        value.getString("text"),
                                        listOf(
                                            PointN(l, t),
                                            PointN(r, t),
                                            PointN(r, b),
                                            PointN(l, b),
                                        ),
                                    )
                            }
                        if (lines.isNotEmpty()) {
                            if (font == null) font = PdfTextLayer.font(repo.context, pdf)
                            lines.forEach { line ->
                                PdfTextLayer.safe(requireNotNull(font), line.text)
                                    .codePoints()
                                    .toArray()
                                    .forEach { codePoint ->
                                        val character = String(Character.toChars(codePoint))
                                        val encoded = requireNotNull(font).encode(character)
                                        if (encoded.size == 2)
                                            unicode[
                                                ((encoded[0].toInt() and 255) shl 8) or
                                                    (encoded[1].toInt() and 255)] = character
                                    }
                            }
                            PdfTextLayer.append(
                                pdf,
                                page,
                                requireNotNull(font),
                                OcrLayout(
                                    info.getInt("width"),
                                    info.getInt("height"),
                                    lines,
                                    engineId = "$type-source-text",
                                ),
                                0f,
                                0f,
                                w,
                                h,
                            )
                        }
                        progress(
                            88 + 10 * (index + 1) / pages.length(),
                            "生成 PDF · 第 ${index+1} / ${pages.length()} 页",
                        )
                    }
                    val atomic = android.util.AtomicFile(output)
                    val stream = atomic.startWrite()
                    try {
                        pdf.save(stream)
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) {
                        atomic.failWrite(stream)
                        throw error
                    }
                }
                PdfTextUnicode.rewrite(output, unicode)
            }
        }
        val completedReport = File(outputFolder, "layout-report.json")
        val info = JSONObject(completedReport.readText())
        DocumentJobs.checkpointFile(
            repo,
            job,
            outputKey,
            output,
            JSONObject()
                .put("report", completedReport.path)
                .put("warningCount", info.getJSONArray("warnings").length())
                .put("pages", info.getJSONArray("pages").length()),
        )
        return output
    }
}
