package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.parsePageRange
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

object PdfEngine {
    suspend fun scan(
        pages: List<PageRow>,
        output: File,
        paper: String = "Auto",
        quality: Int = 90,
        context: Context? = null,
        searchable: Boolean = false,
        resolution: String = "STANDARD",
        progress: suspend (Int) -> Unit = {},
    ) {
        require(pages.isNotEmpty()) { "请先添加页面" }
        atomic(output) { temp ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = if (searchable) PdfTextLayer.font(requireNotNull(context), doc) else null
                val engine =
                    if (searchable)
                        (requireNotNull(context).applicationContext
                                as cn.paperforge.app.PaperForgeApp)
                            .ocrEngine
                    else null
                pages.forEachIndexed { index, page ->
                    coroutineContext.ensureActive()
                    var layout =
                        if (engine == null) null
                        else
                            page.ocrLayout
                                ?.let { runCatching { OcrLayout.decode(it) }.getOrNull() }
                                ?.takeIf {
                                    it.sourceRevision == page.revision &&
                                        (it.engineId == engine.id ||
                                            it.lines.any { line -> line.corrected })
                                }
                                ?: engine.layout(
                                    page,
                                    cn.paperforge.app.UserSettings.string(
                                        "ocrLanguage",
                                        "CHINESE",
                                    ) != "LATIN",
                                )
                    val dpi = cn.paperforge.app.imaging.ScanConfig.dpi(resolution)
                    var bitmap =
                        ScanProcessor.render(
                            page,
                            cn.paperforge.app.imaging.ScanConfig.edge(resolution),
                        )
                    try {
                        layout
                            ?.takeIf { it.orientationCorrection != 0 }
                            ?.let { l ->
                                val old = bitmap
                                bitmap =
                                    cn.paperforge.app.imaging.PageOrientation.rotate(
                                        old,
                                        l.orientationCorrection.toFloat(),
                                    )
                                old.recycle()
                                layout = l.rotated(l.orientationCorrection)
                            }
                        layout
                            ?.takeIf { it.deskewDegrees != 0f }
                            ?.let { l ->
                                val geometry =
                                    cn.paperforge.app.model.DeskewGeometry(
                                        bitmap.width,
                                        bitmap.height,
                                        l.deskewDegrees,
                                    )
                                val old = bitmap
                                bitmap =
                                    cn.paperforge.app.imaging.PageOrientation.rotate(
                                        old,
                                        l.deskewDegrees,
                                    )
                                old.recycle()
                                layout = geometry.map(l, true).copy(deskewDegrees = 0f)
                            }
                        val matched = paper in listOf("Auto", "Original")
                        val size =
                            when (paper) {
                                "A5" -> PDRectangle.A5
                                "Letter" -> PDRectangle.LETTER
                                "Auto",
                                "Original" ->
                                    PDRectangle(bitmap.width * 72f / dpi, bitmap.height * 72f / dpi)
                                else -> PDRectangle.A4
                            }
                        val card = paper == "ID"
                        val pdfPage =
                            if (card && index % 2 == 1) doc.getPage(doc.numberOfPages - 1)
                            else PDPage(size).also { doc.addPage(it) }
                        val margin = if (matched) 0f else 12f
                        val scale =
                            if (card) minOf(242.65f / bitmap.width, 153.07f / bitmap.height)
                            else
                                minOf(
                                    (size.width - 2 * margin) / bitmap.width,
                                    (size.height - 2 * margin) / bitmap.height,
                                )
                        val w = bitmap.width * scale
                        val h = bitmap.height * scale
                        val effectiveDpi =
                            kotlin.math.round(bitmap.width * 72f / w).toInt().coerceAtLeast(1)
                        val image =
                            PdfImageEncoder.scan(doc, bitmap, page.filter, quality, effectiveDpi)
                        val x = (size.width - w) / 2
                        val y =
                            if (card)
                                (if (index % 2 == 0) size.height * .66f else size.height * .33f) -
                                    h / 2
                            else (size.height - h) / 2
                        PDPageContentStream(
                                doc,
                                pdfPage,
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true,
                            )
                            .use { it.drawImage(image, x, y, w, h) }
                        if (font != null && layout != null)
                            PdfTextLayer.append(doc, pdfPage, font, layout!!, x, y, w, h)
                    } finally {
                        bitmap.recycle()
                    }
                    progress((index + 1) * 100 / pages.size)
                }
                doc.documentInformation.title = output.nameWithoutExtension
                doc.documentInformation.creator = "PaperForge 纸匠"
                doc.save(temp)
            }
        }
    }

    fun merge(inputs: List<File>, output: File) {
        require(inputs.size >= 2) { "请选择至少两份 PDF" }
        atomicSync(output) { tmp ->
            val merger = PDFMergerUtility()
            inputs.forEach { merger.addSource(it) }
            merger.destinationFileName = tmp.path
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
    }

    fun extract(input: File, range: String, output: File, rotation: Int = 0) {
        atomicSync(output) { tmp ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { src ->
                PDDocument().use { dst ->
                    parsePageRange(range, src.numberOfPages).forEach { n ->
                        val original = src.getPage(n)
                        dst.importPage(original).also {
                            it.resources = original.resources
                            it.rotation = (it.rotation + rotation) % 360
                        }
                    }
                    dst.save(tmp)
                }
            }
        }
    }

    fun organize(input: File, output: File, order: List<Int>, rotations: Map<Int, Int>) {
        require(order.isNotEmpty()) { "至少保留一页" }
        atomicSync(output) { temp ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { source ->
                PDDocument().use { destination ->
                    order.forEach { n ->
                        require(n in 0 until source.numberOfPages)
                        val page = source.getPage(n)
                        destination.importPage(page).also {
                            it.resources = page.resources
                            it.rotation = (page.rotation + (rotations[n] ?: 0)) % 360
                        }
                    }
                    destination.save(temp)
                }
            }
        }
    }

    fun text(input: File): String =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use {
            PDFTextStripper().apply { sortByPosition = true }.getText(it)
        }

    suspend fun compress(
        input: File,
        output: File,
        quality: Int,
        progress: suspend (Int) -> Unit = {},
    ) {
        PdfImageOptimizer.compress(input, output, quality, progress)
    }

    suspend fun flatten(
        input: File,
        output: File,
        quality: Int,
        progress: suspend (Int) -> Unit = {},
    ) {
        // Explicit destructive conversion, separate from normal image-only compression.
        atomic(output) { tmp ->
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        for (n in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            renderer.openPage(n).use { page ->
                                val scale =
                                    (if (quality < 60) 1100f else 1800f) /
                                        maxOf(page.width, page.height)
                                val b =
                                    Bitmap.createBitmap(
                                        (page.width * scale).toInt().coerceAtLeast(1),
                                        (page.height * scale).toInt().coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    b.eraseColor(Color.WHITE)
                                    page.render(
                                        b,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    val p =
                                        PDPage(
                                            PDRectangle(page.width.toFloat(), page.height.toFloat())
                                        )
                                    doc.addPage(p)
                                    PDPageContentStream(doc, p).use {
                                        it.drawImage(
                                            PdfImageEncoder.jpeg(doc, b, false, quality, 150),
                                            0f,
                                            0f,
                                            p.mediaBox.width,
                                            p.mediaBox.height,
                                        )
                                    }
                                } finally {
                                    b.recycle()
                                }
                            }
                            progress((n + 1) * 100 / renderer.pageCount)
                        }
                        doc.save(tmp)
                    }
                }
            }
        }
    }

    private fun verify(file: File) {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use {
            require(it.numberOfPages > 0)
        }
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private suspend fun atomic(output: File, write: suspend (File) -> Unit) {
        val tmp = File(output.path + ".part")
        try {
            write(tmp)
            verify(tmp)
            check(tmp.renameTo(output)) { "保存 PDF 失败" }
        } finally {
            tmp.delete()
        }
    }

    private fun atomicSync(output: File, write: (File) -> Unit) {
        val tmp = File(output.path + ".part")
        try {
            write(tmp)
            verify(tmp)
            check(tmp.renameTo(output)) { "保存 PDF 失败" }
        } finally {
            tmp.delete()
        }
    }
}
