package cn.paperforge.app.pdf

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.ocr.OcrEngine
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Rebuild editable text, table cells and embedded images, with original page sizes. */
object PhoneDocx {
    private fun xml(text: String) =
        text
            .filter { it >= ' ' || it in "\n\r\t" }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun ZipOutputStream.entry(path: String, data: String) {
        putNextEntry(ZipEntry(path))
        write(data.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private data class Block(val top: Float, val bottom: Float, val xml: String)

    private fun tableXml(table: PhoneTables.Table, width: Float): String = buildString {
        val columns = table.rows.first().size
        val left = table.cells.minOf { it.box.left }
        val right = table.cells.maxOf { it.box.right }
        val total = ((right - left) * width * 20).toInt()
        append(
            "<w:tbl><w:tblPr><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblInd w:w=\"${(left*width*20-600).toInt().coerceAtLeast(0)}\" w:type=\"dxa\"/><w:tblBorders>"
        )
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
            append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"777777\"/>")
        }
        append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(columns) { append("<w:gridCol w:w=\"${total/columns}\"/>") }
        append("</w:tblGrid>")
        table.rows.forEachIndexed { row, values ->
            append("<w:tr>")
            var column = 0
            while (column < columns) {
                val owner =
                    table.cells.firstOrNull {
                        row in it.row..it.rowEnd && column in it.column..it.columnEnd
                    }
                if (owner != null && column != owner.column) {
                    column++
                    continue
                }
                val span = if (owner != null) owner.columnEnd - owner.column + 1 else 1
                append("<w:tc><w:tcPr><w:tcW w:w=\"${total*span/columns}\" w:type=\"dxa\"/>")
                if (span > 1) append("<w:gridSpan w:val=\"$span\"/>")
                if (owner != null && owner.rowEnd > owner.row)
                    append(if (row == owner.row) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                append(
                    "</w:tcPr><w:p><w:r><w:rPr><w:rFonts w:ascii=\"Carlito\" w:eastAsia=\"Noto Sans CJK SC\"/><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">"
                )
                append(xml(if (owner != null && row > owner.row) "" else values[column]))
                append("</w:t></w:r></w:p></w:tc>")
                column += span
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
    }

    suspend fun convert(
        input: File,
        output: File,
        ocr: OcrEngine,
        progress: suspend (Int) -> Unit = {},
    ) {
        val temporary = File(output.path + ".part")
        try {
            PDDocument.load(input).use { pdf ->
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                            val body = StringBuilder()
                            val relations = StringBuilder()
                            var imageId = 0
                            repeat(pdf.numberOfPages) { index ->
                                currentCoroutineContext().ensureActive()
                                if (index > 0)
                                    body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                                val page = pdf.getPage(index)
                                val pageWidth = page.cropBox.width
                                val pageHeight = page.cropBox.height
                                val bitmap =
                                    renderer.openPage(index).use { rendered ->
                                        val scale = 2200f / maxOf(rendered.width, rendered.height)
                                        Bitmap.createBitmap(
                                                (rendered.width * scale).toInt(),
                                                (rendered.height * scale).toInt(),
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
                                try {
                                    var tokens = PhoneTables.nativeTokens(pdf, index)
                                    val scanned = tokens.sumOf { it.text.length } < 5
                                    if (scanned)
                                        tokens = PhoneTables.ocrTokens(ocr.recognizeBitmap(bitmap))
                                    val tables = PhoneTables.detect(bitmap, index + 1, tokens)
                                    val tableBounds =
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
                                            tableBounds.none {
                                                it.contains(
                                                    token.box.centerX(),
                                                    token.box.centerY(),
                                                )
                                            }
                                        }
                                    val lines = mutableListOf<MutableList<PhoneTables.Token>>()
                                    outside
                                        .sortedBy { it.box.centerY() }
                                        .forEach { token ->
                                            val line = lines.lastOrNull()
                                            if (
                                                line == null ||
                                                    abs(
                                                        line.map { it.box.centerY() }.average() -
                                                            token.box.centerY()
                                                    ) > maxOf(.004f, token.box.height() * .55f)
                                            )
                                                lines += mutableListOf(token)
                                            else line += token
                                        }
                                    val blocks = mutableListOf<Block>()
                                    lines.forEach { line ->
                                        val ordered = line.sortedBy { it.box.left }
                                        val size =
                                            (ordered.map { it.fontSize }.average() * 2)
                                                .toInt()
                                                .coerceIn(12, 96)
                                        val text = PhoneTables.joinTokens(ordered)
                                        blocks +=
                                            Block(
                                                line.minOf { it.box.top },
                                                line.maxOf { it.box.bottom },
                                                "<w:p><w:pPr><w:spacing w:after=\"0\"/><w:ind w:left=\"${(line.minOf { it.box.left }*pageWidth*20-600).toInt().coerceAtLeast(0)}\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Carlito\" w:eastAsia=\"Noto Sans CJK SC\"/><w:sz w:val=\"$size\"/>${if(line.any { it.bold }) "<w:b/>" else ""}${if(line.any { it.italic }) "<w:i/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>",
                                            )
                                    }
                                    tables.forEach { table ->
                                        blocks +=
                                            Block(
                                                table.cells.minOf { it.box.top },
                                                table.cells.maxOf { it.box.bottom },
                                                tableXml(table, pageWidth),
                                            )
                                    }
                                    // Image transforms come from the real PDF graphics state; full
                                    // scan backgrounds are omitted after OCR.
                                    var point = PointF()
                                    val engine =
                                        object : PDFGraphicsStreamEngine(page) {
                                            override fun drawImage(image: PDImage) {
                                                val matrix =
                                                    graphicsState.currentTransformationMatrix
                                                val points =
                                                    listOf(
                                                        matrix.transformPoint(0f, 0f),
                                                        matrix.transformPoint(1f, 0f),
                                                        matrix.transformPoint(0f, 1f),
                                                        matrix.transformPoint(1f, 1f),
                                                    )
                                                val left =
                                                    (points.minOf { it.x } -
                                                        page.cropBox.lowerLeftX) / pageWidth
                                                val right =
                                                    (points.maxOf { it.x } -
                                                        page.cropBox.lowerLeftX) / pageWidth
                                                val top =
                                                    1 -
                                                        (points.maxOf { it.y } -
                                                            page.cropBox.lowerLeftY) / pageHeight
                                                val bottom =
                                                    1 -
                                                        (points.minOf { it.y } -
                                                            page.cropBox.lowerLeftY) / pageHeight
                                                if ((right - left) * (bottom - top) > .8f) return
                                                val picture =
                                                    runCatching {
                                                            image.getImage(
                                                                null,
                                                                maxOf(
                                                                    1,
                                                                    maxOf(
                                                                        image.width,
                                                                        image.height,
                                                                    ) / 2600,
                                                                ),
                                                            )
                                                        }
                                                        .getOrNull() ?: return
                                                val id = ++imageId
                                                val cx =
                                                    ((right - left) * pageWidth * 12700)
                                                        .toLong()
                                                        .coerceAtLeast(1)
                                                val cy =
                                                    ((bottom - top) * pageHeight * 12700)
                                                        .toLong()
                                                        .coerceAtLeast(1)
                                                zip.putNextEntry(
                                                    ZipEntry("word/media/image$id.png")
                                                )
                                                picture.compress(
                                                    Bitmap.CompressFormat.PNG,
                                                    100,
                                                    zip,
                                                )
                                                zip.closeEntry()
                                                relations.append(
                                                    "<Relationship Id=\"image$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$id.png\"/>"
                                                )
                                                blocks +=
                                                    Block(
                                                        top,
                                                        bottom,
                                                        """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Image $id"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="image$id.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="image$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
                                                    )
                                            }

                                            override fun appendRectangle(
                                                a: PointF,
                                                b: PointF,
                                                c: PointF,
                                                d: PointF,
                                            ) {
                                                point = a
                                            }

                                            override fun clip(rule: Path.FillType) {}

                                            override fun moveTo(x: Float, y: Float) {
                                                point = PointF(x, y)
                                            }

                                            override fun lineTo(x: Float, y: Float) {
                                                point = PointF(x, y)
                                            }

                                            override fun curveTo(
                                                a: Float,
                                                b: Float,
                                                c: Float,
                                                d: Float,
                                                e: Float,
                                                f: Float,
                                            ) {
                                                point = PointF(e, f)
                                            }

                                            override fun getCurrentPoint(): PointF = point

                                            override fun closePath() {}

                                            override fun endPath() {}

                                            override fun strokePath() {}

                                            override fun fillPath(rule: Path.FillType) {}

                                            override fun fillAndStrokePath(rule: Path.FillType) {}

                                            override fun shadingFill(name: COSName) {}
                                        }
                                    runCatching { engine.processPage(page) }
                                    blocks.sortedBy { it.top }.forEach { body.append(it.xml) }
                                } finally {
                                    bitmap.recycle()
                                }
                                progress((index + 1) * 100 / pdf.numberOfPages)
                            }
                            zip.entry(
                                "[Content_Types].xml",
                                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                            )
                            zip.entry(
                                "_rels/.rels",
                                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                            )
                            zip.entry(
                                "word/_rels/document.xml.rels",
                                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relations</Relationships>""",
                            )
                            val size = pdf.getPage(0).cropBox
                            zip.entry(
                                "word/document.xml",
                                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="${(size.width*20).toInt()}" w:h="${(size.height*20).toInt()}"/><w:pgMar w:top="600" w:right="600" w:bottom="600" w:left="600"/></w:sectPr></w:body></w:document>""",
                            )
                        }
                    }
                }
            }
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
    }
}
