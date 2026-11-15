package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import kotlin.math.*

/**
 * The text of one page as a reader sees it: glyphs from the page and from the forms it draws (one level of nesting
 * is editable, deeper is read only), each with its exact text matrix from a full text-state machine, grouped into
 * visual lines and blocks whatever way the writer split the text (one operator per line, per word, or per glyph).
 */
internal class PdfTextModel private constructor(val number: Int, val page: PDPage) {
    /** A content stream: the page's (concatenated) contents, or a form XObject drawn by it. */
    class Stream(
        val form: PDFormXObject?,
        val formName: COSName?,
        /** Index of the `Do` token in the page stream that draws this form (forms at depth 1 only). */
        val drawnAt: Int,
        val depth: Int,
        val tokens: List<Any>,
        val resources: PDResources,
        val parent: Int = -1,
    )

    /** Text state right after a show operator: (stream, op) -> text matrix, line matrix, state. */
    class OpEnd(val tm: Matrix, val tlm: Matrix, val state: TextState)

    val opEnds = mutableMapOf<Pair<Int, Int>, OpEnd>()

    /** How many times each form stream is drawn in the document (shared forms are not edited in place). */
    private val formUses = mutableMapOf<COSBase, Int>()

    class TextState(
        var font: PDFont? = null,
        var fontName: COSName? = null,
        var size: Float = 0f,
        var charSpace: Float = 0f,
        var wordSpace: Float = 0f,
        var horizontal: Float = 100f,
        var leading: Float = 0f,
        var rise: Float = 0f,
        var render: Int = 0,
        var fill: List<Any> = listOf(COSInteger.ZERO, Operator.getOperator("g")),
        var fillSpace: List<Any> = emptyList(),
        var stroke: List<Any> = listOf(COSInteger.ZERO, Operator.getOperator("G")),
        var strokeSpace: List<Any> = emptyList(),
        /** Last ExtGState set (transparency, overprint), re-applied when the glyph is redrawn. */
        var extState: COSName? = null,
        var ctm: Matrix = Matrix(),
    ) {
        fun copy() =
            TextState(font, fontName, size, charSpace, wordSpace, horizontal, leading, rise, render, fill, fillSpace, stroke, strokeSpace, extState, ctm.clone())
    }

    class Glyph(
        val stream: Int,
        /** Token index of the show operator in its stream. */
        val op: Int,
        /** Element index in the operator's string/array operand (0 for Tj, ' and "). */
        val item: Int,
        val byteStart: Int,
        val byteEnd: Int,
        val code: Int,
        val text: String,
        val state: TextState,
        /** Text matrix at the glyph's origin, in its stream's user space. */
        val tm: Matrix,
        /** Advance in text space after horizontal scaling (what the next glyph's origin moves by). */
        val advance: Float,
        /** Glyph quad in page user space. */
        val quad: List<PointN>,
        /** Baseline origin and direction in page user space, and the size as drawn there. */
        val origin: PointN,
        val direction: PointN,
        val drawnSize: Float,
        val tag: Int,
    ) {
        /** Text matrix of the origin in page user space. */
        val pageMatrix: Matrix
            get() = tm.multiply(state.ctm)
    }

    /** A character of a line: a glyph, or a gap read as a space. */
    class Char(val glyph: Glyph?, val text: String, val start: Int, val end: Int, val box: List<PointN>)

    class Line(
        val index: Int,
        val chars: List<Char>,
        val text: String,
        val reason: String,
        val block: Int,
    ) {
        val glyphs
            get() = chars.mapNotNull { it.glyph }
    }

    val streams = mutableListOf<Stream>()
    val glyphs = mutableListOf<Glyph>()
    var lines: List<Line> = emptyList()
        private set

    companion object {
        private val showOps = setOf("Tj", "TJ", "'", "\"")
        private val fillOps = setOf("g", "rg", "k", "sc", "scn")
        private val strokeOps = setOf("G", "RG", "K", "SC", "SCN")
        val editTag: COSName = COSName.getPDFName("PFEdit")

        fun read(pdf: PDDocument, number: Int): PdfTextModel {
            val page = pdf.getPage(number)
            val model = PdfTextModel(number, page)
            val tokens = PDFStreamParser(page).apply { parse() }.tokens.toList()
            model.streams += Stream(null, null, -1, 0, tokens, page.resources ?: PDResources())
            model.countForms(pdf)
            model.parse(0, Matrix(), 0)
            model.group()
            return model
        }
    }

