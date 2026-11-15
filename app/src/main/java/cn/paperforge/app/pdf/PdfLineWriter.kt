package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.text.Normalizer
import kotlin.math.hypot

/**
 * Applies line edits to one page of an open document. For each edited line the glyphs from the first change on are
 * erased where they are (each replaced by a TJ adjustment of its own advance, so nothing else moves) and the line is
 * drawn again from that point in the stream that held it: unchanged characters with their original font and codes,
 * shifted only by what the edits before them added or removed; new text in the chosen font. Each redrawn line is a
 * `/PFEdit` marked-content sequence inside `q ... Q`.
 */
internal object PdfLineWriter {
    private class Piece(
        val glyph: PdfTextModel.Glyph?,
        val run: NativeRun,
        /** Original position along the baseline (page units) where this piece starts. */
        val origAlong: Float,
        val font: PDFont,
        val bytes: ByteArray,
        /** Text-space font size. */
        val tfs: Float,
        val horizontal: Float,
        /** Advance along the baseline in page units. */
        val advance: Float,
        val reference: PdfTextModel.Glyph,
        /** New text: where the original characters it replaced ended. */
        val replacedEnd: Float = 0f,
    )

    private fun op(out: MutableList<Any>, name: String, vararg args: Any) {
        out.addAll(args)
        out += Operator.getOperator(name)
    }

    private fun rgb(color: Int, stroke: Boolean): List<Any> =
        listOf(
            COSFloat(((color shr 16) and 255) / 255f),
            COSFloat(((color shr 8) and 255) / 255f),
            COSFloat((color and 255) / 255f),
            Operator.getOperator(if (stroke) "RG" else "rg"),
        )

    private fun along(p: PointN, d: PointN) = p.x * d.x + p.y * d.y

    private fun scaleOf(m: Matrix) = hypot(m.scaleX, m.shearY)

