package cn.paperforge.app.pdf

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.ocr.OcrEngine
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/** Editable reading-order structure and explicit full-page appearance are separate modes. */
internal object DocxLayout {
    private fun xml(text: String) = SlidesPackage.xml(text)

    private data class Block(val box: RectF, val xml: String, val kind: String)

    private data class Line(val tokens: List<PhoneTables.Token>) {
        val box =
            RectF(
                tokens.minOf { it.box.left },
                tokens.minOf { it.box.top },
                tokens.maxOf { it.box.right },
                tokens.maxOf { it.box.bottom },
            )
        val size = tokens.map { it.fontSize }.average().toFloat()
        val text = PhoneTables.joinTokens(tokens)
    }

    private fun lines(tokens: List<PhoneTables.Token>): List<Line> {
        val rows = mutableListOf<MutableList<PhoneTables.Token>>()
        tokens
            .sortedBy { it.box.centerY() }
            .forEach { token ->
                val row = rows.lastOrNull()
                if (
                    row == null ||
                        abs(row.map { it.box.centerY() }.average() - token.box.centerY()) >
                            maxOf(.003, token.box.height() * .6)
                )
                    rows += mutableListOf(token)
                else row += token
            }
        return rows.flatMap { row ->
            val segments = mutableListOf<MutableList<PhoneTables.Token>>()
            row.sortedBy { it.box.left }
                .forEach { token ->
                    val segment = segments.lastOrNull()
                    if (segment == null || token.box.left - segment.last().box.right > .045f)
                        segments += mutableListOf(token)
                    else segment += token
                }
            segments.map(::Line)
        }
    }

    private fun ordered(blocks: List<Block>): Pair<List<Block>, Boolean> {
        val narrow =
            blocks.filter { it.box.width() < .48f && it.kind !in setOf("heading", "footnote-text") }
        val left = narrow.filter { it.box.right < .52f }
        val right = narrow.filter { it.box.left > .48f }
        val columns =
            left.size >= 2 &&
                right.size >= 2 &&
                left.maxOf { it.box.right } + .025f < right.minOf { it.box.left }
        if (!columns) return blocks.sortedWith(compareBy({ it.box.top }, { it.box.left })) to false
        val output = mutableListOf<Block>()
        var lower = -1f
        (blocks.filter { it !in narrow }.sortedBy { it.box.top } + listOf<Block?>(null)).forEach {
            boundary ->
            val band =
                narrow.filter { it.box.top >= lower && it.box.top < (boundary?.box?.top ?: 2f) }
            output += band.filter { it.box.centerX() < .5f }.sortedBy { it.box.top }
            output += band.filter { it.box.centerX() >= .5f }.sortedBy { it.box.top }
            if (boundary != null) {
                output += boundary
                lower = boundary.box.top
            }
        }
        return output to true
    }

    private fun runs(tokens: List<PhoneTables.Token>): String = buildString {
        val groups = mutableListOf<MutableList<PhoneTables.Token>>()
        tokens
            .sortedBy { it.box.left }
            .forEach { token ->
                val group = groups.lastOrNull()
                val before = group?.lastOrNull()
                if (
                    before == null ||
                        abs(before.fontSize - token.fontSize) > .2f ||
                        before.bold != token.bold ||
                        before.italic != token.italic
                )
                    groups += mutableListOf(token)
                else group += token
            }
        var previous: PhoneTables.Token? = null
        groups.forEach { group ->
            val t = group.first()
            val prefix =
                previous
                    ?.let {
                        if (
                            t.box.left - it.box.right > .002f &&
                                it.text.lastOrNull()?.isLetterOrDigit() == true &&
                                t.text.firstOrNull()?.isLetterOrDigit() == true &&
                                it.text.last().code < 128 &&
                                t.text.first().code < 128
                        )
                            " "
                        else ""
                    }
                    .orEmpty()
            append(
                "<w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\" w:eastAsia=\"Noto Sans CJK SC\"/><w:sz w:val=\"${(t.fontSize*2).roundToInt().coerceIn(8,400)}\"/>${if(t.bold) "<w:b/>" else ""}${if(t.italic) "<w:i/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${xml(prefix+PhoneTables.joinTokens(group))}</w:t></w:r>"
            )
            previous = group.last()
        }
    }

