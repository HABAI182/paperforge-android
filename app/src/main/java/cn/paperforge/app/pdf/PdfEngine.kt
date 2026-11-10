package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.parsePageRange
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
        ocrEngine: cn.paperforge.app.ocr.OcrEngine? = null,
        lossless: Boolean = false,
        progress: suspend (Int) -> Unit = {},
    ) {
        require(pages.isNotEmpty()) { "请先添加页面" }
        atomic(output) { temp ->
            val mapping = PdfTextLayer.Mapping()
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = if (searchable) PdfTextLayer.font(requireNotNull(context), doc) else null
                val engine =
                    if (searchable)
                        ocrEngine
                            ?: cn.paperforge.app.ocr.OcrRequest.current(
                                    (requireNotNull(context).applicationContext
                                            as cn.paperforge.app.PaperForgeApp)
                                        .ocrEngine
                                        .id
                                )
                                .bind(
                                    (context.applicationContext as cn.paperforge.app.PaperForgeApp)
                                        .ocrEngine
                                )
                    else null
                mapping.useFonts(engine?.fontFiles.orEmpty())
                pages.forEachIndexed { index, page ->
                    coroutineContext.ensureActive()
                    var layout =
                        if (engine == null) null
                        else
                            page.ocrLayout
                                ?.let { runCatching { OcrLayout.decode(it) }.getOrNull() }
                                ?.takeIf {
                                    it.matches(page) &&
                                        (it.requestSignature ==
                                            engine.signature(
                                                cn.paperforge.app.UserSettings[
                                                        SettingKeys.OcrLanguage] != "LATIN",
                                                cn.paperforge.app.ocr.OcrOptions.current(),
                                            ) || it.lines.any { line -> line.corrected })
                                }
                                ?: engine.layout(
                                    page,
                                    cn.paperforge.app.UserSettings[SettingKeys.OcrLanguage] !=
                                        "LATIN",
                                )
                    val dpi = cn.paperforge.app.imaging.ScanConfig.dpi(resolution)
                    val presetEdge = cn.paperforge.app.imaging.ScanConfig.edge(resolution)
                    // The camera's photo, untouched: embedded as the camera saved it (no resize, no second JPEG).
                    if (
                        !lossless &&
                            resolution != "DRAFT" &&
                            OriginalPhoto.untouched(page) &&
                            (layout == null || (layout!!.orientationCorrection == 0 && layout!!.deskewDegrees == 0f))
                    ) {
                        val (pw, ph) = requireNotNull(OriginalPhoto.size(page))
                        // The page keeps the physical size the preset gives; only its pixels are the original's.
                        val fit = minOf(1f, presetEdge.toFloat() / maxOf(pw, ph))
                        val bw = pw * fit
                        val bh = ph * fit
                        val size =
                            when (paper) {
                                "A5" -> PDRectangle.A5
                                "Letter" -> PDRectangle.LETTER
                                "Auto",
                                "Original" -> PDRectangle(bw * 72f / dpi, bh * 72f / dpi)
                                else -> PDRectangle.A4
                            }
                        val card = paper == "ID"
                        val pdfPage =
                            if (card && index % 2 == 1) doc.getPage(doc.numberOfPages - 1)
                            else PDPage(size).also { doc.addPage(it) }
                        val margin = if (paper in listOf("Auto", "Original")) 0f else 12f
                        val scale =
                            if (card) minOf(242.65f / bw, 153.07f / bh)
                            else minOf((size.width - 2 * margin) / bw, (size.height - 2 * margin) / bh)
                        val w = bw * scale
                        val h = bh * scale
                        val image =
                            java.io.FileInputStream(page.original).use {
                                com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(doc, it)
                            }
                        val x = (size.width - w) / 2
                        val y =
                            if (card) (if (index % 2 == 0) size.height * .66f else size.height * .33f) - h / 2
                            else (size.height - h) / 2
                        PDPageContentStream(doc, pdfPage, PDPageContentStream.AppendMode.APPEND, true, true)
                            .use { it.drawImage(image, x, y, w, h) }
                        if (font != null && layout != null)
                            PdfTextLayer.append(doc, pdfPage, font, layout!!, x, y, w, h, mapping)
                        progress((index + 1) * 100 / pages.size)
                        return@forEachIndexed
                    }
                    // An Original page is the photo itself: rendered at the photo's own resolution, not shrunk
                    // to the preset (unless DRAFT asks for small files).
                    val renderEdge =
                        if (page.filter == "ORIGINAL" && resolution != "DRAFT")
                            maxOf(presetEdge, OriginalPhoto.nativeEdge(page))
                        else presetEdge
                    var bitmap = ScanProcessor.render(page, renderEdge)
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
                                "Original" -> {
                                    // Physical size as at the preset, however many pixels the page has.
                                    val fit = minOf(1f, presetEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
                                    PDRectangle(bitmap.width * fit * 72f / dpi, bitmap.height * fit * 72f / dpi)
                                }
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
                            if (lossless)
                                com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
                                    .createFromImage(doc, bitmap)
                            else
                                PdfImageEncoder.scan(
                                    doc,
                                    bitmap,
                                    if (
                                        page.filter == "ORIGINAL" &&
                                            cn.paperforge.app.data.PageResources.metadata(page)
                                                .has("flatDocument")
                                    )
                                        "WORKBOOK"
                                    else page.filter,
                                    // An Original page is the photo: a third JPEG generation at the general
                                    // export quality (86 by default) blurred the detail it is kept for.
                                    if (page.filter == "ORIGINAL") maxOf(quality, 95) else quality,
                                    effectiveDpi,
                                )
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
                            PdfTextLayer.append(doc, pdfPage, font, layout!!, x, y, w, h, mapping)
                    } finally {
                        bitmap.recycle()
                    }
                    progress((index + 1) * 100 / pages.size)
                }
                doc.documentInformation.title = output.nameWithoutExtension
                doc.documentInformation.creator = "PaperForge"
                doc.save(temp)
            }
            mapping.finish(temp)
        }
    }

    suspend fun merge(inputs: List<File>, output: File) {
        require(inputs.size >= 2) { "请选择至少两份 PDF" }
        val sources =
            inputs.mapIndexed { index, file ->
                AssemblySource(
                    index.toString(),
                    file.nameWithoutExtension,
                    PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use {
                        it.numberOfPages
                    },
                    "",
                )
            }
        val plan =
            AssemblyPlan(
                sources,
                sources.flatMap { source ->
                    List(source.count) { AssemblyPage(sourceId = source.documentId, page = it) }
                },
                title = output.nameWithoutExtension,
            )
        PdfAssembly.writeFiles(
            null,
            plan,
            sources.mapIndexed { index, source -> source.documentId to inputs[index] }.toMap(),
            output,
        )
    }

    suspend fun extract(input: File, range: String, output: File, rotation: Int = 0) {
        val count =
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { it.numberOfPages }
        organize(
            input,
            output,
            parsePageRange(range, count),
            parsePageRange(range, count).associateWith { rotation },
        )
    }

    suspend fun organize(input: File, output: File, order: List<Int>, rotations: Map<Int, Int>) {
        require(order.isNotEmpty()) { "至少保留一页" }
        val count =
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { it.numberOfPages }
        require(order.all { it in 0 until count }) { "页码超出范围" }
        val source = AssemblySource("source", input.nameWithoutExtension, count, "")
        val plan =
            AssemblyPlan(
                listOf(source),
                order.map {
                    AssemblyPage(
                        sourceId = source.documentId,
                        page = it,
                        rotation = rotations[it] ?: 0,
                    )
                },
                title = output.nameWithoutExtension,
            )
        PdfAssembly.writeFiles(null, plan, mapOf(source.documentId to input), output)
    }

    fun text(input: File): String =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use {
            ReadablePdfTextStripper(it).apply { sortByPosition = true }.getText(it)
        }

    suspend fun compress(
        input: File,
        output: File,
        quality: Int,
        progress: suspend (Int) -> Unit = {},
    ) {
        PdfImageOptimizer.compress(input, output, quality, progress = progress)
    }

    suspend fun flatten(
        input: File,
        output: File,
        quality: Int,
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ) {
        if (stages != null) {
            RasterPageStages.convert(
                input,
                output,
                stages,
                "flatten|$quality",
                if (quality < 60) 1100 else 1800,
                encode = { doc, bitmap -> PdfImageEncoder.jpeg(doc, bitmap, false, quality, 150) },
                progress = progress,
            )
            return
        }
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