    fun apply(
        pdf: PDDocument,
        model: PdfTextModel,
        edits: List<NativePdfEditor.Edit>,
        fonts: NativePdfEditor.FontCache,
        mapping: PdfTextLayer.Mapping?,
    ): List<NativePdfEditor.Outcome> {
        val streams = model.streams
        // Page resources may be shared through the page tree: give the page its own dictionaries first.
        val page = model.page
        val pageResources = COSDictionary(page.resources?.cosObject ?: COSDictionary())
        for (k in listOf(COSName.FONT, COSName.XOBJECT, COSName.EXT_G_STATE))
            (pageResources.getDictionaryObject(k) as? COSDictionary)?.let { pageResources.setItem(k, COSDictionary(it)) }
        page.resources = PDResources(pageResources)
        fun resourcesOf(stream: Int): PDResources =
            if (stream == 0) page.resources else streams[stream].form?.resources ?: page.resources
        val replace = Array(streams.size) { mutableMapOf<Int, Pair<Int, List<Any>>>() }
        val insertAfter = Array(streams.size) { mutableMapOf<Int, MutableList<Any>>() }
        val erasedByOp = mutableMapOf<Pair<Int, Int>, MutableSet<PdfTextModel.Glyph>>()
        val outcomes = mutableListOf<NativePdfEditor.Outcome>()
        for (edit in edits) {
            val line = model.lines.getOrNull(edit.index) ?: error("文字行已改变，请重新选择")
            require(line.text == edit.before) { "文字行已改变，请重新选择" }
            require(line.reason.isEmpty()) { line.reason }
            val runs = edit.effectiveRuns
            val chars = line.chars
            val lineGlyphs = line.glyphs
            if (lineGlyphs.isEmpty()) continue
            val firstGlyph = lineGlyphs.first()
            val lastGlyph = lineGlyphs.last()
            val d = firstGlyph.direction
            val lineEnd = along(lastGlyph.origin, d) + model.advanceOnPage(lastGlyph)
            fun charAlong(i: Int): Float {
                if (i >= chars.size) return lineEnd
                chars[i].glyph?.let { return along(it.origin, d) }
                val before = chars.subList(0, i).lastOrNull { it.glyph != null }?.glyph ?: return along(firstGlyph.origin, d)
                return along(before.origin, d) + model.advanceOnPage(before)
            }
            fun charIndexAt(offset: Int) = chars.indexOfFirst { offset < it.end }.let { if (it < 0) chars.size else it }
            // The unchanged prefix stays exactly where it is.
            var keepUntil = 0
            for (run in runs) {
                if (run.keepsGlyphs && !run.styled && run.origStart == keepUntil) keepUntil = run.origEnd else break
            }
            if (keepUntil >= line.text.length) continue
            val firstChar = charIndexAt(keepUntil)
            val pieces = mutableListOf<Piece>()
            var origCursor = keepUntil
            var reference = chars.getOrNull(firstChar)?.glyph ?: chars.subList(0, firstChar).lastOrNull { it.glyph != null }?.glyph ?: firstGlyph
            var started = false
            for ((ri, run) in runs.withIndex()) {
                if (!started) {
                    if (run.keepsGlyphs && !run.styled && run.origEnd <= keepUntil) continue
                    started = true
                }
                if (run.keepsGlyphs) {
                    for (ci in charIndexAt(run.origStart) until charIndexAt(run.origEnd)) {
                        val g = chars[ci].glyph ?: continue
                        reference = g
                        val factor = run.size?.let { it / g.drawnSize } ?: 1f
                        pieces +=
                            Piece(g, run, along(g.origin, d), g.state.font!!, ByteArray(0), g.state.size * factor, g.state.horizontal,
                                model.advanceOnPage(g) * factor, g)
                    }
                    origCursor = run.origEnd
                } else {
                    val nextKept = runs.subList(ri + 1, runs.size).firstOrNull { it.keepsGlyphs }
                    val replacedEnd = nextKept?.let { charAlong(charIndexAt(it.origStart)) } ?: lineEnd
                    val text = Normalizer.normalize(run.text, Normalizer.Form.NFC)
                    require(text.none { Character.isISOControl(it) }) { "一行只能输入一行文字" }
                    val ref = chars.getOrNull(charIndexAt(origCursor))?.glyph ?: reference
                    val font = fonts.get(run.font, run.fontSha256, ref.state.font!!)
                    val lacking = NativePdfEditor.missing(font, text)
                    require(lacking.isEmpty()) {
                        val which = if (run.font == NativeRun.ORIGINAL) "原字体（文件只嵌入了用到的字）" else "所选字体"
                        "${which}缺少「${lacking.take(8).joinToString("")}」的字形，请换用含这些字的字体"
                    }
                    val bytes = NativePdfEditor.encode(font, text)
                    if (font is PDType0Font && run.font != NativeRun.ORIGINAL) {
                        text.codePoints().toArray().forEach { font.addToSubset(it) }
                        mapping?.record(font, text)
                    }
                    val visual = run.size ?: ref.drawnSize
                    val tfs = ref.state.size * visual / ref.drawnSize
                    var width = 0f
                    val input = ByteArrayInputStream(bytes)
                    while (input.available() > 0) width += font.getDisplacement(font.readCode(input)).x * tfs
                    val pageAdvance = width * ref.state.horizontal / 100f * scaleOf(ref.pageMatrix)
                    pieces +=
                        Piece(null, run.copy(text = text), charAlong(charIndexAt(origCursor)), font, bytes, tfs, ref.state.horizontal,
                            pageAdvance, ref, replacedEnd)
                    if (nextKept != null) origCursor = maxOf(origCursor, nextKept.origStart)
                }
            }
            val erased = chars.subList(firstChar, chars.size).mapNotNull { it.glyph }
            for (g in erased) erasedByOp.getOrPut(g.stream to g.op) { mutableSetOf() } += g
            fun layout(squeeze: Float): Pair<FloatArray, Float> {
                val starts = FloatArray(pieces.size)
                var shift = 0f
                var cursor = charAlong(firstChar)
                for ((k, p) in pieces.withIndex()) {
                    val g = p.glyph
                    val start = if (g != null) p.origAlong + shift else cursor
                    starts[k] = start
                    val adv = p.advance * if (g == null) squeeze else 1f
                    cursor = start + adv
                    shift = if (g != null) shift + adv - model.advanceOnPage(g) else cursor - p.replacedEnd
                }
                val end = if (pieces.lastOrNull()?.glyph != null) cursor else maxOf(cursor, lineEnd + shift)
                return starts to end
            }
            // Growing into free space on the line is fine; only the part that would hit the next text is squeezed,
            // and never below 80 % (beyond that the overflow is reported instead).
            val room = model.room(line)
            val grow = layout(1f).second - lineEnd
            val excess = grow - room
            val flexible = pieces.filter { it.glyph == null }.sumOf { it.advance.toDouble() }.toFloat()
            val squeeze = if (edit.fit && excess > .5f && flexible > 0f) ((flexible - excess) / flexible).coerceIn(.8f, 1f) else 1f
            val (starts, end) = layout(squeeze)
            // Drawn right after the line's last erased glyph, inside the same text object when the line lives in one
            // stream (reading order kept; the text state is restored afterwards), else after its text object.
            val anchor = erased.lastOrNull() ?: lastGlyph
            val target = anchor.stream
            val inline = erased.all { it.stream == target } && model.opEnds.containsKey(target to anchor.op)
            val inverse = runCatching { anchor.state.ctm.createAffineTransform().createInverse() }.getOrNull()
                ?: error("这段文字的坐标无法换算")
            val res = resourcesOf(target)
            val out = mutableListOf<Any>()
            if (!inline) op(out, "q")
            op(out, "BDC", PdfTextModel.editTag, COSDictionary().apply { setInt(COSName.getPDFName("L"), line.index) })
            if (!inline) op(out, "BT")
            op(out, "Tc", COSInteger.ZERO)
            op(out, "Tw", COSInteger.ZERO)
            var lastFont: PDFont? = null
            var lastSize = Float.NaN
            var lastFill: List<Any>? = null
            var lastStroke: List<Any>? = null
            var lastH = Float.NaN
            var lastRender = -1
            var lastRise = Float.NaN
            var lastExt: COSName? = null
            val used = mutableListOf<String>()
            for ((k, p) in pieces.withIndex()) {
                val g = p.glyph
                val ref = p.reference
                val st = ref.state
                val m = ref.pageMatrix
                val refAlong = along(PointN(m.translateX, m.translateY), d)
                val delta = starts[k] - refAlong
                val pageM = Matrix(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX + delta * d.x, m.translateY + delta * d.y)
                val local = Matrix(pageM.createAffineTransform().apply { preConcatenate(inverse) })
                if (p.font !== lastFont || p.tfs != lastSize) {
                    val name = if (g != null && g.stream == target && p.font === st.font) st.fontName else null
                    op(out, "Tf", name ?: res.add(p.font), COSFloat(p.tfs))
                    lastFont = p.font
                    lastSize = p.tfs
                }
                val h = p.horizontal * if (g == null) squeeze else 1f
                if (h != lastH) {
                    op(out, "Tz", COSFloat(h))
                    lastH = h
                }
                if (st.render != lastRender) {
                    op(out, "Tr", COSInteger.get(st.render.toLong()))
                    lastRender = st.render
                }
                if (st.rise != lastRise) {
                    op(out, "Ts", COSFloat(st.rise))
                    lastRise = st.rise
                }
                if (!inline && st.extState != null && st.extState != lastExt && ref.stream == target) {
                    op(out, "gs", st.extState!!)
                    lastExt = st.extState
                }
                val strokes = st.render == 1 || st.render == 2
                val fill =
                    p.run.color?.let { rgb(it, false) }
                        ?: (st.fillSpace.takeIf { it.isNotEmpty() && it != st.fill }.orEmpty() + st.fill)
                if (fill != lastFill) {
                    out.addAll(fill)
                    lastFill = fill
                }
                val stroke =
                    if (p.run.color != null && strokes) rgb(p.run.color, true)
                    else st.strokeSpace.takeIf { it.isNotEmpty() && it != st.stroke }.orEmpty() + st.stroke
                if (stroke != lastStroke) {
                    out.addAll(stroke)
                    lastStroke = stroke
                }
                op(out, "Tm", COSFloat(local.scaleX), COSFloat(local.shearY), COSFloat(local.shearX), COSFloat(local.scaleY),
                    COSFloat(local.translateX), COSFloat(local.translateY))
                op(out, "Tj", COSString(if (g != null) glyphBytes(model, g) else p.bytes))
                used += p.font.name.orEmpty()
            }
            val tokens = streams[target].tokens
            if (inline) {
                op(out, "EMC")
                // Restore what the original text object expects after this operator.
                val end = model.opEnds.getValue(target to anchor.op)
                val st = end.state
                if (st.fontName != null) op(out, "Tf", st.fontName!!, COSFloat(st.size))
                op(out, "Tz", COSFloat(st.horizontal))
                op(out, "Tr", COSInteger.get(st.render.toLong()))
                op(out, "Ts", COSFloat(st.rise))
                op(out, "Tc", COSFloat(st.charSpace))
                op(out, "Tw", COSFloat(st.wordSpace))
                op(out, "TL", COSFloat(st.leading))
                out.addAll(st.fillSpace.takeIf { it.isNotEmpty() && it != st.fill }.orEmpty() + st.fill)
                out.addAll(st.strokeSpace.takeIf { it.isNotEmpty() && it != st.stroke }.orEmpty() + st.stroke)
                val lm = end.tlm
                op(out, "Tm", COSFloat(lm.scaleX), COSFloat(lm.shearY), COSFloat(lm.shearX), COSFloat(lm.scaleY),
                    COSFloat(lm.translateX), COSFloat(lm.translateY))
                // Tm sits a distance along the line from Tlm; a TJ adjustment puts it back.
                val ax = lm.scaleX
                val ay = lm.shearY
                val norm = ax * ax + ay * ay
                val per = st.size * st.horizontal / 100f
                if (norm > 0f && per != 0f) {
                    val dx = ((end.tm.translateX - lm.translateX) * ax + (end.tm.translateY - lm.translateY) * ay) / norm
                    if (kotlin.math.abs(dx) > 1e-4f) op(out, "TJ", COSArray().apply { add(COSFloat(-dx * 1000f / per)) })
                }
                insertAfter[target].getOrPut(anchor.op) { mutableListOf() }.addAll(out)
            } else {
                op(out, "ET")
                op(out, "EMC")
                op(out, "Q")
                var after = anchor.op
                while (after < tokens.lastIndex && (tokens[after] as? Operator)?.name != "ET") after++
                insertAfter[target].getOrPut(after) { mutableListOf() }.addAll(out)
            }
            outcomes += NativePdfEditor.Outcome(line.index, used.distinct(), squeeze, end - lineEnd > room + 1f)
        }
        // Erase: each touched show operator becomes a TJ in which erased glyphs are advances.
        for ((key, erased) in erasedByOp) {
            val (stream, opIndex) = key
            val tokens = streams[stream].tokens
            var argsStart = opIndex
            while (argsStart > 0 && tokens[argsStart - 1] !is Operator) argsStart--
            val operator = (tokens[opIndex] as Operator).name
            val args = tokens.subList(argsStart, opIndex)
            val operand = args.lastOrNull()
            val items = if (operand is COSArray) operand.toList() else listOf(operand)
            val glyphsOfOp = model.glyphs.filter { it.stream == stream && it.op == opIndex }
            val array = COSArray()
            var pending = 0f
            fun flushNumber() {
                if (pending != 0f) array.add(COSFloat(pending))
                pending = 0f
            }
            for ((i, item) in items.withIndex()) {
                when (item) {
                    is COSString -> {
                        val bytes = item.bytes
                        var kept = java.io.ByteArrayOutputStream()
                        var at = 0
                        for (g in glyphsOfOp.filter { it.item == i }.sortedBy { it.byteStart }) {
                            if (g !in erased) continue
                            kept.write(bytes, at, g.byteStart - at)
                            if (kept.size() > 0) {
                                flushNumber()
                                array.add(COSString(kept.toByteArray()))
                            }
                            kept = java.io.ByteArrayOutputStream()
                            val per = g.state.size * g.state.horizontal / 100f
                            if (per != 0f) pending -= g.advance / per * 1000f
                            at = g.byteEnd
                        }
                        kept.write(bytes, at, bytes.size - at)
                        if (kept.size() > 0) {
                            flushNumber()
                            array.add(COSString(kept.toByteArray()))
                        }
                    }
                    is COSNumber -> pending += item.floatValue()
                }
            }
            flushNumber()
            val rebuilt = mutableListOf<Any>()
            if (operator == "\"") {
                rebuilt += args[0]
                rebuilt += Operator.getOperator("Tw")
                rebuilt += args[1]
                rebuilt += Operator.getOperator("Tc")
            }
            if (operator == "'" || operator == "\"") rebuilt += Operator.getOperator("T*")
            rebuilt.add(array)
            rebuilt += Operator.getOperator("TJ")
            replace[stream][argsStart] = opIndex to rebuilt
        }
        // Forms first (a shared form is cloned and the page's Do pointed at the copy), then the page.
        var pageDirty = replace[0].isNotEmpty() || insertAfter[0].isNotEmpty()
        for (index in streams.indices.reversed()) {
            val stream = streams[index]
            if (index != 0 && replace[index].isEmpty() && insertAfter[index].isEmpty()) continue
            if (index == 0 && !pageDirty) continue
            val tokens = stream.tokens
            val written = mutableListOf<Any>()
            var cursor = 0
            while (cursor < tokens.size) {
                val r = replace[index][cursor]
                if (r != null) {
                    written.addAll(r.second)
                    insertAfter[index][r.first]?.let(written::addAll)
                    cursor = r.first + 1
                    continue
                }
                written += tokens[cursor]
                insertAfter[index][cursor]?.let(written::addAll)
                cursor++
            }
            if (index == 0) {
                val out = PDStream(pdf)
                out.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(written) }
                page.setContents(out)
            } else {
                val form = stream.form!!
                val target =
                    if (sharedElsewhere(pdf, model, form.cosObject)) cloneForPage(pdf, model, index).also { pageDirty = true }
                    else form.cosObject
                target.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(written) }
            }
        }
        return outcomes
    }

    private fun glyphBytes(model: PdfTextModel, g: PdfTextModel.Glyph): ByteArray {
        val tokens = model.streams[g.stream].tokens
        var argsStart = g.op
        while (argsStart > 0 && tokens[argsStart - 1] !is Operator) argsStart--
        val operand = tokens.subList(argsStart, g.op).lastOrNull()
        val item = if (operand is COSArray) operand.get(g.item) else operand
        return (item as COSString).bytes.copyOfRange(g.byteStart, g.byteEnd)
    }

    /** True when the form is drawn elsewhere too (another page, or twice on this one). */
    private fun sharedElsewhere(pdf: PDDocument, model: PdfTextModel, form: COSStream): Boolean {
        var uses = 0
        for (p in pdf.pages) {
            val x = p.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: continue
            for (k in x.keySet()) if (x.getDictionaryObject(k) === form) uses++
        }
        return uses > 1 || model.streams.count { it.form?.cosObject === form } > 1
    }

    /** A private copy of a page-level form for this page; the page's Do is pointed at it. */
    private fun cloneForPage(pdf: PDDocument, model: PdfTextModel, index: Int): COSStream {
        val stream = model.streams[index]
        require(stream.drawnAt >= 1) { "共用的图形组无法单独修改" }
        val original = stream.form!!.cosObject
        val copy = pdf.document.createCOSStream()
        for ((k, v) in original.entrySet())
            if (k != COSName.LENGTH && k != COSName.FILTER && k != COSName.DECODE_PARMS) copy.setItem(k, v)
        val name = model.page.resources.add(PDFormXObject(copy))
        @Suppress("UNCHECKED_CAST")
        val pageTokens = model.streams[0].tokens as MutableList<Any>
        pageTokens[stream.drawnAt - 1] = name
        return copy
    }
}
