package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.parsePageRange
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PdfLongImages {
    suspend fun convert(
        input: File,
        output: File,
        range: String = "",
        progress: suspend (Int) -> Unit = {},
    ) {
        val temporary = File(output.path + ".part")
        val width = 960
        val limit = 8192
        val bitmap = Bitmap.createBitmap(width, limit, Bitmap.Config.ARGB_8888)
        var used = 0
        var segment = 0
        bitmap.eraseColor(Color.WHITE)
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                fun flush() {
                    if (used == 0) return
                    zip.putNextEntry(ZipEntry("long-%03d.png".format(++segment)))
                    val cropped =
                        if (used < limit) Bitmap.createBitmap(bitmap, 0, 0, width, used) else bitmap
                    try {
                        check(cropped.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    } finally {
                        if (cropped !== bitmap) cropped.recycle()
                    }
                    zip.closeEntry()
                    bitmap.eraseColor(Color.WHITE)
                    used = 0
                }
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val pages = parsePageRange(range, renderer.pageCount)
                        pages.forEachIndexed { index, number ->
                            renderer.openPage(number).use { page ->
                                val scale = width.toFloat() / page.width
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                var offset = 0
                                while (offset < height) {
                                    currentCoroutineContext().ensureActive()
                                    if (used == limit) flush()
                                    val chunk = minOf(height - offset, limit - used)
                                    val matrix =
                                        Matrix().apply {
                                            setScale(scale, scale)
                                            postTranslate(0f, (used - offset).toFloat())
                                        }
                                    page.render(
                                        bitmap,
                                        Rect(0, used, width, used + chunk),
                                        matrix,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    used += chunk
                                    offset += chunk
                                }
                                if (index < pages.lastIndex) used = minOf(limit, used + 16)
                            }
                            progress((index + 1) * 100 / pages.size)
                        }
                    }
                }
                flush()
                zip.putNextEntry(ZipEntry("说明.txt"))
                zip.write(
                    "按页序拼接，宽 960 像素；超过 8192 像素自动连续分段，共 $segment 段。页面内容未裁弃。"
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
            check(temporary.renameTo(output))
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
    }
}
