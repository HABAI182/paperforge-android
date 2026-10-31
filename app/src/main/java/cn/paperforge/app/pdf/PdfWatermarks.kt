package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Generic watermark handling for PDFs shared by third-party scan apps (#27, #28).
 *
 * Two independent paths, both deliberately conservative:
 * - image stains: a small image whose bytes recur across pages (or, for a single-page file, a small
 *   image sitting next to a corner text stain) is treated as a logo watermark and its `Do` operator
 *   is stripped, so nothing is drawn at all;
 * - text stains: a short text fragment sitting in a page corner that either repeats on every page
 *   or is the only text on a scanned page is covered with the paper margin colour.
 *
 * The original file is never touched and the text layer is not rewritten: the covered text remains
 * extractable, which the acceptance note states plainly.
 */
object PdfWatermarks {
    data class ImageMark(
        val page: Int,
        val name: COSName,
        val width: Int,
        val height: Int,
        val hash: String,
    )

    data class TextMark(
        val page: Int,
        val text: String,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
    )

    data class Scan(val images: List<ImageMark>, val texts: List<TextMark>) {
        val found: Boolean
            get() = images.isNotEmpty() || texts.isNotEmpty()
    }

    data class Report(val imagesRemoved: Int, val textCovers: Int, val pages: Int)

    private const val SMALL_IMAGE = 640
    private const val CORNER_BAND = .22f

    fun scan(pdf: PDDocument): Scan {
        val pages = pdf.numberOfPages
        val images = mutableListOf<ImageMark>()
        val byHash = mutableMapOf<String, MutableSet<Int>>()
        for (p in 0 until pages) {
            val resources = pdf.getPage(p).resources ?: continue
            for (name in resources.xObjectNames.toList()) {
                val image = runCatching { resources.getXObject(name) }.getOrNull()
                if (image !is PDImageXObject) continue
                val hash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            image.cosObject.createRawInputStream().use { it.readBytes() }
                        )
                        .joinToString("") { "%02x".format(it) }
                byHash.getOrPut(hash) { mutableSetOf() }.add(p)
                if (image.width <= SMALL_IMAGE && image.height <= SMALL_IMAGE)
                    images.add(ImageMark(p, name, image.width, image.height, hash))
            }
        }
        val texts = mutableListOf<TextMark>()
        val chunks = mutableListOf<Pair<Int, TextMark>>()
        for (p in 0 until pages) {
            val page = pdf.getPage(p)
            val height = page.mediaBox.height
            val collector = ChunkCollector()
            collector.startPage = p + 1
            collector.endPage = p + 1
            runCatching { collector.getText(pdf) }
            val pageChunks = collector.chunks.mapNotNull { it.toMark(p, height, page.mediaBox.width) }
            chunks.addAll(pageChunks.map { p to it })
        }
        val repeats =
            chunks.groupBy { it.second.text.trim() }.filterValues { it.map { c -> c.first }.toSet().size >= pages && pages > 1 }
        val pagesWithText = chunks.map { it.first }.toSet()
        for ((page, mark) in chunks) {
            val repeated = repeats.containsKey(mark.text.trim())
            val onlyTextOnPage = chunks.count { it.first == page } <= 2
            if (repeated || onlyTextOnPage) texts.add(mark)
        }
        val textPages = texts.map { it.page }.toSet()
        val repeatedHashes = byHash.filterValues { it.size >= 2 }.keys
        val keptImages =
            images.filter { mark ->
                mark.hash in repeatedHashes ||
                    (pages == 1 && mark.page in textPages) ||
                    (pages > 1 && textPages.isNotEmpty() && mark.page in textPages)
            }
        return Scan(keptImages, texts)
    }

    /** Writes a cleaned copy; the source file is only read. */
    fun remove(input: File, output: File, paint: FloatArray = floatArrayOf(1f, 1f, 1f)): Report {
        require(input.canonicalFile != output.canonicalFile) { "输出必须是新文件" }
        val part = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val scan = scan(pdf)
                var stripped = 0
                if (scan.images.isNotEmpty()) {
                    val byPage = scan.images.groupBy { it.page }
                    for ((page, marks) in byPage) {
                        val names = marks.map { it.name }.toSet()
                        val target = pdf.getPage(page)
                        val parser = PDFStreamParser(target)
                        parser.parse()
                        val tokens = parser.tokens.toMutableList()
                        val kept = ArrayList<Any>(tokens.size)
                        var index = 0
                        while (index < tokens.size) {
                            val token = tokens[index]
                            if (
                                token is COSName &&
                                    names.contains(token) &&
                                    index + 1 < tokens.size &&
                                    (tokens[index + 1] as? Operator)?.name == "Do"
                            ) {
                                stripped++
                                index += 2
                                continue
                            }
                            kept.add(token)
                            index++
                        }
                        if (kept.size != tokens.size) {
                            val stream = PDStream(pdf)
                            stream.createOutputStream(COSName.FLATE_DECODE).use {
                                ContentStreamWriter(it).writeTokens(kept)
                            }
                            target.setContents(stream)
                            val resources = target.resources ?: continue
                            for (name in names) resources.cosObject.removeItem(name)
                        }
                    }
                }
                for (mark in scan.texts) {
                    val page = pdf.getPage(mark.page)
                    PDPageContentStream(
                            pdf,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true,
                        )
                        .use { content ->
                            content.setNonStrokingColor(paint[0], paint[1], paint[2])
                            val pad = 2f
                            content.addRect(
                                mark.left - pad,
                                mark.bottom - pad,
                                max(1f, mark.right - mark.left + pad * 2),
                                max(1f, mark.top - mark.bottom + pad * 2),
                            )
                            content.fill()
                        }
                }
                pdf.save(part)
                val report = Report(stripped, scan.texts.size, pdf.numberOfPages)
                check(part.renameTo(output)) { "无法保存副本" }
                return report
            }
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private class ChunkCollector : PDFTextStripper() {
        val chunks = mutableListOf<Chunk>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val positions = textPositions.filter { !it.unicode.isNullOrBlank() }
            if (positions.isEmpty() || text.isBlank()) return
            val left = positions.minOf { it.xDirAdj }
            val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }
            // yDirAdj is the baseline measured downwards from the top edge; the glyph box grows
            // *upwards* from it by heightDir, which is why the cover must extend by that much.
            val baseline = positions.maxOf { it.yDirAdj }
            val glyph = positions.maxOf { it.heightDir }
            chunks.add(Chunk(text.trim(), left, right, baseline, glyph))
        }
    }

    private data class Chunk(
        val text: String,
        val left: Float,
        val right: Float,
        val baseline: Float,
        val glyph: Float,
    ) {
        fun toMark(page: Int, height: Float, width: Float): TextMark? {
            if (baseline !in 0f..height) return null
            val bottom = (height - baseline).coerceIn(0f, height)
            // 1.3x + 2pt keeps ascenders and descenders out of the residual sliver.
            val top = (bottom + glyph * 1.3f + 2f).coerceIn(bottom, height)
            val nearBottom = bottom <= height * .06f
            val nearTop = top >= height * .94f
            val rightSide = left >= width * .4f
            if ((!nearBottom && !nearTop) || !rightSide) return null
            return TextMark(page, text, left, bottom, right, top)
        }
    }
}
