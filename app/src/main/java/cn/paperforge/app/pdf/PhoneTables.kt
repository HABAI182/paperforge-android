package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import cn.paperforge.app.model.OcrLayout
import cn.paperforge.app.ocr.OcrEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Table geometry and editable output run on Android; no desktop or public service. */
object PhoneTables {
    data class Token(
        val text: String,
        val box: RectF,
        val fontSize: Float = 11f,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    data class Cell(
        val row: Int,
        val column: Int,
        val rowEnd: Int,
        val columnEnd: Int,
        val box: RectF,
        val text: String,
    )

    data class Table(
        val page: Int,
        val rows: List<List<String>>,
        val cells: List<Cell>,
        val method: String,
        val sourcePages: List<Int> = listOf(page),
    )

    private fun cluster(values: List<Float>, tolerance: Float): List<Float> {
        val groups = mutableListOf<MutableList<Float>>()
        values.sorted().forEach { value ->
            if (groups.isEmpty() || value - groups.last().average() > tolerance)
                groups += mutableListOf(value)
            else groups.last() += value
        }
        return groups.map { it.average().toFloat() }
    }

    private fun nearest(values: List<Float>, value: Float) =
        values.indices.minBy { abs(values[it] - value) }

    internal fun joinTokens(tokens: List<Token>): String = buildString {
        var previous: Token? = null
        tokens
            .sortedBy { it.box.left }
            .forEach { token ->
                val before = previous
                if (
                    before != null &&
                        token.box.left - before.box.right > .002f &&
                        before.text.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } ==
                            true &&
                        token.text.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } ==
                            true
                )
                    append(' ')
                append(token.text)
                previous = token
            }
    }

    private fun content(tokens: List<Token>, box: RectF): String {
        val lines = mutableListOf<MutableList<Token>>()
        tokens
            .filter { box.contains(it.box.centerX(), it.box.centerY()) }
            .sortedBy { it.box.centerY() }
            .forEach { token ->
                val row = lines.lastOrNull()
                val tolerance = maxOf(.004f, token.box.height() * .55f)
                if (
                    row == null ||
                        abs(row.map { it.box.centerY() }.average() - token.box.centerY()) >
                            tolerance
                )
                    lines += mutableListOf(token)
                else row += token
            }
        return lines.joinToString("\n") { line -> joinTokens(line) }
    }

    private fun ruled(bitmap: Bitmap, page: Int, tokens: List<Token>): List<Table> {
        val image = Mat()
        val gray = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val grid = Mat()
        val labels = Mat()
        val stats = Mat()
        val centers = Mat()
        val resources =
            mutableListOf(image, gray, binary, horizontal, vertical, grid, labels, stats, centers)
        try {
            Utils.bitmapToMat(bitmap, image)
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                15.0,
            )
            val hKernel =
                Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(maxOf(24, bitmap.width / 35).toDouble(), 1.0),
                )
            val vKernel =
                Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(1.0, maxOf(24, bitmap.height / 35).toDouble()),
                )
            resources += listOf(hKernel, vKernel)
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, hKernel)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, vKernel)
            Core.bitwise_or(horizontal, vertical, grid)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            resources += kernel
            Imgproc.morphologyEx(grid, grid, Imgproc.MORPH_CLOSE, kernel)
            val count = Imgproc.connectedComponentsWithStats(grid, labels, stats, centers)
            val tables = mutableListOf<Table>()
            for (label in 1 until count) {
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (w < bitmap.width / 8 || h < 35) continue
                val view = grid.submat(org.opencv.core.Rect(x, y, w, h))
                val crop = view.clone()
                view.release()
                val hierarchy = Mat()
                val contours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(
                        crop,
                        contours,
                        hierarchy,
                        Imgproc.RETR_TREE,
                        Imgproc.CHAIN_APPROX_SIMPLE,
                    )
                    val boxes =
                        contours.mapIndexedNotNull { index, contour ->
                            val link = hierarchy.get(0, index)
                            val rect = Imgproc.boundingRect(contour)
                            if (link[3] < 0 || link[2] >= 0 || rect.width < 20 || rect.height < 12)
                                null
                            else
                                RectF(
                                    (x + rect.x).toFloat() / bitmap.width,
                                    (y + rect.y).toFloat() / bitmap.height,
                                    (x + rect.x + rect.width).toFloat() / bitmap.width,
                                    (y + rect.y + rect.height).toFloat() / bitmap.height,
                                )
                        }
                    if (boxes.size < 4) continue
                    val xs = cluster(boxes.flatMap { listOf(it.left, it.right) }, 7f / bitmap.width)
                    val ys =
                        cluster(boxes.flatMap { listOf(it.top, it.bottom) }, 7f / bitmap.height)
                    if (xs.size < 3 || ys.size < 3 || xs.size > 101 || ys.size > 501) continue
                    val rows = MutableList(ys.size - 1) { MutableList(xs.size - 1) { "" } }
                    val cells =
                        boxes.mapNotNull { box ->
                            val r = nearest(ys, box.top)
                            val c = nearest(xs, box.left)
                            val re = nearest(ys, box.bottom) - 1
                            val ce = nearest(xs, box.right) - 1
                            if (r !in rows.indices || c !in rows[0].indices || re < r || ce < c)
                                null
                            else
                                Cell(r, c, re, ce, box, content(tokens, box)).also {
                                    rows[r][c] = it.text
                                }
                        }
                    if (cells.count { it.text.isNotBlank() } >= 2)
                        tables += Table(page, rows, cells, "有线表")
                } finally {
                    contours.forEach { it.release() }
                    crop.release()
                    hierarchy.release()
                }
            }
            return tables.sortedBy { it.cells.minOfOrNull { cell -> cell.box.top } ?: 0f }
        } finally {
            resources.forEach { it.release() }
        }
    }

    private fun borderless(page: Int, tokens: List<Token>): List<Table> {
        val grouped = mutableListOf<MutableList<Token>>()
        tokens
            .sortedBy { it.box.centerY() }
            .forEach { token ->
                val row = grouped.lastOrNull()
                if (
                    row == null ||
                        abs(row.map { it.box.centerY() }.average() - token.box.centerY()) >
                            maxOf(.006f, token.box.height() * .6f)
                )
                    grouped += mutableListOf(token)
                else row += token
            }
        val lines =
            grouped
                .map { row ->
                    val words = mutableListOf<MutableList<Token>>()
                    row.sortedBy { it.box.left }
                        .forEach { token ->
                            if (
                                words.isEmpty() ||
                                    token.box.left - words.last().maxOf { it.box.right } > .018f
                            )
                                words += mutableListOf(token)
                            else words.last() += token
                        }
                    words.map { word ->
                        Token(
                            word.joinToString("") { it.text },
                            RectF(
                                word.minOf { it.box.left },
                                word.minOf { it.box.top },
                                word.maxOf { it.box.right },
                                word.maxOf { it.box.bottom },
                            ),
                        )
                    }
                }
                .filter { it.size in 2..30 }
        val groups = mutableListOf<MutableList<List<Token>>>()
        lines.forEach { row ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (
                previous != null &&
                    previous.size == row.size &&
                    row.zip(previous).all { (a, b) -> abs(a.box.left - b.box.left) < .035f } &&
                    row[0].box.top - previous[0].box.bottom < .08f
            )
                groups.last() += row
            else groups += mutableListOf(row)
        }
        return groups
            .filter { it.size >= 3 }
            .map { rows ->
                val cells =
                    rows.flatMapIndexed { r, row ->
                        row.mapIndexed { c, token -> Cell(r, c, r, c, token.box, token.text) }
                    }
                Table(page, rows.map { row -> row.map { it.text } }, cells, "无线表推断")
            }
    }

    internal fun nativeTokens(pdf: PDDocument, index: Int): List<Token> {
        val output = mutableListOf<Token>()
        val page = pdf.getPage(index)
        val bounds = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        fun display(x: Float, y: Float): Pair<Float, Float> =
            when (rotation) {
                90 -> 1 - y to x
                180 -> 1 - x to 1 - y
                270 -> y to 1 - x
                else -> x to y
            }
        val stripper =
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    positions.forEach { pos ->
                        val a =
                            display(
                                pos.xDirAdj / bounds.width,
                                (pos.yDirAdj - pos.heightDir) / bounds.height,
                            )
                        val b =
                            display(
                                (pos.xDirAdj + pos.widthDirAdj) / bounds.width,
                                pos.yDirAdj / bounds.height,
                            )
                        output +=
                            Token(
                                pos.unicode,
                                RectF(
                                    minOf(a.first, b.first),
                                    minOf(a.second, b.second),
                                    maxOf(a.first, b.first),
                                    maxOf(a.second, b.second),
                                ),
                                pos.fontSizeInPt,
                                pos.font.name.contains("Bold", true),
                                pos.font.name.contains("Italic", true),
                            )
                    }
                }
            }
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.sortByPosition = true
        stripper.getText(pdf)
        return output
    }

    internal fun ocrTokens(layout: OcrLayout): List<Token> =
        layout.lines.flatMap { line ->
            if (line.elements.isNotEmpty())
                line.elements.map { element ->
                    Token(
                        element.text,
                        RectF(
                            element.corners.minOf { it.x },
                            element.corners.minOf { it.y },
                            element.corners.maxOf { it.x },
                            element.corners.maxOf { it.y },
                        ),
                    )
                }
            else
                listOf(
                    Token(
                        line.text,
                        RectF(
                            line.corners.minOf { it.x },
                            line.corners.minOf { it.y },
                            line.corners.maxOf { it.x },
                            line.corners.maxOf { it.y },
                        ),
                    )
                )
        }

    internal fun detect(
        bitmap: Bitmap,
        page: Int,
        tokens: List<Token>,
        strategy: String = "auto",
    ): List<Table> {
        val found = if (strategy != "text") ruled(bitmap, page, tokens) else emptyList()
        return if (found.isNotEmpty() || strategy == "lines_strict") found
        else borderless(page, tokens)
    }

    suspend fun extract(
        input: File,
        ocr: OcrEngine,
        options: JSONObject,
        progress: (String) -> Unit,
    ): List<Table> {
        val output = mutableListOf<Table>()
        val isPdf = input.extension.equals("pdf", true)
        val native = if (isPdf) PDDocument.load(input) else null
        try {
            suspend fun analyze(bitmap: Bitmap, index: Int) {
                currentCoroutineContext().ensureActive()
                progress("识别手机表格 · 第 ${index + 1} 页")
                var tokens = native?.let { nativeTokens(it, index) }.orEmpty()
                if (tokens.sumOf { it.text.length } < 5 && options.optBoolean("ocr", true))
                    tokens = ocrTokens(ocr.recognizeBitmap(bitmap))
                val strategy = options.optString("tableStrategy", "auto")
                val ruled =
                    if (strategy != "text") ruled(bitmap, index + 1, tokens) else emptyList()
                output +=
                    if (ruled.isNotEmpty() || strategy == "lines_strict") ruled
                    else borderless(index + 1, tokens)
            }
            if (isPdf)
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        repeat(renderer.pageCount) { index ->
                            renderer.openPage(index).use { page ->
                                val scale = 2200f / maxOf(page.width, page.height)
                                val bitmap =
                                    Bitmap.createBitmap(
                                        (page.width * scale).toInt(),
                                        (page.height * scale).toInt(),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                    )
                                    analyze(bitmap, index)
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
            else {
                val bitmap = cn.paperforge.app.imaging.ScanProcessor.decode(input, 2600)
                try {
                    analyze(bitmap, 0)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            native?.close()
        }
        require(output.isNotEmpty()) { "未找到可重建表格。请裁切到表格区域，或改用有线表 / 无线表模式。" }
        if (!options.optBoolean("joinTables", false)) return output
        val joined = mutableListOf<Table>()
        output.forEach { table ->
            val previous = joined.lastOrNull()
            if (
                previous != null &&
                    previous.sourcePages.last() + 1 == table.page &&
                    previous.rows.first() == table.rows.first()
            ) {
                val offset = previous.rows.size - 1
                joined[joined.lastIndex] =
                    previous.copy(
                        rows = previous.rows + table.rows.drop(1),
                        cells =
                            previous.cells +
                                table.cells
                                    .filter { it.row > 0 }
                                    .map {
                                        it.copy(row = it.row + offset, rowEnd = it.rowEnd + offset)
                                    },
                        sourcePages = previous.sourcePages + table.page,
                    )
            } else joined += table
        }
        return joined
    }

    private fun xml(value: String) =
        value
            .filter { it >= ' ' || it in "\n\r\t" }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun column(index: Int): String =
        if (index < 26) ('A' + index).toString() else column(index / 26 - 1) + ('A' + index % 26)

    private fun ZipOutputStream.entry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun write(tables: List<Table>, output: File, csv: Boolean) {
        val temporary = File(output.path + ".part")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                if (csv)
                    tables.forEachIndexed { index, table ->
                        val data =
                            "\uFEFF" +
                                table.rows.joinToString("\r\n") { row ->
                                    row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
                                }
                        zip.entry("page-${table.page}-table-${index+1}.csv", data)
                    }
                else {
                    zip.entry(
                        "[Content_Types].xml",
                        """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="json" ContentType="application/json"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${tables.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""",
                    )
                    zip.entry(
                        "_rels/.rels",
                        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                    )
                    zip.entry(
                        "xl/workbook.xml",
                        """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${tables.mapIndexed { index, table -> "<sheet name=\"P${table.page}-T${index+1}\" sheetId=\"${index+1}\" r:id=\"rId${index+1}\"/>" }.joinToString("")}</sheets></workbook>""",
                    )
                    zip.entry(
                        "xl/_rels/workbook.xml.rels",
                        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${tables.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>" }}<Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                    )
                    zip.entry(
                        "xl/styles.xml",
                        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
                    )
                    tables.forEachIndexed { index, table ->
                        val merges =
                            table.cells.filter { it.rowEnd > it.row || it.columnEnd > it.column }
                        val body =
                            table.rows
                                .mapIndexed { r, row ->
                                    "<row r=\"${r+1}\">" +
                                        row.mapIndexed { c, value ->
                                                "<c r=\"${column(c)}${r+1}\" t=\"inlineStr\" s=\"0\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
                                            }
                                            .joinToString("") +
                                        "</row>"
                                }
                                .joinToString("")
                        val mergeXml =
                            if (merges.isEmpty()) ""
                            else
                                "<mergeCells count=\"${merges.size}\">" +
                                    merges.joinToString("") {
                                        "<mergeCell ref=\"${column(it.column)}${it.row+1}:${column(it.columnEnd)}${it.rowEnd+1}\"/>"
                                    } +
                                    "</mergeCells>"
                        zip.entry(
                            "xl/worksheets/sheet${index+1}.xml",
                            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="${table.rows.first().size}" width="22" customWidth="1"/></cols><sheetData>$body</sheetData>$mergeXml</worksheet>""",
                        )
                    }
                }
                val records =
                    JSONArray().apply {
                        tables.forEach { table ->
                            put(
                                JSONObject()
                                    .put("sourcePages", JSONArray(table.sourcePages))
                                    .put("method", table.method)
                                    .put(
                                        "rows",
                                        JSONArray().apply {
                                            table.rows.forEach { put(JSONArray(it)) }
                                        },
                                    )
                            )
                        }
                    }
                zip.entry(
                    if (csv) "structure.json" else "paperforge-structure.json",
                    records.toString(2),
                )
            }
            check(temporary.renameTo(output))
        } finally {
            temporary.delete()
        }
    }
}