    private fun countForms(pdf: PDDocument) {
        fun walk(res: PDResources?, depth: Int) {
            val x = res?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return
            for (k in x.keySet()) {
                val o = x.getDictionaryObject(k) as? COSStream ?: continue
                if (o.getCOSName(COSName.SUBTYPE) != COSName.FORM) continue
                formUses[o] = (formUses[o] ?: 0) + 1
                if (depth < 3) walk(runCatching { PDFormXObject(o).resources }.getOrNull(), depth + 1)
            }
        }
        for (p in pdf.pages) walk(p.resources, 0)
    }

    /** True when every form between the page and [stream] is drawn only once in the document. */
    fun privateChain(stream: Int): Boolean {
        var s = stream
        while (s > 0) {
            val f = streams[s].form ?: return false
            if ((formUses[f.cosObject] ?: 1) > 1 && streams[s].depth > 1) return false
            s = streams[s].parent
        }
        return true
    }

    private fun displacement(font: PDFont, code: Int, s: TextState, single: Boolean): Float =
        (font.getDisplacement(code).x * s.size + s.charSpace + if (single && code == 32) s.wordSpace else 0f) *
            s.horizontal / 100f

    private fun unicode(font: PDFont, code: Int): String =
        PdfToUnicode.lookup(font, code) ?: runCatching { font.toUnicode(code) }.getOrNull()?.takeIf { it.isNotEmpty() && '�' !in it }
            ?: "�"

    private fun ascentDescent(font: PDFont): Pair<Float, Float> {
        val d = font.fontDescriptor ?: (font as? PDType0Font)?.descendantFont?.fontDescriptor
        val a = d?.ascent?.div(1000f)?.takeIf { it > .2f && it < 1.5f } ?: .85f
        val de = d?.descent?.div(1000f)?.takeIf { it < 0f && it > -.8f } ?: -.2f
        return a to de
    }

