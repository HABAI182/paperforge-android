package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.imaging.PageDecoder
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Actual cleanup engine on the displayed page, without generating an entire output document. */
object WatermarkPreviews {
    data class Original(val bitmap: Bitmap, val pages: Int)

    data class After(val bitmap: Bitmap, val removed: Int, val detail: String)

    private fun folder(context: Context) =
        File(context.cacheDir, "watermark-previews").apply { mkdirs() }

    private suspend fun officePdf(context: Context, file: File): File {
        val target = File(folder(context), PdfAssembly.sha256(file) + ".pdf")
        if (!target.isFile || target.length() == 0L) {
            val part = File(target.path + ".part")
            try {
                part.outputStream().use {
                    OfficeBridge.convert(
                        context,
                        "watermark-preview-${UUID.randomUUID()}",
                        file,
                        it,
                        "pdf",
                    ) { _, _ ->
                    }
                }
                check(part.renameTo(target))
            } finally {
                part.delete()
            }
        }
        return target
    }

    private fun pdf(file: File, page: Int): Original =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { p ->
                    val scale = 1100f / maxOf(p.width, p.height)
                    val bitmap =
                        Bitmap.createBitmap(
                            (p.width * scale).toInt().coerceAtLeast(1),
                            (p.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } catch (e: Throwable) {
                        bitmap.recycle()
                        throw e
                    }
                    Original(bitmap, renderer.pageCount)
                }
            }
        }

    suspend fun original(context: Context, file: File, page: Int): Original {
        if (file.extension.lowercase() in DocumentWatermarks.packages + DocumentWatermarks.legacy)
            return pdf(officePdf(context, file), page)
        if (file.extension.equals("pdf", true)) return pdf(file, page)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        opts.inSampleSize = 1
        while (maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 1400) opts.inSampleSize *=
            2
        opts.inJustDecodeBounds = false
        val image = BitmapFactory.decodeFile(file.path, opts) ?: error("无法预览此文件")
        return Original(
            PageDecoder.orient(
                image,
                android.media
                    .ExifInterface(file.path)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1),
            ),
            1,
        )
    }

    suspend fun after(
        context: Context,
        source: File,
        page: Int,
        options: JSONObject,
        before: Bitmap,
    ): After {
        val mode = options.optString("mode", "AUTO")
        if (mode == "REGION")
            return if (options.has("region"))
                After(DocumentWatermarks.repair(before, options), 1, "框选区域修复预览")
            else After(before.copy(Bitmap.Config.ARGB_8888, false), 0, "在原图上拖动框选修复区域")
        if (mode == "TEXT" && options.optString("text").isBlank())
            return After(before.copy(Bitmap.Config.ARGB_8888, false), 0, "填写水印文字后更新预览")
        val modern = source.extension.lowercase() in DocumentWatermarks.packages
        if (modern) {
            val signature =
                PdfAssembly.sha256(source) +
                    "|$mode|${options.optString("text")}|${options.optBoolean("background", true)}"
            val key =
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            val cleaned = File(folder(context), "$key.${source.extension}")
            val report = File(folder(context), "$key.json")
            if (!cleaned.isFile || !report.isFile) {
                val part = File(cleaned.path + ".part")
                try {
                    val count =
                        DocumentWatermarks.office(
                                source,
                                part,
                                if (mode == "TEXT") options.optString("text") else "",
                                options.optBoolean("background", true),
                            )
                            .removed
                    check(part.renameTo(cleaned))
                    report.writeText(JSONObject().put("removed", count).toString())
                } finally {
                    part.delete()
                }
            }
            val count = JSONObject(report.readText()).getInt("removed")
            val result = pdf(officePdf(context, cleaned), page)
            return After(
                result.bitmap,
                count,
                if (count == 0) "未找到匹配水印，预览保留原内容" else "文档已清理 $count 项水印",
            )
        }
        val input =
            if (source.extension.lowercase() in DocumentWatermarks.legacy)
                officePdf(context, source)
            else source
        require(input.extension.equals("pdf", true)) { "图片请使用框选修复" }
        val one = File.createTempFile("watermark-page-", ".pdf", folder(context))
        val cleaned = File.createTempFile("watermark-result-", ".pdf", folder(context))
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                PDDocument().use { selected ->
                    val original = doc.getPage(page.coerceIn(0, doc.numberOfPages - 1))
                    selected.importPage(original).resources = original.resources
                    selected.save(one)
                }
            }
            val count =
                if (mode == "TEXT")
                    PdfWatermarks.removeMatching(one, cleaned, options.getString("text"))
                else PdfWatermarks.remove(one, cleaned).let { it.imagesRemoved + it.textCovers }
            val result = pdf(cleaned, 0)
            return After(
                result.bitmap,
                count,
                if (count == 0) "当前页未找到匹配水印，可尝试指定文字或框选" else "当前页已清理 $count 项水印",
            )
        } finally {
            one.delete()
            cleaned.delete()
        }
    }
}
