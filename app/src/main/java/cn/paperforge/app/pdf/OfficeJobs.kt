package cn.paperforge.app.pdf

import cn.paperforge.app.data.*
import cn.paperforge.app.model.safeFileName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

object OfficeJobs {
    suspend fun convert(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val source = File(job.input.getStringArray("inputs")!!.single())
        val options = JSONObject(job.input.getString("options") ?: "{}")
        val format = options.optString("format", "pdf")
        require(format in setOf("pdf", "docx", "odt", "rtf", "xlsx", "ods", "csv", "pptx", "odp")) {
            "Office 目标格式不支持"
        }
        val directory = File(repo.exports, job.row.id).apply { mkdirs() }
        val file =
            File(
                directory,
                safeFileName((job.input.getString("name") ?: "Office").substringBeforeLast('.')) +
                    ".$format",
            )
        val signature = "office-rpc1|${PdfAssembly.sha256(source)}|$format"
        val cached =
            JSONObject(checkNotNull(repo.dao.task(job.row.id)).payload)
                .getJSONObject("checkpoint")
                .optJSONObject("office-output")
        val ready =
            cached
                ?.takeIf { it.optString("signature") == signature }
                ?.optString("path")
                ?.let(::File)
                ?.takeIf { it.isFile && it.length() > 0 }
        if (ready != null) return ready
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        var finished = false
        try {
            OfficeBridge.convert(
                repo.context,
                job.row.workId ?: job.row.id,
                source,
                stream,
                format,
                progress,
            )
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(stream)
            finished = true
            when (format) {
                "pdf" ->
                    PDDocument.load(file).use {
                        require(it.numberOfPages > 0) { "Office PDF 没有页面" }
                    }
                "docx",
                "xlsx",
                "pptx" ->
                    ZipFile(file).use {
                        require(it.getEntry("[Content_Types].xml") != null) { "Office 结果结构不完整" }
                    }
                "odt",
                "ods",
                "odp" ->
                    ZipFile(file).use { require(it.getEntry("mimetype") != null) { "ODF 结果结构不完整" } }
                "rtf" ->
                    file.inputStream().use { input ->
                        val head = ByteArray(5)
                        require(
                            input.read(head) == 5 && head.toString(Charsets.US_ASCII) == "{\\rtf"
                        ) {
                            "RTF 结果格式不正确"
                        }
                    }
            }
        } catch (error: Throwable) {
            if (!finished) atomic.failWrite(stream)
            file.delete()
            throw error
        }
        DocumentJobs.checkpointFile(
            repo,
            job,
            "office-output",
            file,
            JSONObject().put("signature", signature).put("protocol", 1),
        )
        return file
    }
}
