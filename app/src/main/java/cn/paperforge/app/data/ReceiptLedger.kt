package cn.paperforge.app.data

import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.model.ReceiptFields
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * #26: the append-only receipt ledger behind the confirmation dialog.
 *
 * The CSV file is the source of truth: confirmed rows are appended atomically, with identical
 * document records deduplicated. The XLSX export rebuilds [XLSX_NAME] from the CSV ledger so a user who switches
 * formats never loses earlier rows. Both files live in the app-private exports directory that the
 * rest of the app already uses (`Repository.exports`), under a `PaperForge/` folder; nothing is
 * written until the user presses an export button.
 */
object ReceiptLedger {
    const val DIRECTORY = "PaperForge"
    const val CSV_NAME = "票据记录.csv"
    const val XLSX_NAME = "票据记录.xlsx"
    const val SHEET_NAME = "票据记录"
    const val MAX_SOURCE_CHARS = 6000

    val HEADERS =
        listOf("日期", "商家", "金额", "税额", "发票号", "分类", "规则完整度", "来源", "币种", "明细", "字段来源", "文档ID")
    val ITEM_HEADERS = listOf("记录序号", "来源", "文档ID", "名称", "数量", "单价", "金额", "来源页", "原文")

    private const val BOM = "\uFEFF"

    fun directory(repo: Repository) = File(repo.exports, DIRECTORY).apply { mkdirs() }

    fun csvFile(repo: Repository) = File(directory(repo), CSV_NAME)

    fun xlsxFile(repo: Repository) = File(directory(repo), XLSX_NAME)

    /**
     * Reads the text the offline engine already cached: page OCR layouts first, then plain OCR
     * text. PDF documents have no page rows in this app, so they yield an empty string instead of
     * guesses.
     */
    suspend fun sourceText(repo: Repository, doc: DocumentRow): String {
        if (doc.kind != "scan") return ""
        val pages = repo.dao.pages(doc.id)
        val text =
            pages
                .mapNotNull { page ->
                    page.ocrLayout
                        ?.let { runCatching { OcrLayout.decode(it).text }.getOrNull() }
                        ?.takeIf { it.isNotBlank() } ?: page.ocrText?.takeIf { it.isNotBlank() }
                }
                .joinToString("\n")
        return text.take(MAX_SOURCE_CHARS)
    }

    /**
     * One atomic write also upgrades old eight-column ledgers without losing their existing rows.
     */
    fun append(repo: Repository, record: ReceiptFields.Record, source: String = ""): File =
        appendAll(repo, listOf(record to source))

