package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.text.Html
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser

/** Offline, explicit conversion paths. Office input is a paragraph import, not a layout engine. */
object LocalConversions {
    private fun readText(context: Context, uri: Uri, name: String): String {
        if (name.endsWith(".docx", true)) return docxText(context, uri)
        val bytes =
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(buffer.size() + count <= 32 * 1024 * 1024) { "文本过大，请拆分后转换（单次 32 MB）" }
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
        val decoded =
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, Charsets.UTF_16BE)
                else ->
                    runCatching {
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                        }
                        .getOrElse { String(bytes, charset("GB18030")) }
            }.removePrefix("\uFEFF")
        return if (name.endsWith(".html", true) || name.endsWith(".htm", true))
            Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString()
        else decoded
    }

    private fun docxText(context: Context, uri: Uri): String {
        ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "word/document.xml") continue
                val parser = android.util.Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(zip, null)
                return buildString {
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG)
                            when (parser.name) {
                                "t" -> append(parser.nextText())
                                "tab" -> append("    ")
                                "br" -> append('\n')
                            }
                        if (parser.eventType == XmlPullParser.END_TAG)
                            when (parser.name) {
                                "p",
                                "tr" -> append('\n')
                                "tc" -> append("    ")
                            }
                        require(length <= 8 * 1024 * 1024) { "DOCX 文本过大，请拆分后转换" }
                        parser.next()
                    }
                }
            }
        }
        error("未找到 DOCX 正文；旧版 DOC 请先另存为 DOCX")
    }

    suspend fun textToPdf(
        context: Context,
        uri: Uri,
        name: String,
        output: File,
        size: Float = 12f,
    ) {
        val text =
            readText(context, uri, name)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "    ")
        require(text.isNotBlank()) { "文件没有可导入的文字" }
        try {
            PDDocument().use { pdf ->
                val font = PdfTextLayer.font(context, pdf)
                var stream: PDPageContentStream? = null
                var y = 0f
                val margin = 44f
                val lineHeight = size * 1.65f
                val maxWidth = PDRectangle.A4.width - margin * 2
                fun newPage() {
                    stream?.close()
                    val page = PDPage(PDRectangle.A4)
                    pdf.addPage(page)
                    stream = PDPageContentStream(pdf, page)
                    y = PDRectangle.A4.height - margin - size
                }
                fun line(value: String) {
                    if (y < margin) newPage()
                    stream!!.beginText()
                    stream!!.setFont(font, size)
                    stream!!.newLineAtOffset(margin, y)
                    stream!!.showText(value)
                    stream!!.endText()
                    y -= lineHeight
                }
                try {
                    newPage()
                    text.splitToSequence('\n').forEach { paragraph ->
                        currentCoroutineContext().ensureActive()
                        val safe = PdfTextLayer.safe(font, paragraph)
                        val buffer = StringBuilder()
                        var width = 0f
                        safe.codePoints().toArray().forEach { cp ->
                            val ch = String(Character.toChars(cp))
                            val charWidth = font.getStringWidth(ch) * size / 1000
                            if (width + charWidth > maxWidth && buffer.isNotEmpty()) {
                                line(buffer.toString())
                                buffer.clear()
                                width = 0f
                            }
                            buffer.append(ch)
                            width += charWidth
                        }
                        line(buffer.toString())
                    }
                } finally {
                    stream?.close()
                }
                pdf.save(output)
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0) { "无法读取这张图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val bitmap =
            context.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: error("图片解码失败")
        val orientation =
            runCatching {
                    context.contentResolver.openInputStream(uri)!!.use {
                        ExifInterface(it)
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    }
                }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix =
            Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    6 -> setRotate(90f)
                    7 -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    8 -> setRotate(-90f)
                }
            }
        return if (matrix.isIdentity) bitmap
        else
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
    }

    suspend fun images(
        context: Context,
        uris: List<Uri>,
        format: String,
        output: File,
        progress: (String) -> Unit,
    ) {
        val codec =
            when (format) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
        try {
            val zip = if (uris.size > 1) ZipOutputStream(output.outputStream().buffered()) else null
            try {
                uris.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    progress("转换图片 ${index + 1} / ${uris.size}")
                    var bitmap = decode(context, uri)
                    try {
                        if (format == "jpg" && bitmap.hasAlpha()) {
                            val white =
                                Bitmap.createBitmap(
                                    bitmap.width,
                                    bitmap.height,
                                    Bitmap.Config.ARGB_8888,
                                )
                            Canvas(white).apply {
                                drawColor(Color.WHITE)
                                drawBitmap(bitmap, 0f, 0f, null)
                            }
                            bitmap.recycle()
                            bitmap = white
                        }
                        if (zip != null) {
                            zip.putNextEntry(ZipEntry("image-%03d.%s".format(index + 1, format)))
                            check(bitmap.compress(codec, 92, zip)) { "图片编码失败" }
                            zip.closeEntry()
                        } else
                            output.outputStream().use {
                                check(bitmap.compress(codec, 92, it)) { "图片编码失败" }
                            }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                zip?.close()
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