    private fun paragraph(line: Line, median: Float, width: Float): Block {
        val heading = line.size >= median * 1.25f && line.text.length < 120
        val footnote = line.box.top > .87f && line.size < median * .88f
        val style =
            if (heading) (if (line.size >= median * 1.65f) "Heading1" else "Heading2")
            else if (footnote) "FootnoteText" else "Normal"
        val indent =
            if (line.box.left < .45f)
                (line.box.left * width * 20 - 600).roundToInt().coerceAtLeast(0)
            else 0
        return Block(
            line.box,
            "<w:p><w:pPr><w:pStyle w:val=\"$style\"/><w:spacing w:after=\"30\"/><w:ind w:left=\"$indent\"/></w:pPr>${runs(line.tokens)}</w:p>",
            if (heading) "heading" else if (footnote) "footnote-text" else "paragraph",
        )
    }

    /** Rendered crops preserve clipping/rotation/compositing and do not recycle borrowed images. */
    private class ImageRegions(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val images = mutableListOf<RectF>()
        private val crop = page.cropBox
        private var point = PointF()
        private var start = PointF()
        private var path = Path()
        private var clip: Path.FillType? = null

        private fun displayed(x: Float, y: Float): PointF {
            val u = (x - crop.lowerLeftX) / crop.width
            val v = 1 - (y - crop.lowerLeftY) / crop.height
            return when ((page.rotation % 360 + 360) % 360) {
                90 -> PointF(1 - v, u)
                180 -> PointF(1 - u, 1 - v)
                270 -> PointF(v, 1 - u)
                else -> PointF(u, v)
            }
        }

        override fun drawImage(image: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val points =
                listOf(
                    m.transformPoint(0f, 0f),
                    m.transformPoint(1f, 0f),
                    m.transformPoint(1f, 1f),
                    m.transformPoint(0f, 1f),
                )
            val raw =
                RectF(
                    points.minOf { it.x },
                    points.minOf { it.y },
                    points.maxOf { it.x },
                    points.maxOf { it.y },
                )
            if (!raw.intersect(RectF(graphicsState.currentClippingPath.bounds))) return
            val a = displayed(raw.left, raw.top)
            val b = displayed(raw.right, raw.bottom)
            val region =
                RectF(
                    minOf(a.x, b.x).coerceIn(0f, 1f),
                    minOf(a.y, b.y).coerceIn(0f, 1f),
                    maxOf(a.x, b.x).coerceIn(0f, 1f),
                    maxOf(a.y, b.y).coerceIn(0f, 1f),
                )
            if (
                region.width() > .003f &&
                    region.height() > .003f &&
                    region.width() * region.height() < .8f
            )
                images += region
        }

        override fun appendRectangle(a: PointF, b: PointF, c: PointF, d: PointF) {
            path.moveTo(a.x, a.y)
            path.lineTo(b.x, b.y)
            path.lineTo(c.x, c.y)
            path.lineTo(d.x, d.y)
            path.close()
            point = a
            start = a
        }

        override fun clip(rule: Path.FillType) {
            clip = rule
        }

        override fun moveTo(x: Float, y: Float) {
            path.moveTo(x, y)
            point = PointF(x, y)
            start = point
        }

        override fun lineTo(x: Float, y: Float) {
            path.lineTo(x, y)
            point = PointF(x, y)
        }

        override fun curveTo(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
            path.cubicTo(a, b, c, d, e, f)
            point = PointF(e, f)
        }

        override fun getCurrentPoint() = point

        override fun closePath() {
            path.close()
            point = start
        }

        override fun endPath() {
            clip?.let {
                path.fillType = it
                graphicsState.intersectClippingPath(path)
            }
            clip = null
            path = Path()
        }

        override fun strokePath() = endPath()

        override fun fillPath(rule: Path.FillType) = endPath()

        override fun fillAndStrokePath(rule: Path.FillType) = endPath()

        override fun shadingFill(name: COSName) {}
    }

