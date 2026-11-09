package cn.paperforge.app.share

import android.content.Context
import android.net.Uri
import cn.paperforge.app.pdf.PdfEngine
import cn.paperforge.app.pdf.PdfWatermarks
import java.io.File

/**
 * #28 share pipeline: take a PDF handed over by another app, strip its generic watermark stains and
 * optionally compress it, then hand the result back for forwarding. The shared source stays in the
 * cache directory; nothing is imported into the library.
 */
object ShareEnhance {
    data class Result(
        val file: File,
        val watermarks: PdfWatermarks.Report?,
        val compressed: Boolean,
    )

    suspend fun process(
        context: Context,
        uri: Uri,
        removeWatermark: Boolean,
        compress: Boolean,
        quality: Int = 75,
        status: (String) -> Unit = {},
    ): Result {
        val root = File(context.cacheDir, "share").apply { mkdirs() }
        val dir = java.nio.file.Files.createTempDirectory(root.toPath(), "job-").toFile()
        val stamp = System.currentTimeMillis()
        val sourceName =
            runCatching {
                    context.contentResolver
                        .query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                .getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "分享文档.pdf"
        val baseName =
            cn.paperforge.app.model.safeFileName(sourceName.replace(Regex("(?i)\\.pdf$"), ""))
        val input = File(dir, "shared-$stamp.pdf")
        status("正在读取分享的文件…")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            input.outputStream().use { stream.copyTo(it) }
        } ?: error("无法读取分享的文件")
        var current = input
        var report: PdfWatermarks.Report? = null
        if (removeWatermark) {
            status("正在去水印…")
            val cleaned = File(dir, "cleaned-$stamp.pdf")
            report = PdfWatermarks.remove(current, cleaned)
            current = cleaned
        }
        var compressed = false
        if (compress) {
            status("正在压缩 PDF…")
            val target = File(dir, "final-$stamp.pdf")
            PdfEngine.compress(current, target, quality) {}
            current = target
            compressed = true
        }
        val named = File(dir, "$baseName.pdf")
        check(current.renameTo(named)) { "无法保存处理后的文件" }
        return Result(named, report, compressed)
    }

    /** Drops cached share artefacts older than a day; called before a new share is handled. */
    fun prune(context: Context) {
        val dir = File(context.cacheDir, "share")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
    }
}
