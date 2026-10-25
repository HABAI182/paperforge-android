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
    fun readText(context: Context, uri: Uri, name: String): String {
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
        options: TextPdfOptions = TextPdfOptions(fontSize = size),
    ) {
        val text =
            readText(context, uri, name)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "    ")
        require(text.isNotBlank()) { "文件没有可导入的文字" }
        textContentToPdf(context, text, output, options)
    }

    suspend fun textContentToPdf(
        context: Context,
        text: String,
        output: File,
        options: TextPdfOptions = TextPdfOptions(),
    ) {
        require(text.isNotBlank()) { "请先输入文字" }
        require(
            options.fontSize.isFinite() &&
                options.fontSize in 6f..72f &&
                options.margin.isFinite() &&
                options.margin >= 0 &&
                options.lineSpacing in 1f..3f
        ) {
            "字号、页边距或行距不合适"
        }
        val base =
            when (options.paper) {
                "A5" -> PDRectangle.A5
                "Letter" -> PDRectangle.LETTER
                else -> PDRectangle.A4
            }
        val paper = if (options.landscape) PDRectangle(base.height, base.width) else base
        val size = options.fontSize
        val part = File(output.path + ".part")
        val unicode = linkedMapOf<Int, String>()
        try {
            PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val font = PdfTextLayer.font(context, pdf)
                var stream: PDPageContentStream? = null
                var y = 0f
                val margin = options.margin
                val lineHeight = size * options.lineSpacing
                val maxWidth = paper.width - margin * 2
                require(maxWidth > size && paper.height - margin * 2 > lineHeight) {
                    "页边距过大，纸张没有足够的正文空间"
                }
                fun newPage() {
                    stream?.close()
                    val page = PDPage(paper)
                    pdf.addPage(page)
                    stream = PDPageContentStream(pdf, page)
                    y = paper.height - margin - size
                }
                fun line(value: String) {
                    if (y < margin) newPage()
                    value.codePoints().toArray().forEach { cp ->
                        val text = String(Character.toChars(cp))
                        val encoded = font.encode(text)
                        if (encoded.size == 2)
                            unicode.putIfAbsent(
                                ((encoded[0].toInt() and 255) shl 8) or
                                    (encoded[1].toInt() and 255),
                                text,
                            )
                    }
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
                pdf.save(part)
            }
            PdfTextUnicode.rewrite(part, unicode)
            PDDocument.load(part).use { require(it.numberOfPages > 0) }
            check(part.renameTo(output)) { "无法发布 PDF" }
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            part.delete()
        }
    }

    private fun decode(context: Context, uri: Uri, edge: Int = 4096): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0) { "无法读取这张图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
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
        val oriented =
            if (matrix.isIdentity) bitmap
            else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
        val longest = maxOf(oriented.width, oriented.height)
        return if (longest <= edge) oriented
        else
            Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width.toLong() * edge / longest).toInt().coerceAtLeast(1),
                    (oriented.height.toLong() * edge / longest).toInt().coerceAtLeast(1),
                    true,
                )
                .also { if (it !== oriented) oriented.recycle() }
    }

    suspend fun image(context: Context, uri: Uri, output: File, options: ImageConversionOptions) {
        require(
            options.format in setOf("jpg", "png", "webp") &&
                options.edge in 128..8192 &&
                options.quality in 1..100
        ) {
            "图片格式、尺寸或质量设置不合适"
        }
        require(
            !(options.format == "webp" &&
                options.losslessWebp &&
                android.os.Build.VERSION.SDK_INT < 30)
        ) {
            "此系统不支持无损 WebP，请选择 PNG"
        }
        val codec =
            when (options.format) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" ->
                    if (options.losslessWebp && android.os.Build.VERSION.SDK_INT >= 30)
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
        val part = File(output.path + ".part")
        var bitmap = decode(context, uri, options.edge)
        try {
            currentCoroutineContext().ensureActive()
            if (bitmap.hasAlpha() && (options.format == "jpg" || !options.keepAlpha)) {
                val opaque =
                    Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply {
                    drawColor(options.background or 0xFF000000.toInt())
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                opaque.setHasAlpha(false)
                bitmap.recycle()
                bitmap = opaque
            }
            java.io.FileOutputStream(part).use { stream ->
                check(bitmap.compress(codec, options.quality, stream)) { "图片编码失败" }
                stream.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "编码后的图片无法读取" }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法发布图片" }
        } finally {
            bitmap.recycle()
            part.delete()
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
