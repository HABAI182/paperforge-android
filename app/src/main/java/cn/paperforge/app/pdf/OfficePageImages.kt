package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.data.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Static pages from the actual phone Office rendering, with independently reusable page files. */
object OfficePageImages {
    suspend fun convert(
        repo: Repository,
        job: JobHandle,
        progress: suspend (Int, String) -> Unit,
    ): File {
        val options = JSONObject(job.input.getString("options") ?: "{}")
        val format = options.optString("format", "png")
        val edge = options.optInt("edge", 2200)
        val quality = options.optInt("quality", 92)
        require(format in setOf("png", "jpg", "webp") && edge in 128..8192 && quality in 1..100) {
            "请选择有效的图片格式、尺寸和质量"
        }
        val pdfInput =
            JSONObject(job.input.json.toString())
                .put("options", JSONObject().put("format", "pdf").toString())
        val rendered =
            OfficeJobs.convert(repo, job.copy(input = JobInput(pdfInput))) { p, detail ->
                progress(p * 40 / 100, detail)
            }
        val pdfHash = PdfAssembly.sha256(rendered)
        val signature = "office-pages-r1|$pdfHash|$format|$edge|$quality"
        val version =
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
                .take(24)
        val directory =
            File(repo.context.filesDir, "job-items/${job.row.id}/office-pages/$version").apply {
                mkdirs()
            }
        val source = File(job.input.getStringArray("inputs")!!.first())
        val pages = JSONArray()
        val output =
            OutputNames.create(
                repo,
                job.row.id,
                OutputNames.sourceStem(job.input.getString("name") ?: source.name),
                "zip",
            )
        val part = File(output.path + ".part")
        try {
            ParcelFileDescriptor.open(rendered, ParcelFileDescriptor.MODE_READ_ONLY).use {
                descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    ZipOutputStream(part.outputStream().buffered()).use { zip ->
                        for (index in 0 until renderer.pageCount) {
                            currentCoroutineContext().ensureActive()
                            DocumentJobs.checkCurrent(repo, job)
                            val key = "office-page-$version-$index"
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
                            val image =
                                ready
                                    ?: File(directory, "page-%04d.%s".format(index + 1, format))
                                        .also { file ->
                                            renderer.openPage(index).use { page ->
                                                val scale =
                                                    edge.toFloat() / maxOf(page.width, page.height)
                                                val bitmap =
                                                    Bitmap.createBitmap(
                                                        (page.width * scale)
                                                            .toInt()
                                                            .coerceAtLeast(1),
                                                        (page.height * scale)
                                                            .toInt()
                                                            .coerceAtLeast(1),
                                                        Bitmap.Config.ARGB_8888,
                                                    )
                                                val atomic = android.util.AtomicFile(file)
                                                val stream = atomic.startWrite()
                                                try {
                                                    bitmap.eraseColor(Color.WHITE)
                                                    page.render(
                                                        bitmap,
                                                        null,
                                                        null,
                                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                                    )
                                                    val codec =
                                                        when (format) {
                                                            "png" -> Bitmap.CompressFormat.PNG
                                                            "webp" ->
                                                                @Suppress("DEPRECATION")
                                                                Bitmap.CompressFormat.WEBP
                                                            else -> Bitmap.CompressFormat.JPEG
                                                        }
                                                    check(bitmap.compress(codec, quality, stream)) {
                                                        "图片编码失败"
                                                    }
                                                    currentCoroutineContext().ensureActive()
                                                    atomic.finishWrite(stream)
                                                } catch (failure: Throwable) {
                                                    atomic.failWrite(stream)
                                                    throw failure
                                                } finally {
                                                    bitmap.recycle()
                                                }
                                            }
                                            DocumentJobs.checkpointFile(
                                                repo,
                                                job,
                                                key,
                                                file,
                                                JSONObject().put("signature", signature),
                                            )
                                        }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(image.path, bounds)
                            val name = "page-%04d.%s".format(index + 1, format)
                            zip.putNextEntry(ZipEntry(name))
                            image.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            pages.put(
                                JSONObject()
                                    .put("page", index + 1)
                                    .put("file", name)
                                    .put("width", bounds.outWidth)
                                    .put("height", bounds.outHeight)
                                    .put("reused", ready != null)
                            )
                            progress(
                                40 + (index + 1) * 55 / renderer.pageCount,
                                "静态图片 ${index+1}/${renderer.pageCount}",
                            )
                        }
                        zip.putNextEntry(ZipEntry("images.json"))
                        zip.write(
                            JSONObject()
                                .put("format", 1)
                                .put("sourceSha256", PdfAssembly.sha256(source))
                                .put("renderedPdfSha256", pdfHash)
                                .put("imageFormat", format)
                                .put("edge", edge)
                                .put("quality", quality)
                                .put("staticPages", true)
                                .put("note", "每页为静态图片，动画、音频、备注和可编辑对象不随图片保留")
                                .put("pages", pages)
                                .toString(2)
                                .toByteArray()
                        )
                        zip.closeEntry()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法保存图片包" }
            return output
        } finally {
            part.delete()
        }
    }
}
