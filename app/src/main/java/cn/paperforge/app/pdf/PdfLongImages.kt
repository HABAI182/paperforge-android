package cn.paperforge.app.pdf

import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.parsePageRange
import java.io.File
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

object PdfLongImages {
    suspend fun convert(
        input: File,
        output: File,
        range: String = "",
        options: LongImageOptions = LongImageOptions(),
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        options.validate()
        val temporary = File(output.path + ".part")
        val signature =
            if (stages == null) "" else "pdf|${PdfAssembly.sha256(input)}|$range|${options.json()}"
        try {
            var report: JSONObject? = null
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                LongImageWriter(zip, options, stages, signature).use { writer ->
                    ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd
                        ->
                        PdfRenderer(fd).use { renderer ->
                            val pages = parsePageRange(range, renderer.pageCount)
                            pages.forEachIndexed { index, number ->
                                currentCoroutineContext().ensureActive()
                                renderer.openPage(number).use { page ->
                                    val scale = options.width.toFloat() / page.width
                                    val height =
                                        ((page.height.toLong() * options.width + page.width - 1) /
                                                page.width)
                                            .toInt()
                                            .coerceAtLeast(1)
                                    writer.append(number + 1, "PDF 第 ${number+1} 页", height) {
                                        bitmap,
                                        target,
                                        offset ->
                                        val matrix =
                                            Matrix().apply {
                                                setScale(scale, scale)
                                                postTranslate(0f, (target.top - offset).toFloat())
                                            }
                                        page.render(
                                            bitmap,
                                            target,
                                            matrix,
                                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                        )
                                    }
                                }
                                if (index < pages.lastIndex) writer.gap()
                                progress((index + 1) * 100 / pages.size)
                            }
                        }
                    }
                    report = writer.finish()
                }
            }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(output)) { "无法发布长图" }
            return checkNotNull(report)
        } finally {
            temporary.delete()
        }
    }
}