    private fun parse(streamIndex: Int, initialCtm: Matrix, depth: Int) {
        val stream = streams[streamIndex]
        val tokens = stream.tokens
        var state = TextState(ctm = initialCtm.clone())
        val stack = mutableListOf<TextState>()
        var tm = Matrix()
        var tlm = Matrix()
        var tag = -1
        val marks = mutableListOf<Int>()
        var start = 0
        for ((end, token) in tokens.withIndex()) {
            if (token !is Operator) continue
            val args = tokens.subList(start, end)
            fun num(i: Int) = (args.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f
            when (val name = token.name) {
                "q" -> stack += state.copy()
                "Q" -> if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                "cm" -> state.ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).multiply(state.ctm)
                "BT" -> {
                    tm = Matrix()
                    tlm = Matrix()
                }
                "Tf" -> {
                    state.fontName = args.getOrNull(0) as? COSName
                    state.font = state.fontName?.let { runCatching { stream.resources.getFont(it) }.getOrNull() }
                    state.size = num(1)
                }
                "Tc" -> state.charSpace = num(0)
                "Tw" -> state.wordSpace = num(0)
                "Tz" -> state.horizontal = num(0)
                "TL" -> state.leading = num(0)
                "Ts" -> state.rise = num(0)
                "Tr" -> state.render = num(0).toInt()
                "Td" -> {
                    tlm = Matrix.getTranslateInstance(num(0), num(1)).multiply(tlm)
                    tm = tlm.clone()
                }
                "TD" -> {
                    state.leading = -num(1)
                    tlm = Matrix.getTranslateInstance(num(0), num(1)).multiply(tlm)
                    tm = tlm.clone()
                }
                "Tm" -> {
                    tlm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5))
                    tm = tlm.clone()
                }
                "T*" -> {
                    tlm = Matrix.getTranslateInstance(0f, -state.leading).multiply(tlm)
                    tm = tlm.clone()
                }
                "gs" -> state.extState = args.getOrNull(0) as? COSName
                "cs" -> {
                    state.fillSpace = args.toList() + token
                    state.fill = state.fillSpace
                }
                "CS" -> {
                    state.strokeSpace = args.toList() + token
                    state.stroke = state.strokeSpace
                }
                in fillOps -> {
                    if (name in setOf("g", "rg", "k")) state.fillSpace = emptyList()
                    state.fill = args.toList() + token
                }
                in strokeOps -> {
                    if (name in setOf("G", "RG", "K")) state.strokeSpace = emptyList()
                    state.stroke = args.toList() + token
                }
                "BDC", "BMC" -> {
                    marks += tag
                    if (args.getOrNull(0) == editTag) {
                        val props = args.getOrNull(1) as? COSDictionary
                        tag = props?.getInt(COSName.getPDFName("L"), -1) ?: -1
                    }
                }
                "EMC" -> tag = marks.removeLastOrNull() ?: -1
                "Do" ->
                    if (depth < 3) {
                        val key = args.getOrNull(0) as? COSName
                        val form = key?.let { runCatching { stream.resources.getXObject(it) }.getOrNull() } as? PDFormXObject
                        if (form != null) {
                            val formTokens = runCatching { PDFStreamParser(form).apply { parse() }.tokens.toList() }.getOrNull()
                            if (formTokens != null) {
                                streams +=
                                    Stream(form, key, if (depth == 0) end else -1, depth + 1, formTokens, form.resources ?: stream.resources, streamIndex)
                                val m = (form.matrix ?: Matrix()).multiply(state.ctm)
                                parse(streams.lastIndex, m, depth + 1)
                            }
                        }
                    }
            }
            if (token.name in showOps) {
                if (token.name == "'" || token.name == "\"") {
                    if (token.name == "\"") {
                        state.wordSpace = num(0)
                        state.charSpace = num(1)
                    }
                    tlm = Matrix.getTranslateInstance(0f, -state.leading).multiply(tlm)
                    tm = tlm.clone()
                }
                val font = state.font
                if (font != null && state.size != 0f) {
                    val snapshot = state.copy()
                    val operand = args.lastOrNull()
                    val items = if (operand is COSArray) operand.toList() else listOf(operand)
                    val (ascent, descent) = ascentDescent(font)
                    for ((itemIndex, item) in items.withIndex()) {
                        when (item) {
                            is COSString -> {
                                val bytes = item.bytes
                                val input = ByteArrayInputStream(bytes)
                                var read = 0
                                while (input.available() > 0) {
                                    val left = input.available()
                                    val code = runCatching { font.readCode(input) }.getOrNull() ?: break
                                    val used = left - input.available()
                                    if (used <= 0) break
                                    val adv = displacement(font, code, snapshot, used == 1)
                                    val w0 = runCatching { font.getDisplacement(code).x }.getOrDefault(.5f)
                                    // Text rendering matrix: [Tfs*Th 0 0 Tfs 0 Ts] x Tm x CTM.
                                    val params = Matrix(snapshot.size * snapshot.horizontal / 100f, 0f, 0f, snapshot.size, 0f, snapshot.rise)
                                    val trm = params.multiply(tm).multiply(snapshot.ctm)
                                    fun p(x: Float, y: Float) = trm.transformPoint(x, y).let { PointN(it.x, it.y) }
                                    val quad = listOf(p(0f, descent), p(w0, descent), p(w0, ascent), p(0f, ascent))
                                    val o = p(0f, 0f)
                                    val e = p(1f, 0f)
                                    val len = hypot(e.x - o.x, e.y - o.y).coerceAtLeast(1e-6f)
                                    val up = p(0f, 1f)
                                    glyphs +=
                                        Glyph(
                                            streamIndex,
                                            end,
                                            itemIndex,
                                            read,
                                            read + used,
                                            code,
                                            unicode(font, code),
                                            snapshot,
                                            tm.clone(),
                                            adv,
                                            quad,
                                            o,
                                            PointN((e.x - o.x) / len, (e.y - o.y) / len),
                                            hypot(up.x - o.x, up.y - o.y),
                                            tag,
                                        )
                                    tm = Matrix.getTranslateInstance(adv, 0f).multiply(tm)
                                    read += used
                                    NativePdfEditor.seenCodes.getOrPut(font.cosObject) { java.util.Collections.synchronizedSet(mutableSetOf()) } += code
                                }
                            }
                            is COSNumber ->
                                tm =
                                    Matrix.getTranslateInstance(-item.floatValue() / 1000f * snapshot.size * snapshot.horizontal / 100f, 0f)
                                        .multiply(tm)
                        }
                    }
                }
                opEnds[streamIndex to end] = OpEnd(tm.clone(), tlm.clone(), state.copy())
            }
            start = end + 1
        }
    }

    private fun reasonFor(g: Glyph, stream: Stream): String? =
        when {
            g.state.font!!.isVertical -> "竖排文字暂不支持修改"
            g.state.render in 4..7 -> "文字被用作裁剪路径，修改会改变图形"
            g.state.render == 3 -> "不可见文字层（OCR），请在识别结果中校对"
            stream.depth > 1 && !privateChain(g.stream) -> "文字位于多页共用的嵌套图形组中，暂不支持修改"
            else -> null
        }

    /** Groups glyphs into visual lines (baseline direction and offset, split at column-sized gaps) and blocks. */
    private fun group() {
        data class Proto(val glyphs: MutableList<Glyph>, val dir: PointN, val offset: Float, val size: Float)
        val visible = glyphs.filter { it.text.isNotEmpty() && it.drawnSize > 0f }
        val protos = mutableListOf<Proto>()
        // Glyphs in content order; join a line with the same direction whose baseline is within a third of the size.
        for (g in visible.sortedWith(compareBy({ -it.origin.y }, { it.origin.x }))) {
            val off = g.origin.x * -g.direction.y + g.origin.y * g.direction.x
            val proto =
                protos.lastOrNull { p ->
                    abs(p.dir.x * g.direction.x + p.dir.y * g.direction.y) > .999f &&
                        abs(p.offset - off) < .35f * max(p.size, g.drawnSize)
                }
            if (proto != null) proto.glyphs += g else protos += Proto(mutableListOf(g), g.direction, off, g.drawnSize)
        }
        // Split each proto line where the gap is wider than a column gutter; order glyphs along the baseline.
        val segments = mutableListOf<List<Glyph>>()
        for (p in protos) {
            val sorted = p.glyphs.sortedBy { it.origin.x * p.dir.x + it.origin.y * p.dir.y }
            var current = mutableListOf<Glyph>()
            var lastEnd = Float.NaN
            for (g in sorted) {
                val along = g.origin.x * p.dir.x + g.origin.y * p.dir.y
                val width = advanceOnPage(g)
                if (current.isNotEmpty() && along - lastEnd > 2.2f * g.drawnSize) {
                    segments += current
                    current = mutableListOf()
                }
                // Overprinted duplicates (fake bold) are kept once.
                if (current.isNotEmpty() && abs(along - (lastEnd - width)) < .05f * g.drawnSize && current.last().code == g.code)
                    continue
                current += g
                lastEnd = along + width
            }
            if (current.isNotEmpty()) segments += current
        }
        // Blocks: lines whose start aligns and that follow within 1.8 line heights; columns ordered left to right.
        class Seg(val glyphs: List<Glyph>) {
            val left = glyphs.minOf { it.quad.minOf { q -> q.x } }
            val right = glyphs.maxOf { it.quad.maxOf { q -> q.x } }
            val top = glyphs.maxOf { it.quad.maxOf { q -> q.y } }
            val bottom = glyphs.minOf { it.quad.minOf { q -> q.y } }
            val size = glyphs.map { it.drawnSize }.sorted()[glyphs.size / 2]
            var block = -1
        }
        val segs = segments.map(::Seg).sortedByDescending { it.top }
        var blocks = 0
        for ((i, s) in segs.withIndex()) {
            if (s.block >= 0) continue
            s.block = blocks++
            var last = s
            for (t in segs.subList(i + 1, segs.size)) {
                if (t.block >= 0) continue
                val gap = last.bottom - t.top
                val overlap = min(last.right, t.right) - max(last.left, t.left)
                if (gap < 1.8f * last.size && gap > -.5f * last.size && overlap > .3f * min(last.right - last.left, t.right - t.left)) {
                    t.block = s.block
                    last = t
                }
            }
        }
        // Reading order: blocks by column (left edge buckets) when they sit side by side, else top to bottom.
        val blockBoxes = segs.groupBy { it.block }.mapValues { (_, v) -> Triple(v.minOf { it.left }, v.maxOf { it.top }, v.maxOf { it.right }) }
        val width = page.cropBox.width
        val orderedBlocks =
            blockBoxes.keys.sortedWith { a, b ->
                val (la, ta, ra) = blockBoxes.getValue(a)
                val (lb, tb, rb) = blockBoxes.getValue(b)
                val sideBySide = ra < lb + .02f * width || rb < la + .02f * width
                val narrow = ra - la < .6f * width && rb - lb < .6f * width
                if (sideBySide && narrow) la.compareTo(lb) else tb.compareTo(ta).let { if (it == 0) la.compareTo(lb) else it }
            }
        val out = mutableListOf<Line>()
        for (b in orderedBlocks) {
            for (s in segs.filter { it.block == b }.sortedByDescending { it.top }) {
                val chars = mutableListOf<Char>()
                var text = StringBuilder()
                var prev: Glyph? = null
                for (g in s.glyphs) {
                    if (prev != null && prev.text != " " && g.text != " ") {
                        val gap = (g.origin.x - prev.origin.x) * g.direction.x + (g.origin.y - prev.origin.y) * g.direction.y - advanceOnPage(prev)
                        if (gap > spaceGap(prev)) {
                            val a = prev.quad[1]
                            val bq = g.quad[0]
                            chars += Char(null, " ", text.length, text.length + 1, listOf(a, bq, g.quad[3], prev.quad[2]))
                            text.append(' ')
                        }
                    }
                    chars += Char(g, g.text, text.length, text.length + g.text.length, g.quad)
                    text.append(g.text)
                    prev = g
                }
                val reason =
                    s.glyphs.firstNotNullOfOrNull { g -> reasonFor(g, streams[g.stream]) }.orEmpty()
                out += Line(out.size, chars, text.toString(), reason, b)
            }
        }
        lines = out
    }

    /** A gap wider than this after [g] reads as a word space: 60 % of the font's space, at most 0.22 em. */
    private fun spaceGap(g: Glyph): Float {
        val font = g.state.font ?: return .22f * g.drawnSize
        val space = runCatching { font.getSpaceWidth() / 1000f }.getOrNull()?.takeIf { it > .1f && it < .7f }
        return g.drawnSize * minOf(.22f, (space ?: .3f) * .6f)
    }

    /**
     * How far [line] can grow along its baseline: to the next text on the same baseline, else to the right edge of
     * the widest line of its block (ragged lines have room up to the paragraph's measure).
     */
    fun room(line: Line): Float {
        val glyphs = line.glyphs
        if (glyphs.isEmpty()) return 0f
        val d = glyphs.first().direction
        val last = glyphs.last()
        val end = last.origin.x * d.x + last.origin.y * d.y + advanceOnPage(last)
        val off = last.origin.x * -d.y + last.origin.y * d.x
        var next = Float.MAX_VALUE
        var blockEnd = end
        var peers = false
        for (other in lines) {
            if (other === line) continue
            val og = other.glyphs.firstOrNull() ?: continue
            if (abs(og.direction.x * d.x + og.direction.y * d.y) < .999f) continue
            val oOff = og.origin.x * -d.y + og.origin.y * d.x
            val start = og.origin.x * d.x + og.origin.y * d.y
            if (abs(oOff - off) < .35f * last.drawnSize && start > end - .5f) next = minOf(next, start)
            if (other.block == line.block) {
                peers = true
                val ol = other.glyphs.last()
                blockEnd = maxOf(blockEnd, ol.origin.x * d.x + ol.origin.y * d.y + advanceOnPage(ol))
            }
        }
        // A paragraph line keeps to its paragraph's right edge (justified columns must not grow into the gutter);
        // a line on its own may run up to the next text on its baseline.
        val gapLimit = if (next < Float.MAX_VALUE) next - .3f * last.drawnSize else Float.MAX_VALUE
        val limit = if (peers) minOf(blockEnd, gapLimit) else if (gapLimit < Float.MAX_VALUE) gapLimit else blockEnd
        return (limit - end).coerceAtLeast(0f)
    }

    /** Advance of a glyph along its baseline in page user space. */
    fun advanceOnPage(g: Glyph): Float {
        val m = g.pageMatrix
        return g.advance * hypot(m.scaleX, m.shearY)
    }

    /** Normalised displayed coordinates (crop box, /Rotate) of a page-space point. */
    fun displayed(p: PointN): PointN {
        val box = page.cropBox
        val n = PointN((p.x - box.lowerLeftX) / box.width, 1 - (p.y - box.lowerLeftY) / box.height)
        return when ((page.rotation % 360 + 360) % 360) {
            90 -> PointN(1 - n.y, n.x)
            180 -> PointN(1 - n.x, 1 - n.y)
            270 -> PointN(n.y, 1 - n.x)
            else -> n
        }
    }

    fun unitOf(c: Char): ReaderTextUnit {
        val pts = c.box.map(::displayed)
        return ReaderTextUnit(c.start, c.end, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
}
