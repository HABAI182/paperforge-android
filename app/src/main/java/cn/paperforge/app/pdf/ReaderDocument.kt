package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

data class ReaderOutline(val title: String, val page: Int, val depth: Int)

data class ReaderLink(
    val page: Int?,
    val uri: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
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
                    runCatching { entry.findDestinationPage(doc) }
                        .getOrNull()
                        ?.let { target ->
                            val page = doc.pages.indexOf(target)
                            if (page >= 0)
                                result.add(ReaderOutline(entry.title ?: "未命名章节", page, depth))
                        }
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
            fun target(destination: PDDestination?): Int? {
                val actual =
                    when (destination) {
                        is PDNamedDestination ->
                            doc.documentCatalog.findNamedDestinationPage(destination)
                        is PDPageDestination -> destination
                        else -> null
                    } ?: return null
                return actual.page?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 }
                    ?: actual.pageNumber.takeIf { it in 0 until doc.numberOfPages }
            }
            page.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
                val action = link.action
                val internal = target((action as? PDActionGoTo)?.destination ?: link.destination)
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
                )
            }
        }

    fun text(file: File, index: Int): List<ReaderTextBlock> =
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(index)
            val box = page.cropBox
            val blocks = mutableListOf<ReaderTextBlock>()
            val stripper =
                object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
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
                                val points =
                                    group.flatMap { p ->
                                        listOf(
                                            display(
                                                PointN(
                                                    p.xDirAdj / box.width,
                                                    (p.yDirAdj - p.heightDir) / box.height,
                                                ),
                                                page.rotation,
                                            ),
                                            display(
                                                PointN(
                                                    (p.xDirAdj + p.widthDirAdj) / box.width,
                                                    p.yDirAdj / box.height,
                                                ),
                                                page.rotation,
                                            ),
                                        )
                                    }
                                blocks.add(
                                    ReaderTextBlock(
                                        line,
                                        points.minOf { it.x }.coerceIn(0f, 1f),
                                        points.minOf { it.y }.coerceIn(0f, 1f),
                                        points.maxOf { it.x }.coerceIn(0f, 1f),
                                        points.maxOf { it.y }.coerceIn(0f, 1f),
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

    fun order(blocks: List<ReaderTextBlock>, columns: Int): List<ReaderTextBlock> =
        when (columns) {
            1 ->
                blocks.sortedWith(
                    compareBy<ReaderTextBlock> {
                            if (it.right - it.left > .65f) -1 else if (it.left < .5f) 0 else 1
                        }
                        .thenBy { it.top }
                )
            2 ->
                blocks.sortedWith(
                    compareBy<ReaderTextBlock> {
                            if (it.right - it.left > .65f) -1 else if (it.left >= .5f) 0 else 1
                        }
                        .thenBy { it.top }
                )
            else ->
                blocks.sortedWith(
                    compareBy<ReaderTextBlock> { (it.top * 200).toInt() }.thenBy { it.left }
                )
        }

    private fun display(p: PointN, rotation: Int) =
        when ((rotation % 360 + 360) % 360) {
            90 -> PointN(1 - p.y, p.x)
            180 -> PointN(1 - p.x, 1 - p.y)
            270 -> PointN(p.y, 1 - p.x)
            else -> p
        }
}
