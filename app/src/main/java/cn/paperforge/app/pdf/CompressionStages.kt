package cn.paperforge.app.pdf

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/**
 * One verified quality candidate includes its report. JPEG checkpoints survive a partial attempt.
 */
object CompressionStages {
    suspend fun compress(
        input: File,
        output: File,
        quality: Int,
        stages: JobFileStages,
        progress: suspend (Int) -> Unit,
    ): JSONObject {
        val signature = "pdf-image-rewrite-v2:${stages.sourceHash(input)}:$quality"
        val folder = File(output.parentFile, "compression-${UUID.randomUUID()}").apply { mkdirs() }
        val part = File(output.path + ".part")
        val reusedBefore = stages.reused
        try {
            val bundle =
                stages.file("compression-quality-$quality", signature, "zip") { destination ->
                    val pdf = File(folder, "candidate.pdf")
                    val report =
                        PdfImageOptimizer.compressCore(input, pdf, quality, stages, progress)
                    FileOutputStream(destination).use { out ->
                        ZipOutputStream(out)
                            .apply { setLevel(Deflater.NO_COMPRESSION) }
                            .use { zip ->
                                zip.putNextEntry(ZipEntry("candidate.pdf"))
                                pdf.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                                zip.putNextEntry(ZipEntry("report.json"))
                                zip.write(report.toString().toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                                zip.finish()
                                out.fd.sync()
                            }
                    }
                }
            var report: JSONObject? = null
            ZipFile(bundle).use { zip ->
                val pdf = zip.getEntry("candidate.pdf") ?: error("压缩阶段缺少PDF")
                val json = zip.getEntry("report.json") ?: error("压缩阶段缺少报告")
                zip.getInputStream(pdf).use { source ->
                    FileOutputStream(part).use { out ->
                        source.copyTo(out)
                        out.fd.sync()
                    }
                }
                report = zip.getInputStream(json).bufferedReader().use { JSONObject(it.readText()) }
            }
            currentCoroutineContext().ensureActive()
            require(part.isFile && part.length() == checkNotNull(report).getLong("outputBytes")) {
                "压缩阶段长度不一致"
            }
            check(part.renameTo(output)) { "无法保存压缩结果" }
            progress(100)
            return checkNotNull(report)
                .put("checkpointReused", stages.reused > reusedBefore)
                .put("checkpointEntries", stages.entries)
        } finally {
            part.delete()
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}
