package cn.paperforge.app.pdf

import android.graphics.*
import cn.paperforge.app.ocr.OcrEngine
import java.io.File

/** Rebuild editable text, table cells and embedded images, with original page sizes. */
object PhoneDocx {
    private fun xml(text: String) =
        text
            .filter { it >= ' ' || it in "\n\r\t" }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    internal fun tableXml(table: PhoneTables.Table, width: Float): String = buildString {
        val columns = table.rows.first().size
        val left = table.cells.minOf { it.box.left }
        val right = table.cells.maxOf { it.box.right }
        val total = (minOf((right - left) * width, width - 60) * 20).toInt()
        val boundaries =
            (0..columns).map { c ->
                val known =
                    table.cells.flatMap { cell ->
                        buildList {
                            if (cell.column == c) add(cell.box.left)
                            if (cell.columnEnd + 1 == c) add(cell.box.right)
                        }
                    }
                if (known.isEmpty()) left + (right - left) * c / columns
                else known.average().toFloat()
            }
        val widths =
            (0 until columns).map {
                ((boundaries[it + 1] - boundaries[it]) / (right - left) * total)
                    .toInt()
                    .coerceAtLeast(1)
            }
        append(
            "<w:tbl><w:tblPr><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblInd w:w=\"${(left*width*20-600).toInt().coerceAtLeast(0)}\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/><w:tblBorders>"
        )
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
            append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"777777\"/>")
        }
        append("</w:tblBorders></w:tblPr><w:tblGrid>")
        widths.forEach { append("<w:gridCol w:w=\"$it\"/>") }
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
                append(
                    "<w:tc><w:tcPr><w:tcW w:w=\"${widths.subList(column, (column+span).coerceAtMost(columns)).sum()}\" w:type=\"dxa\"/>"
                )
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
        layoutMode: String = "editable",
        progress: suspend (Int) -> Unit = {},
    ): org.json.JSONObject = DocxLayout.convert(input, output, ocr, layoutMode, progress)
}