    private fun drawing(id: Int, cx: Long, cy: Long, page: Boolean): String {
        val shape =
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"${if(page) "Page appearance" else "Visible image crop"} $id\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$id.png\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"image$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"
        val wrapper =
            if (page)
                "<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"0\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionV>${shape.replace("<wp:docPr","<wp:wrapNone/><wp:docPr")}</wp:anchor>"
            else "<wp:inline>$shape</wp:inline>"
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"${if(page) " w:line=\"20\" w:lineRule=\"exact\"" else ""}/></w:pPr><w:r><w:drawing>$wrapper</w:drawing></w:r></w:p>"
    }

    private fun section(width: Float, height: Float, appearance: Boolean): String {
        val margin = if (appearance) 0 else 600
        return "<w:sectPr><w:type w:val=\"nextPage\"/><w:pgSz w:w=\"${(width*20).roundToInt()}\" w:h=\"${(height*20).roundToInt()}\"${if(width>height) " w:orient=\"landscape\"" else ""}/><w:pgMar w:top=\"$margin\" w:right=\"$margin\" w:bottom=\"$margin\" w:left=\"$margin\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
    }

    suspend fun convert(
        input: File,
        output: File,
        ocr: OcrEngine,
        layoutMode: String,
        progress: suspend (Int) -> Unit,
    ): JSONObject {
        val appearance = layoutMode == "appearance"
        val temporary = File(output.path + ".part")
        val pages = JSONArray()
        val report =
            JSONObject()
                .put("format", 1)
                .put("mode", if (appearance) "appearance" else "editable")
                .put(
                    "detail",
                    if (appearance) "实际页面整页图片；不声称文字或表格可编辑。"
                    else
                        "可编辑单栏阅读流、标题样式、表格与实际可见图片裁片。跨栏按阅读顺序展开；脚注保留为小号正文，不推断脚注引用。字体近似，浮动复杂对象请对照原页。图片裁片包含该区域实际合成外观。",
                )
                .put("pages", pages)
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                            val body = StringBuilder()
                            val relations = StringBuilder()
                            var imageId = 0
                            repeat(pdf.numberOfPages) { index ->
                                currentCoroutineContext().ensureActive()
                                val page = pdf.getPage(index)
                                val rotated = (page.rotation % 180 + 180) % 180 != 0
                                val width =
                                    (if (rotated) page.cropBox.height else page.cropBox.width) *
                                        page.userUnit
                                val height =
                                    (if (rotated) page.cropBox.width else page.cropBox.height) *
                                        page.userUnit
                                val bitmap =
                                    renderer.openPage(index).use { rendered ->
                                        val scale = 2200f / maxOf(rendered.width, rendered.height)
                                        Bitmap.createBitmap(
                                                (rendered.width * scale)
                                                    .roundToInt()
                                                    .coerceAtLeast(1),
                                                (rendered.height * scale)
                                                    .roundToInt()
                                                    .coerceAtLeast(1),
                                                Bitmap.Config.ARGB_8888,
                                            )
                                            .also {
                                                it.eraseColor(Color.WHITE)
                                                rendered.render(
                                                    it,
                                                    null,
                                                    null,
                                                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                                )
                                            }
                                    }
                                val details =
                                    JSONObject()
                                        .put("page", index + 1)
                                        .put("widthPt", width)
                                        .put("heightPt", height)
                                        .put(
                                            "mode",
                                            if (appearance) "page-appearance"
                                            else "editable-reading-order",
                                        )
                                fun picture(
                                    pixels: Bitmap,
                                    w: Float,
                                    h: Float,
                                    full: Boolean,
                                ): String {
                                    val id = ++imageId
                                    zip.putNextEntry(ZipEntry("word/media/image$id.png"))
                                    check(pixels.compress(Bitmap.CompressFormat.PNG, 100, zip))
                                    zip.closeEntry()
                                    relations.append(
                                        "<Relationship Id=\"image$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$id.png\"/>"
                                    )
                                    return drawing(
                                        id,
                                        (w * 12700).roundToLong().coerceAtLeast(1),
                                        (h * 12700).roundToLong().coerceAtLeast(1),
                                        full,
                                    )
                                }
                                try {
                                    if (appearance) {
                                        body.append(picture(bitmap, width, height, true))
                                        details.put("pictures", 1).put("editableText", false)
                                    } else {
                                        var tokens =
                                            PhoneTables.nativeTokens(pdf, index).filter { token ->
                                                token.text.none {
                                                    it == '\uFFFD' ||
                                                        Character.getType(it) ==
                                                            Character.PRIVATE_USE.toInt()
                                                }
                                            }
                                        val decision = NativeTextCoverage.assess(pdf, index)
                                        val scanned = decision.needsOcr
                                        if (scanned)
                                            tokens =
                                                tokens +
                                                    PhoneTables.ocrTokens(
                                                        NativeTextCoverage.removeCovered(
                                                            ocr.recognizeBitmap(bitmap),
                                                            decision.regions,
                                                        )
                                                    )
                                        // Aligned article columns are not evidence of a table.
                                        // Borderless tables remain in the explicit table workspace.
                                        val tables =
                                            PhoneTables.detect(
                                                bitmap,
                                                index + 1,
                                                tokens,
                                                "lines_strict",
                                            )
                                        val boxes =
                                            tables.map { table ->
                                                RectF(
                                                    table.cells.minOf { it.box.left },
                                                    table.cells.minOf { it.box.top },
                                                    table.cells.maxOf { it.box.right },
                                                    table.cells.maxOf { it.box.bottom },
                                                )
                                            }
                                        val outside =
                                            tokens.filter { token ->
                                                boxes.none {
                                                    it.contains(
                                                        token.box.centerX(),
                                                        token.box.centerY(),
                                                    )
                                                }
                                            }
                                        val median =
                                            outside
                                                .map { it.fontSize }
                                                .sorted()
                                                .let { if (it.isEmpty()) 11f else it[it.size / 2] }
                                        val blocks =
                                            lines(outside)
                                                .map { paragraph(it, median, width) }
                                                .toMutableList()
                                        tables.forEachIndexed { i, table ->
                                            blocks +=
                                                Block(
                                                    boxes[i],
                                                    PhoneDocx.tableXml(table, width),
                                                    "table",
                                                )
                                        }
                                        val regions = ImageRegions(page)
                                        val parsed =
                                            runCatching { regions.processPage(page) }.isSuccess
                                        regions.images
                                            .distinctBy {
                                                listOf(
                                                    (it.left * 1000).roundToInt(),
                                                    (it.top * 1000).roundToInt(),
                                                    (it.right * 1000).roundToInt(),
                                                    (it.bottom * 1000).roundToInt(),
                                                )
                                            }
                                            .forEach { box ->
                                                val l =
                                                    (box.left * bitmap.width)
                                                        .roundToInt()
                                                        .coerceIn(0, bitmap.width - 1)
                                                val t =
                                                    (box.top * bitmap.height)
                                                        .roundToInt()
                                                        .coerceIn(0, bitmap.height - 1)
                                                val r =
                                                    (box.right * bitmap.width)
                                                        .roundToInt()
                                                        .coerceIn(l + 1, bitmap.width)
                                                val b =
                                                    (box.bottom * bitmap.height)
                                                        .roundToInt()
                                                        .coerceIn(t + 1, bitmap.height)
                                                val crop =
                                                    Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
                                                try {
                                                    val scale =
                                                        minOf(
                                                                1f,
                                                                (width - 60) /
                                                                    (box.width() * width),
                                                                (height - 80) /
                                                                    (box.height() * height),
                                                            )
                                                            .coerceAtLeast(.01f)
                                                    blocks +=
                                                        Block(
                                                            box,
                                                            picture(
                                                                crop,
                                                                box.width() * width * scale,
                                                                box.height() * height * scale,
                                                                false,
                                                            ),
                                                            "picture",
                                                        )
                                                } finally {
                                                    if (crop !== bitmap) crop.recycle()
                                                }
                                            }
                                        val (ordered, columns) = ordered(blocks)
                                        ordered.forEach { body.append(it.xml) }
                                        details
                                            .put("ocr", scanned)
                                            .put("twoColumnReadingOrder", columns)
                                            .put("headings", blocks.count { it.kind == "heading" })
                                            .put(
                                                "paragraphs",
                                                blocks.count { it.kind == "paragraph" },
                                            )
                                            .put(
                                                "footnoteTextBlocks",
                                                blocks.count { it.kind == "footnote-text" },
                                            )
                                            .put("tables", tables.size)
                                            .put("pictures", blocks.count { it.kind == "picture" })
                                            .put("imageStreamParsed", parsed)
                                    }
                                    val sect = section(width, height, appearance)
                                    if (index == pdf.numberOfPages - 1) body.append(sect)
                                    else
                                        body.append(
                                            "<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>$sect</w:pPr></w:p>"
                                        )
                                    pages.put(details)
                                } finally {
                                    bitmap.recycle()
                                }
                                progress((index + 1) * 100 / pdf.numberOfPages)
                            }
                            fun entry(path: String, data: String) =
                                SlidesPackage.text(zip, path, data)
                            entry(
                                "[Content_Types].xml",
                                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="json" ContentType="application/json"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
                            )
                            entry(
                                "_rels/.rels",
                                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                            )
                            entry(
                                "word/_rels/document.xml.rels",
                                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relations<Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                            )
                            entry(
                                "word/styles.xml",
                                """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Liberation Sans" w:eastAsia="Noto Sans CJK SC"/><w:sz w:val="22"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="1"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="FootnoteText"><w:name w:val="Footnote Text"/><w:basedOn w:val="Normal"/></w:style></w:styles>""",
                            )
                            entry(
                                "word/document.xml",
                                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body</w:body></w:document>""",
                            )
                            entry("paperforge-conversion.json", report.toString(2))
                        }
                    }
                }
            }
            check(temporary.renameTo(output))
            return report
        } finally {
            temporary.delete()
        }
    }
}
