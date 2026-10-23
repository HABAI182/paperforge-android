package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.parsePageRange
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.*
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

object PdfTools {
    fun splitEach(input: File, output: File) {
        val folder = File(output.parentFile, "split-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                ZipOutputStream(output.outputStream()).use { zip ->
                    for (i in 0 until doc.numberOfPages) {
                        val page = File(folder, "page-${i+1}.pdf")
                        PdfEngine.extract(input, "${i+1}", page)
                        zip.putNextEntry(ZipEntry(page.name))
                        page.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        page.delete()
                    }
                }
            }
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }

    fun write(input: File, output: File, password: String = "", action: (PDDocument) -> Unit) {
        val temp = File(output.path + ".part")
        try {
            PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                action(doc)
                doc.save(temp)
            }
            PDDocument.load(
                    temp,
                    if (output.extension == "encrypted") password else "",
                    MemoryUsageSetting.setupTempFileOnly(),
                )
                .use { require(it.numberOfPages > 0) }
            check(temp.renameTo(output))
        } finally {
            temp.delete()
        }
    }

    fun encrypt(
        input: File,
        output: File,
        password: String,
        allowPrint: Boolean = true,
        allowCopy: Boolean = true,
    ) {
        require(password.isNotEmpty()) { "请输入打开密码" }
        val temp = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val permissions =
                    AccessPermission().apply {
                        setCanPrint(allowPrint)
                        setCanExtractContent(allowCopy)
                    }
                val policy =
                    StandardProtectionPolicy(UUID.randomUUID().toString(), password, permissions)
                        .apply {
                            encryptionKeyLength = 256
                            isPreferAES = true
                        }
                doc.protect(policy)
                doc.save(temp)
            }
            PDDocument.load(temp, password).use { require(it.isEncrypted) }
            check(temp.renameTo(output))
        } finally {
            temp.delete()
        }
    }

    fun decrypt(input: File, output: File, password: String) =
        write(input, output, password) { it.isAllSecurityToBeRemoved = true }

    fun watermark(
        context: Context,
        input: File,
        output: File,
        text: String,
        range: String = "",
        opacity: Float = .18f,
        numbered: Boolean = false,
    ) =
        write(input, output) { doc ->
            val font = PdfTextLayer.font(context, doc)
            parsePageRange(range, doc.numberOfPages).forEach { index ->
                val page = doc.getPage(index)
                val box = page.cropBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { c ->
                        c.saveGraphicsState()
                        c.setGraphicsStateParameters(
                            PDExtendedGraphicsState().apply {
                                nonStrokingAlphaConstant =
                                    if (numbered) 1f else opacity.coerceIn(.05f, 1f)
                            }
                        )
                        val label =
                            PdfTextLayer.safe(
                                font,
                                if (numbered) "${index+1} / ${doc.numberOfPages}" else text,
                            )
                        val size =
                            if (numbered) 12f
                            else (box.width / label.length.coerceAtLeast(5)).coerceIn(18f, 64f)
                        c.beginText()
                        c.setFont(font, size)
                        c.setNonStrokingColor(95, 95, 95)
                        if (numbered)
                            c.newLineAtOffset(
                                box.lowerLeftX + box.width / 2 -
                                    font.getStringWidth(label) * size / 2000,
                                box.lowerLeftY + 18,
                            )
                        else {
                            c.setTextMatrix(
                                Matrix.getRotateInstance(
                                    Math.PI / 6,
                                    box.lowerLeftX + box.width / 2,
                                    box.lowerLeftY + box.height / 2,
                                )
                            )
                            c.newLineAtOffset(-font.getStringWidth(label) * size / 2000, 0f)
                        }
                        c.showText(label)
                        c.endText()
                        c.restoreGraphicsState()
                    }
            }
        }

    fun crop(input: File, output: File, percent: Float, range: String = "") =
        write(input, output) { doc ->
            require(percent in 0f..40f)
            parsePageRange(range, doc.numberOfPages).forEach { n ->
                val p = doc.getPage(n)
                val b = p.cropBox
                val dx = b.width * percent / 100
                val dy = b.height * percent / 100
                p.cropBox =
                    PDRectangle(
                        b.lowerLeftX + dx,
                        b.lowerLeftY + dy,
                        b.width - 2 * dx,
                        b.height - 2 * dy,
                    )
            }
        }

    fun insertBlank(input: File, output: File, after: Int) =
        write(input, output) { doc ->
            require(after in 0..doc.numberOfPages)
            val page = PDPage(PDRectangle.A4)
            if (after == doc.numberOfPages) doc.addPage(page)
            else doc.pages.insertBefore(page, doc.getPage(after))
        }

    fun metadata(input: File, output: File, title: String, author: String) =
        write(input, output) { doc ->
            doc.documentInformation.title = title
            doc.documentInformation.author = author
        }

    suspend fun optimize(input: File, output: File) {
        PdfImageOptimizer.compress(input, output, 90)
    }

    fun nup(input: File, output: File, count: Int) {
        require(count == 2 || count == 4)
        val temp = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { src ->
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { dst ->
                    val utility = LayerUtility(dst)
                    val columns = 2
                    val rows = if (count == 4) 2 else 1
                    for (start in 0 until src.numberOfPages step count) {
                        val size =
                            if (count == 2) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
                            else PDRectangle.A4
                        val page = PDPage(size)
                        dst.addPage(page)
                        PDPageContentStream(dst, page).use { c ->
                            for (slot in 0 until count) {
                                val n = start + slot
                                if (n >= src.numberOfPages) break
                                val form = utility.importPageAsForm(src, n)
                                val b = form.bBox
                                val cellW = size.width / columns
                                val cellH = size.height / rows
                                val scale = minOf((cellW - 16) / b.width, (cellH - 16) / b.height)
                                c.saveGraphicsState()
                                c.transform(
                                    Matrix.getTranslateInstance(
                                        (slot % columns) * cellW + (cellW - b.width * scale) / 2,
                                        (rows - 1 - slot / columns) * cellH +
                                            (cellH - b.height * scale) / 2,
                                    )
                                )
                                c.transform(Matrix.getScaleInstance(scale, scale))
                                c.drawForm(form)
                                c.restoreGraphicsState()
                            }
                        }
                    }
                    dst.save(temp)
                }
            }
            PDDocument.load(temp).use { require(it.numberOfPages > 0) }
            check(temp.renameTo(output))
        } finally {
            temp.delete()
        }
    }

    suspend fun images(
        input: File,
        output: File,
        range: String,
        format: String = "jpg",
        edge: Int = 2000,
        progress: suspend (Int) -> Unit = {},
    ) {
        val temp = File(output.path + ".part")
        try {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val selected = parsePageRange(range, renderer.pageCount)
                    ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                        selected.forEachIndexed { i, n ->
                            coroutineContext.ensureActive()
                            renderer.openPage(n).use { page ->
                                val scale = edge.toFloat() / maxOf(page.width, page.height)
                                val bitmap =
                                    Bitmap.createBitmap(
                                        (page.width * scale).toInt().coerceAtLeast(1),
                                        (page.height * scale).toInt().coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    zip.putNextEntry(ZipEntry("page-%04d.%s".format(n + 1, format)))
                                    bitmap.compress(
                                        if (format == "png") Bitmap.CompressFormat.PNG
                                        else Bitmap.CompressFormat.JPEG,
                                        92,
                                        zip,
                                    )
                                    zip.closeEntry()
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            progress((i + 1) * 100 / selected.size)
                        }
                    }
                }
            }
            check(temp.renameTo(output))
        } finally {
            temp.delete()
        }
    }

    suspend fun extractImages(input: File, output: File) {
        val temporary = File(output.path + ".part")
        var count = 0
        val seen =
            java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSStream, Boolean>()
            )
        val resourcesSeen =
            java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary, Boolean>()
            )
        try {
            ZipOutputStream(temporary.outputStream()).use { zip ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    suspend fun walk(resources: PDResources?, page: Int, depth: Int = 0) {
                        if (
                            resources == null ||
                                depth > 32 ||
                                !resourcesSeen.add(resources.cosObject)
                        )
                            return
                        for (name in resources.xObjectNames) {
                            coroutineContext.ensureActive()
                            when (val value = resources.getXObject(name)) {
                                is PDImageXObject ->
                                    if (seen.add(value.cosObject)) {
                                        require(
                                            value.width.toLong() * value.height <= 18_000_000L
                                        ) {
                                            "嵌入图片超过解码预算，请先压缩 PDF"
                                        }
                                        val bitmap = value.image
                                        try {
                                            zip.putNextEntry(
                                                ZipEntry("page-${page+1}-image-${++count}.png")
                                            )
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                                            zip.closeEntry()
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject ->
                                    walk(value.resources, page, depth + 1)
                            }
                        }
                    }
                    doc.pages.forEachIndexed { index, page -> walk(page.resources, index) }
                }
            }
            require(count > 0) { "没有可提取的 XObject 图片；暂不提取内联图片" }
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
    }

    fun forms(input: File): List<Pair<String, String>> =
        PDDocument.load(input).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use emptyList()
            require(!form.hasXFA()) { "暂不支持 XFA 表单" }
            form.fieldTree
                .filter { it is PDTerminalField && it !is PDSignatureField && !it.isReadOnly }
                .map { it.fullyQualifiedName to it.valueAsString }
        }

    fun fillForms(
        input: File,
        output: File,
        values: Map<String, String>,
        context: Context? = null,
    ) =
        write(input, output) { doc ->
            val form = doc.documentCatalog.acroForm ?: error("PDF 没有 AcroForm 字段")
            require(!form.hasXFA()) { "暂不支持 XFA 表单" }
            if (values.values.any { value -> value.any { it.code > 255 } } && context != null) {
                val resources =
                    form.defaultResources ?: PDResources().also { form.defaultResources = it }
                val font =
                    context.assets.open("fonts/PaperForgeCJK-Regular.ttf").use {
                        com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it, false)
                    }
                resources.put(COSName.getPDFName("PaperForgeCJK"), font)
                form.fieldTree.filterIsInstance<PDVariableText>().forEach {
                    it.defaultAppearance = "/PaperForgeCJK 12 Tf 0 g"
                }
            }
            values.forEach { (key, value) ->
                (form.getField(key) ?: error("找不到表单字段 $key")).setValue(value)
            }
            form.refreshAppearances()
        }

    suspend fun exportText(input: File, output: File, kind: String) {
        PdfTextExport.export(input, output, kind)
    }
}
