package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Editable text paragraphs; optional page reference image is explicitly a separate DOCX mode. */
object PdfTextExport {
    private fun xml(text: String) =
        text
            .filter { it == '\t' || it == '\n' || it.code >= 32 }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun paragraphs(doc: PDDocument, page: Int): List<String> {
        val text =
            PDFTextStripper()
                .apply {
                    sortByPosition = true
                    startPage = page + 1
                    endPage = page + 1
                    paragraphStart = ""
                    paragraphEnd = "\n\n"
                    pageStart = ""
                    pageEnd = ""
                }
                .getText(doc)
        return text
            .split(Regex("\\n\\s*\\n"))
            .map { paragraph ->
                val lines = paragraph.trim().lines().map { it.trimEnd() }
                buildString {
                    lines.forEach { line ->
                        val next = line.trimStart()
                        if (
                            isNotEmpty() &&
                                next.isNotEmpty() &&
                                last().code < 128 &&
                                next.first().code < 128 &&
                                !last().isWhitespace()
                        )
                            append(' ')
                        append(next)
                    }
                }
            }
            .filter { it.isNotBlank() }
    }

    suspend fun export(input: File, output: File, kind: String) {
        val temporary = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val pages =
                    (0 until doc.numberOfPages).map { page ->
                        coroutineContext.ensureActive()
                        paragraphs(doc, page)
                    }
                require(pages.any { it.isNotEmpty() }) { "文档没有文字层，请先生成可搜索 PDF" }
                if (kind in listOf("docx", "docx-image")) {
                    ZipOutputStream(temporary.outputStream()).use { zip ->
                        fun entry(name: String, body: String) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(body.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                        val body = StringBuilder()
                        val relationships = StringBuilder()
                        val descriptor =
                            if (kind == "docx-image")
                                ParcelFileDescriptor.open(
                                    input,
                                    ParcelFileDescriptor.MODE_READ_ONLY,
                                )
                            else null
                        try {
                            val renderer = descriptor?.let { PdfRenderer(it) }
                            try {
                                pages.forEachIndexed { index, paragraphs ->
                                    coroutineContext.ensureActive()
                                    if (index > 0)
                                        body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                                    if (renderer != null)
                                        renderer.openPage(index).use { page ->
                                            val ratio = 1100f / maxOf(page.width, page.height)
                                            val bitmap =
                                                Bitmap.createBitmap(
                                                    (page.width * ratio).toInt().coerceAtLeast(1),
                                                    (page.height * ratio).toInt().coerceAtLeast(1),
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
                                                zip.putNextEntry(
                                                    ZipEntry("word/media/page-${index+1}.png")
                                                )
                                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                                                zip.closeEntry()
                                                val width =
                                                    minOf(
                                                        5_303_520L,
                                                        6_400_800L * bitmap.width / bitmap.height,
                                                    )
                                                val height = width * bitmap.height / bitmap.width
                                                val id = index + 1
                                                relationships.append(
                                                    "<Relationship Id=\"image$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page-$id.png\"/>"
                                                )
                                                body.append(
                                                    """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$width" cy="$height"/><wp:docPr id="$id" name="Original page $id"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="page-$id.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="image$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
                                                )
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        }
                                    paragraphs.forEach { paragraph ->
                                        body.append(
                                            "<w:p><w:r><w:t xml:space=\"preserve\">${xml(paragraph)}</w:t></w:r></w:p>"
                                        )
                                    }
                                }
                            } finally {
                                renderer?.close()
                            }
                        } finally {
                            descriptor?.close()
                        }
                        entry(
                            "[Content_Types].xml",
                            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                        )
                        entry(
                            "_rels/.rels",
                            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                        )
                        entry(
                            "word/_rels/document.xml.rels",
                            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships</Relationships>""",
                        )
                        entry(
                            "word/document.xml",
                            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1000" w:right="1000" w:bottom="1000" w:left="1000"/></w:sectPr></w:body></w:document>""",
                        )
                    }
                } else {
                    val text = pages.joinToString("\n\n\u000c\n\n") { it.joinToString("\n\n") }
                    val result =
                        if (kind == "html")
                            "<!doctype html><meta charset=\"utf-8\">" +
                                pages
                                    .mapIndexed { i, paragraphs ->
                                        "<section aria-label=\"Page ${i+1}\">" +
                                            paragraphs.joinToString("") { "<p>${xml(it)}</p>" } +
                                            "</section>"
                                    }
                                    .joinToString("")
                        else text
                    temporary.writeText(result, Charsets.UTF_8)
                }
            }
            coroutineContext.ensureActive()
            check(temporary.renameTo(output)) { "文字文件保存失败" }
        } finally {
            temporary.delete()
        }
    }
}
