package cn.paperforge.app.pdf

import android.graphics.RectF
import cn.paperforge.app.model.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

object NativeTextCoverage {
    data class Region(val text: String, val rectangle: RectF)

    fun regions(doc: PDDocument, index: Int): List<Region> {
        val page = doc.getPage(index)
        val box = page.cropBox
        val regions = mutableListOf<Region>()
        val stripper =
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (positions.isEmpty()) return
                    val points =
                        positions.flatMap { p -> PdfGlyphGeometry.displayed(p, box, page.rotation) }
                    regions.add(
                        Region(
                            text,
                            RectF(
                                points.minOf { it.x },
                                points.minOf { it.y },
                                points.maxOf { it.x },
                                points.maxOf { it.y },
                            ),
                        )
                    )
                }
            }
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.sortByPosition = true
        stripper.getText(doc)
        return regions
    }

    fun substantial(regions: List<Region>) =
        regions.sumOf { it.text.count { c -> !c.isWhitespace() } } >= 120 &&
            regions.sumOf { (it.rectangle.width() * it.rectangle.height()).toDouble() } > .1

    fun removeCovered(layout: OcrLayout, regions: List<Region>) =
        layout.copy(
            lines =
                layout.lines.filter { line ->
                    val r =
                        RectF(
                            line.corners.minOf { it.x },
                            line.corners.minOf { it.y },
                            line.corners.maxOf { it.x },
                            line.corners.maxOf { it.y },
                        )
                    regions.none { native ->
                        val intersection = RectF(r)
                        intersection.intersect(native.rectangle) &&
                            intersection.width() * intersection.height() >
                                r.width() * r.height() * .35f
                    }
                }
        )
}
