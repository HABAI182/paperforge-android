package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionNamed
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

data class ReaderOutline(
    val title: String,
    val page: Int,
    val depth: Int,
    val destination: ReaderDestination = ReaderDestination(page),
)

data class ReaderLink(
    val page: Int?,
    val uri: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val destination: ReaderDestination? = null,
)

data class ReaderInfo(
    val outline: List<ReaderOutline> = emptyList(),
    val labels: List<String> = emptyList(),
)

data class ReaderTextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val source: String = "native",
    val units: List<ReaderTextUnit> = emptyList(),
)

/** Read-only PDF navigation and text geometry. No rasterization for native text. */
object ReaderDocument {
    fun info(file: File): ReaderInfo =
        PDDocument.load(file).use { doc ->
            val result = mutableListOf<ReaderOutline>()
            val visited = mutableSetOf<com.tom_roush.pdfbox.cos.COSDictionary>()
            fun visit(start: PDOutlineItem?, depth: Int) {
                if (depth > 32) return
                var item = start
                while (item != null && result.size < 10000 && visited.add(item.cosObject)) {
                    val entry = item
                    val destination =
                        runCatching {
                                readerDestination(
                                    doc,
                                    (entry.action as? PDActionGoTo)?.destination
                                        ?: entry.destination,
                                )
                            }
                            .getOrNull()
                    if (destination != null)
                        result.add(
                            ReaderOutline(
                                entry.title ?: "未命名章节",
                                destination.page,
                                depth,
                                destination,
                            )
                        )
                    visit(entry.firstChild, depth + 1)
                    item = entry.nextSibling
                }
            }
            visit(doc.documentCatalog.documentOutline?.firstChild, 0)
            ReaderInfo(
                result,
                doc.documentCatalog.pageLabels?.labelsByPageIndices?.toList().orEmpty(),
            )
        }

