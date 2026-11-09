package cn.paperforge.network.lan

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Read-only raster previews of complete transport copies; no external viewer is launched. */
internal object LanFilePreview {
    fun pages(file: File, mime: String): Int =
        when {
            mime == "application/pdf" ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            mime.startsWith("image/") -> 1
            else -> 0
        }

    fun render(file: File, mime: String, index: Int, edge: Int = 1400): Bitmap? {
        if (mime == "application/pdf")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    require(index in 0 until pdf.pageCount) { "页码超出范围" }
                    pdf.openPage(index).use { page ->
                        val scale = edge.toFloat() / maxOf(page.width, page.height)
                        Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            .apply {
                                eraseColor(Color.WHITE)
                                page.render(
                                    this,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                            }
                    }
                }
            }
        if (!mime.startsWith("image/")) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > edge * 2) options
            .inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options) ?: error("无法读取图片")
    }
}