    @Synchronized
    fun appendAll(repo: Repository, records: List<Pair<ReceiptFields.Record, String>>): File {
        val file = csvFile(repo)
        val existing = readcsv(file)
        val rows =
            (listOf(HEADERS) +
                    existing.drop(1).map {
                        it + List((HEADERS.size - it.size).coerceAtLeast(0)) { "" }
                    })
                .toMutableList()
        records.forEach { (record, source) ->
            val next = row(record, source)
            if (record.documentId.isBlank() || next !in rows) rows += next
        }
        val temporary = File(file.path + ".part")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(BOM)
                rows.forEach { cells ->
                    writer.write(cells.joinToString(",") { escape(it) })
                    writer.write("\r\n")
                }
            }
            check(temporary.renameTo(file)) { "无法写入票据记录 CSV" }
        } finally {
            temporary.delete()
        }
        return file
    }

    /** Rebuilds the workbook from every row recorded in the CSV ledger. */
    fun exportXlsx(repo: Repository): File {
        val stored = readcsv(csvFile(repo))
        val rows =
            listOf(HEADERS) +
                stored.drop(1).map { it + List((HEADERS.size - it.size).coerceAtLeast(0)) { "" } }
        val details =
            listOf(ITEM_HEADERS) +
                rows.drop(1).flatMapIndexed { recordIndex, row ->
                    val items =
                        row.getOrNull(9)?.takeIf { it.isNotBlank() }?.let(::JSONArray)
                            ?: JSONArray()
                    (0 until items.length()).map { index ->
                        val item = items.getJSONObject(index)
                        val source = item.optJSONObject("source")
                        listOf(
                            (recordIndex + 1).toString(),
                            row.getOrElse(7) { "" },
                            row.getOrElse(11) { "" },
                            item.optString("name"),
                            item.optString("quantity"),
                            item.optString("unitPrice"),
                            item.optString("amount"),
                            source?.let { (it.optInt("page") + 1).toString() }.orEmpty(),
                            source?.optString("text").orEmpty(),
                        )
                    }
                }
        val output = xlsxFile(repo)
        val temporary = File(output.path + ".part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.entry(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
                )
                zip.entry(
                    "_rels/.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                )
                zip.entry(
                    "xl/workbook.xml",
                    """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$SHEET_NAME" sheetId="1" r:id="rId1"/><sheet name="明细" sheetId="2" r:id="rId2"/></sheets></workbook>""",
                )
                zip.entry(
                    "xl/_rels/workbook.xml.rels",
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                )
                zip.entry(
                    "xl/styles.xml",
                    """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
                )
                listOf(rows to setOf(2, 3, 6), details to setOf(0, 4, 5, 6, 7)).forEachIndexed {
                    sheet,
                    (content, numbers) ->
                    val body =
                        content
                            .mapIndexed { index, cells ->
                                "<row r=\"${index + 1}\">" +
                                    cells
                                        .mapIndexed { column, value ->
                                            "<c r=\"${column(column)}${index + 1}\"${cell(index, column, value, numbers)}</c>"
                                        }
                                        .joinToString("") +
                                    "</row>"
                            }
                            .joinToString("")
                    zip.entry(
                        "xl/worksheets/sheet${sheet + 1}.xml",
                        """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="${content.first().size}" width="18" customWidth="1"/></cols><sheetData>$body</sheetData></worksheet>""",
                    )
                }
            }
            check(temporary.renameTo(output)) { "无法写入票据记录 XLSX" }
        } finally {
            temporary.delete()
        }
        return output
    }

    fun readcsv(file: File): List<List<String>> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        val text = file.readText(Charsets.UTF_8).removePrefix(BOM)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                quoted && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += cell.toString()
                    cell.setLength(0)
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString()
                    cell.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            if (row.any { it.isNotEmpty() }) rows += row.toList()
        }
        return rows
    }

    private fun row(record: ReceiptFields.Record, source: String) =
        listOf(
            record.date,
            record.merchant,
            record.amount,
            record.tax,
            record.invoiceNo,
            record.category.label,
            String.format(Locale.ROOT, "%.2f", record.confidence),
            source,
            record.currency,
            JSONArray(
                    record.items.map { item ->
                        JSONObject()
                            .put("name", item.name)
                            .put("quantity", item.quantity)
                            .put("unitPrice", item.unitPrice)
                            .put("amount", item.amount)
                            .put("source", item.source?.let(::sourceJson))
                    }
                )
                .toString(),
            JSONObject(record.sources.mapValues { sourceJson(it.value) }).toString(),
            record.documentId,
        )

    private fun sourceJson(source: ReceiptFields.SourceLine) =
        JSONObject()
            .put("page", source.page)
            .put("line", source.line)
            .put("text", source.text)
            .put("engine", source.engine)
            .put("confidence", source.confidence ?: JSONObject.NULL)
            .put("pageId", source.pageId ?: JSONObject.NULL)
            .put("geometrySignature", source.geometrySignature ?: JSONObject.NULL)
            .put("corners", JSONArray(source.corners.map { listOf(it.x, it.y) }))

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /** Header stays text; amount/tax/confidence become numbers so spreadsheet apps can sum them. */
    private fun cell(rowIndex: Int, columnIndex: Int, value: String, numbers: Set<Int>): String {
        val numeric = rowIndex > 0 && columnIndex in numbers
        val number = if (numeric) value.takeIf { it.isNotBlank() }?.toBigDecimalOrNull() else null
        return if (number != null) {
            " t=\"n\" s=\"1\"><v>${number.toPlainString()}</v>"
        } else {
            " t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is>"
        }
    }

    private fun column(index: Int): String =
        if (index < 26) ('A' + index).toString() else column(index / 26 - 1) + ('A' + index % 26)

    private fun xml(value: String) =
        value
            .filter { it >= ' ' || it in "\n\r\t" }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun ZipOutputStream.entry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
