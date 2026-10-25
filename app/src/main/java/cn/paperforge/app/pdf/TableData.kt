package cn.paperforge.app.pdf

import android.graphics.RectF
import cn.paperforge.app.model.*
import org.json.JSONArray
import org.json.JSONObject

data class TableExportOptions(
    val format: String = "XLSX",
    val separator: String = ",",
    val encoding: String = "UTF8_BOM",
    val excelText: Boolean = false,
    val columnTypes: Map<String, String> = emptyMap(),
) {
    fun type(table: Int, column: Int) = columnTypes["$table:$column"] ?: "TEXT"

    fun json() =
        JSONObject()
            .put("format", format)
            .put("separator", separator)
            .put("encoding", encoding)
            .put("excelText", excelText)
            .put("columnTypes", JSONObject(columnTypes))

    companion object {
        fun decode(o: JSONObject) =
            TableExportOptions(
                o.optString("format", "XLSX").takeIf { it in setOf("XLSX", "CSV", "TSV") }
                    ?: "XLSX",
                o.optString("separator", ",").takeIf { it in setOf(",", ";", "\t") } ?: ",",
                o.optString("encoding", "UTF8_BOM").takeIf {
                    it in setOf("UTF8_BOM", "UTF16LE", "GB18030")
                } ?: "UTF8_BOM",
                o.optBoolean("excelText", false),
                o.optJSONObject("columnTypes")
                    ?.let { types ->
                        types.keys().asSequence().associateWith {
                            types.optString(it).takeIf { v ->
                                v in setOf("TEXT", "NUMBER", "DECIMAL")
                            } ?: "TEXT"
                        }
                    }
                    .orEmpty(),
            )
    }
}

object TableData {
    fun table(o: JSONObject): PhoneTables.Table {
        val rows =
            o.getJSONArray("rows").let { a ->
                List(a.length()) { r ->
                    a.getJSONArray(r).let { row -> List(row.length()) { row.getString(it) } }
                }
            }
        val cells =
            o.getJSONArray("cells").let { a ->
                List(a.length()) { i ->
                    val c = a.getJSONObject(i)
                    val b = c.getJSONArray("box")
                    PhoneTables.Cell(
                        c.getInt("row"),
                        c.getInt("column"),
                        c.getInt("rowEnd"),
                        c.getInt("columnEnd"),
                        RectF(
                            b.getDouble(0).toFloat(),
                            b.getDouble(1).toFloat(),
                            b.getDouble(2).toFloat(),
                            b.getDouble(3).toFloat(),
                        ),
                        c.getString("text"),
                        c.optInt("sourcePage").takeIf { it > 0 },
                        c.optBoolean("corrected"),
                        c.optString("textSource", "page"),
                    )
                }
            }
        return PhoneTables.Table(
            o.getInt("page"),
            rows,
            cells,
            o.getString("method"),
            o.getJSONArray("sourcePages").let { a -> List(a.length()) { a.getInt(it) } },
            o.optJSONObject("ocrLayout")?.let { OcrLayout.decode(it.toString()) },
            o.optString("algorithm", "legacy-tables-v1"),
        )
    }

    fun json(table: PhoneTables.Table) =
        JSONObject()
            .put("page", table.page)
            .put("sourcePages", JSONArray(table.sourcePages))
            .put("method", table.method)
            .put("algorithm", table.algorithm)
            .put("rows", JSONArray().apply { table.rows.forEach { put(JSONArray(it)) } })
            .put(
                "cells",
                JSONArray().apply {
                    table.cells.forEach { c ->
                        put(
                            JSONObject()
                                .put("row", c.row)
                                .put("column", c.column)
                                .put("rowEnd", c.rowEnd)
                                .put("columnEnd", c.columnEnd)
                                .put("text", c.text)
                                .put("sourcePage", c.sourcePage)
                                .put("corrected", c.corrected)
                                .put("textSource", c.textSource)
                                .put(
                                    "box",
                                    JSONArray(
                                        listOf(c.box.left, c.box.top, c.box.right, c.box.bottom)
                                    ),
                                )
                        )
                    }
                },
            )
            .apply {
                table.ocrLayout
                    ?.takeIf { table.sourcePages.size == 1 }
                    ?.let {
                        put(
                            "ocrLayout",
                            JSONObject(it.copy(tables = listOf(structure(table, it))).encode()),
                        )
                    }
            }

    fun structure(table: PhoneTables.Table, layout: OcrLayout? = null) =
        OcrTable(
            table.rows.size,
            table.rows.first().size,
            table.cells.map { cell ->
                OcrCell(
                    cell.row,
                    cell.column,
                    cell.rowEnd - cell.row + 1,
                    cell.columnEnd - cell.column + 1,
                    listOf(
                        PointN(cell.box.left, cell.box.top),
                        PointN(cell.box.right, cell.box.top),
                        PointN(cell.box.right, cell.box.bottom),
                        PointN(cell.box.left, cell.box.bottom),
                    ),
                    cell.text,
                    layout
                        ?.takeIf { cell.textSource == "page" }
                        ?.lines
                        ?.indices
                        ?.filter { i ->
                            layout.lines[i].corners.let { p ->
                                p.isNotEmpty() &&
                                    cell.box.contains(
                                        p.map { it.x }.average().toFloat(),
                                        p.map { it.y }.average().toFloat(),
                                    )
                            }
                        }
                        .orEmpty(),
                    cell.corrected,
                    cell.textSource,
                )
            },
            table.method,
        )

    fun encode(tables: List<PhoneTables.Table>) =
        JSONObject()
            .put("format", "paperforge-tables")
            .put("version", 1)
            .put("algorithms", JSONArray(tables.map { it.algorithm }.distinct()))
            .put("tables", JSONArray().apply { tables.forEach { put(json(it)) } })

    fun decode(o: JSONObject) =
        o.getJSONArray("tables").let { a -> List(a.length()) { table(a.getJSONObject(it)) } }

    /** Explicit text wrapping uses constant Excel string formulas; the raw grid stays in JSON. */
    fun delimited(table: PhoneTables.Table, index: Int, options: TableExportOptions): String {
        val separator = if (options.format == "TSV") "\t" else options.separator
        return table.rows
            .mapIndexed { row, values ->
                values
                    .mapIndexed { column, raw ->
                        val number =
                            if (row > 0 && options.type(index, column) != "TEXT")
                                raw.toBigDecimalOrNull()?.toPlainString()
                            else null
                        val value =
                            number
                                ?: if (options.excelText && raw.isNotEmpty())
                                    "=\"${raw.replace("\"","\"\"")}\""
                                else raw
                        "\"${value.replace("\"","\"\"")}\""
                    }
                    .joinToString(separator)
            }
            .joinToString("\r\n")
    }
}
