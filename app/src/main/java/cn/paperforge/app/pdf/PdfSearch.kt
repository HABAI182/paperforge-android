package cn.paperforge.app.pdf

import cn.paperforge.app.model.CjkSearch
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer
import java.util.Locale

object PdfSearch {
    data class Hit(
        val page: Int,
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    fun find(file: File, query: String): List<Hit> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<Hit>()
        PDDocument.load(file).use { doc ->
            val stripper =
                object : ReadablePdfTextStripper(doc) {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        if (positions.any { !NativeTextCoverage.valid(it) }) return
                        val chars = StringBuilder()
                        val mapping = mutableListOf<TextPosition>()
                        positions.forEach { p ->
                            Normalizer.normalize(p.unicode, Normalizer.Form.NFKC)
                                .lowercase(Locale.ROOT)
                                .forEach {
                                    chars.append(it)
                                    mapping.add(p)
                                }
                        }
                        val content = chars.toString().lowercase(Locale.ROOT)
                        val target = CjkSearch.normalize(query)
                        var from = 0
                        while (from < content.length) {
                            val at = content.indexOf(target, from)
                            if (at < 0) break
                            val selected =
                                mapping.subList(at, (at + target.length).coerceAtMost(mapping.size))
                            val page = doc.getPage(currentPageNo - 1)
                            val box = page.cropBox
                            val points =
                                selected.flatMap { p ->
                                    PdfGlyphGeometry.displayed(p, box, page.rotation)
                                }
                            result.add(
                                Hit(
                                    currentPageNo - 1,
                                    chars.toString(),
                                    points.minOf { it.x }.coerceIn(0f, 1f),
                                    points.minOf { it.y }.coerceIn(0f, 1f),
                                    points.maxOf { it.x }.coerceIn(0f, 1f),
                                    points.maxOf { it.y }.coerceIn(0f, 1f),
                                )
                            )
                            from = at + target.length
                        }
                    }
                }
            stripper.sortByPosition = true
            stripper.getText(doc)
        }
        return result
    }
}