    fun links(file: File, index: Int): List<ReaderLink> =
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(index)
            val crop = page.cropBox
            page.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
                val action = link.action
                val namedPage =
                    when ((action as? PDActionNamed)?.n) {
                        "FirstPage" -> 0
                        "LastPage" -> doc.numberOfPages - 1
                        "NextPage" -> (index + 1).coerceAtMost(doc.numberOfPages - 1)
                        "PrevPage" -> (index - 1).coerceAtLeast(0)
                        else -> null
                    }
                val destination =
                    readerDestination(
                        doc,
                        (action as? PDActionGoTo)?.destination ?: link.destination,
                    ) ?: namedPage?.let { ReaderDestination(it) }
                val internal = destination?.page
                val uri = (action as? PDActionURI)?.uri
                if (internal == null && uri == null) return@mapNotNull null
                val r = link.rectangle
                val points =
                    listOf(
                        display(
                            PointN(
                                (r.lowerLeftX - crop.lowerLeftX) / crop.width,
                                1 - (r.upperRightY - crop.lowerLeftY) / crop.height,
                            ),
                            page.rotation,
                        ),
                        display(
                            PointN(
                                (r.upperRightX - crop.lowerLeftX) / crop.width,
                                1 - (r.lowerLeftY - crop.lowerLeftY) / crop.height,
                            ),
                            page.rotation,
                        ),
                    )
                ReaderLink(
                    internal,
                    uri,
                    points.minOf { it.x },
                    points.minOf { it.y },
                    points.maxOf { it.x },
                    points.maxOf { it.y },
                    destination,
                )
            }
        }

    fun text(file: File, index: Int): List<ReaderTextBlock> =
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(index)
            val box = page.cropBox
            val blocks = mutableListOf<ReaderTextBlock>()
            val stripper =
                object : ReadablePdfTextStripper(doc) {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        if (positions.any { !NativeTextCoverage.valid(it) }) return
                        val groups = mutableListOf<MutableList<TextPosition>>()
                        positions.forEach { p ->
                            val group = groups.lastOrNull()
                            val prior = group?.lastOrNull()
                            if (
                                prior == null ||
                                    p.xDirAdj - prior.xDirAdj - prior.widthDirAdj >
                                        maxOf(box.width * .035f, p.heightDir * 1.4f)
                            )
                                groups.add(mutableListOf(p))
                            else checkNotNull(group).add(p)
                        }
                        groups.forEach { group ->
                            val line =
                                buildString {
                                        group.forEachIndexed { i, p ->
                                            if (i > 0) {
                                                val prior = group[i - 1]
                                                if (
                                                    p.xDirAdj - prior.xDirAdj - prior.widthDirAdj >
                                                        p.heightDir * .18f &&
                                                        isNotEmpty() &&
                                                        !last().isWhitespace()
                                                )
                                                    append(' ')
                                            }
                                            append(p.unicode)
                                        }
                                    }
                                    .trim()
                            if (line.isNotEmpty()) {
                                fun bounds(p: TextPosition) =
                                    PdfGlyphGeometry.displayed(p, box, page.rotation)
                                val points = group.flatMap(::bounds)
                                var cursor = 0
                                val units =
                                    group.mapNotNull { p ->
                                        val start = line.indexOf(p.unicode, cursor)
                                        if (p.unicode.isNullOrEmpty() || start < 0) null
                                        else {
                                            cursor = start + p.unicode.length
                                            val corners = bounds(p)
                                            ReaderTextUnit(
                                                start,
                                                cursor,
                                                corners.minOf { it.x }.coerceIn(0f, 1f),
                                                corners.minOf { it.y }.coerceIn(0f, 1f),
                                                corners.maxOf { it.x }.coerceIn(0f, 1f),
                                                corners.maxOf { it.y }.coerceIn(0f, 1f),
                                            )
                                        }
                                    }
                                blocks.add(
                                    ReaderTextBlock(
                                        line,
                                        points.minOf { it.x }.coerceIn(0f, 1f),
                                        points.minOf { it.y }.coerceIn(0f, 1f),
                                        points.maxOf { it.x }.coerceIn(0f, 1f),
                                        points.maxOf { it.y }.coerceIn(0f, 1f),
                                        units = units,
                                    )
                                )
                            }
                        }
                    }
                }
            stripper.sortByPosition = true
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            blocks
        }

    fun order(blocks: List<ReaderTextBlock>, columns: Int): List<ReaderTextBlock> {
        val order =
            when (columns) {
                1 -> cn.paperforge.app.ocr.OcrOrder.COLUMNS_LTR
                2 -> cn.paperforge.app.ocr.OcrOrder.COLUMNS_RTL
                3 -> cn.paperforge.app.ocr.OcrOrder.VERTICAL_RTL
                4 -> cn.paperforge.app.ocr.OcrOrder.VERTICAL_LTR
                5 -> cn.paperforge.app.ocr.OcrOrder.ROWS
                else -> cn.paperforge.app.ocr.OcrOrder.AUTO
            }
        // Only reuse the geometry ordering; original character offsets and source boxes survive.
        val layout =
            cn.paperforge.app.model.OcrLayout(
                1,
                1,
                blocks.mapIndexed { index, b ->
                    cn.paperforge.app.model.OcrLine(
                        b.text,
                        listOf(
                            PointN(b.left, b.top),
                            PointN(b.right, b.top),
                            PointN(b.right, b.bottom),
                            PointN(b.left, b.bottom),
                        ),
                        block = index,
                    )
                },
            )
        return cn.paperforge.app.ocr.OcrReadingOrder.arrange(layout, order).lines.map {
            blocks[it.block]
        }
    }

    private fun display(p: PointN, rotation: Int) =
        when ((rotation % 360 + 360) % 360) {
            90 -> PointN(1 - p.y, p.x)
            180 -> PointN(1 - p.x, 1 - p.y)
            270 -> PointN(p.y, 1 - p.x)
            else -> p
        }
}
