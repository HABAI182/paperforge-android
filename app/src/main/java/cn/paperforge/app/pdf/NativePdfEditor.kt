package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/**
 * Edits page text operators in place. Text the user did not touch keeps its original glyph codes, kerning and
 * state; new text is encoded in the chosen font and every edited object restores the original advance, so the text
 * after it stays where it was. Each edited object is written as one `/PFEdit` marked-content group, so a saved copy
 * can be edited again object by object.
 */
object NativePdfEditor {
    private val shows = setOf("Tj", "TJ", "'", "\"")
    private val positioning = setOf("T*", "Td", "TD", "Tm", "Tw", "Tc", "TL", "Ts")
    private val fillOps = setOf("g", "rg", "k", "sc", "scn")
    private val strokeOps = setOf("G", "RG", "K", "SC", "SCN")
    private val editTag = COSName.getPDFName("PFEdit")

    data class TextObject(
        val page: Int,
        val index: Int,
        val text: String,
        val font: String,
        val size: Float,
        val reason: String = "",
        val corners: List<PointN> = emptyList(),
        /** Character boxes in displayed, normalised page coordinates (offsets into [text]). */
        val units: List<ReaderTextUnit> = emptyList(),
        /** Fill colour as ARGB when it is a device colour. */
        val color: Int? = null,
        /** True when the object was written by this editor (a `/PFEdit` group). */
        val edited: Boolean = false,
        /** Font is embedded (possibly a subset): new characters may be missing. */
        val embedded: Boolean = false,
        val serif: Boolean = false,
        val monospace: Boolean = false,
        /** Invisible text (render mode 3), usually an OCR layer. */
        val invisible: Boolean = false,
    ) {
        val key
            get() = "$page:$index"

        val editable
            get() = reason.isEmpty()
    }

    data class Edit(
        val page: Int,
        val index: Int,
        val before: String,
        val text: String,
        val font: String = "ORIGINAL",
        val size: Float? = null,
        val fit: Boolean = true,
        val fontSha256: String = "",
        val runs: List<NativeRun> = emptyList(),
    ) {
        val key
            get() = "$page:$index"

        /** Legacy edits (no runs) replace the whole object with [text] in [font] at [size]. */
        val effectiveRuns: List<NativeRun>
            get() =
                if (runs.isNotEmpty() || text.isEmpty()) runs
                else listOf(NativeRun(text, -1, -1, font, fontSha256, size, null))

        val fontIds
            get() = effectiveRuns.filter { !it.keepsGlyphs }.map { it.font to it.fontSha256 }.distinct()

        fun json() =
            JSONObject()
                .put("page", page)
                .put("index", index)
                .put("before", before)
                .put("text", text)
                .put("font", font)
                .put("size", size ?: JSONObject.NULL)
                .put("fit", fit)
                .put("fontSha256", fontSha256)
                .apply { if (runs.isNotEmpty()) put("runs", NativeTextRuns.json(runs)) }

        companion object {
            fun decode(j: JSONObject) =
                Edit(
                    j.getInt("page"),
                    j.getInt("index"),
                    j.getString("before"),
                    j.getString("text"),
                    j.optString("font", "ORIGINAL"),
                    if (j.isNull("size")) null else j.getDouble("size").toFloat(),
                    j.optBoolean("fit", true),
                    j.optString("fontSha256"),
                    j.optJSONArray("runs")?.let(NativeTextRuns::decode).orEmpty(),
                )

            /** An edit holding [runs]; null when the runs equal the original text. */
            fun of(item: TextObject, runs: List<NativeRun>, fit: Boolean = true): Edit? {
                val normal = NativeTextRuns.normalize(runs)
                if (NativeTextRuns.unchanged(item.text, normal)) return null
                return Edit(
                    item.page,
                    item.index,
                    item.text,
                    NativeTextRuns.text(normal),
                    fit = fit,
                    runs = normal.ifEmpty { listOf(NativeRun("", -1, -1)) }.filter { it.text.isNotEmpty() },
                )
            }
        }
    }

    fun edits(request: JSONObject): List<Edit> =
        request.getJSONArray("edits").let { a -> List(a.length()) { Edit.decode(a.getJSONObject(it)) } }

    internal data class State(
        var font: PDFont? = null,
        var fontName: COSName? = null,
        var size: Float = 0f,
        var charSpace: Float = 0f,
        var wordSpace: Float = 0f,
        var horizontal: Float = 100f,
        var rise: Float = 0f,
        var render: Int = 0,
        var unknownFont: Boolean = false,
        var fill: List<Any> = listOf(COSInteger.ZERO, Operator.getOperator("g")),
        var fillSpace: List<Any> = emptyList(),
        var stroke: List<Any> = listOf(COSInteger.ZERO, Operator.getOperator("G")),
        var strokeSpace: List<Any> = emptyList(),
    )

    /** One glyph code of an object. [advance] is its displacement in text space, after horizontal scaling. */
    internal class Glyph(
        val bytes: ByteArray,
        val code: Int,
        val text: String,
        val start: Int,
        val end: Int,
        /** TJ number (thousandths of text space) applied just before this glyph. */
        val adjustBefore: Float,
        val state: State,
        val advance: Float,
    )

    internal class Span(
        val start: Int,
        val end: Int,
        val prefix: List<Any>,
        val base: State,
        val end_: State,
        val glyphs: List<Glyph>,
        val advance: Float,
        val shows: List<Int>,
        val objectInfo: TextObject,
    )

    internal class Page(val tokens: List<Any>, val spans: List<Span>)

    private fun displacement(font: PDFont, code: Int, state: State, single: Boolean): Float =
        (font.getDisplacement(code).x * state.size +
            state.charSpace +
            if (single && code == 32) state.wordSpace else 0f) * state.horizontal / 100f

    private fun decode(
        value: COSString,
        state: State,
        offset: Int,
        adjust: Float,
        out: MutableList<Glyph>,
        strict: Boolean,
    ): Int {
        val font = requireNotNull(state.font) { "缺少字体" }
        val input = ByteArrayInputStream(value.bytes)
        var at = offset
        var pending = adjust
        val all = value.bytes
        var read = 0
        while (input.available() > 0) {
            val left = input.available()
            val code = font.readCode(input)
            val used = left - input.available()
            require(used > 0) { "无法读取字形" }
            val unicode = PdfToUnicode.lookup(font, code) ?: font.toUnicode(code)
            if (strict) require(!unicode.isNullOrEmpty() && '�' !in unicode) { "缺少可用的Unicode映射" }
            val text = unicode.orEmpty()
            out +=
                Glyph(
                    all.copyOfRange(read, read + used),
                    code,
                    text,
                    at,
                    at + text.length,
                    pending,
                    state,
                    displacement(font, code, state, used == 1),
                )
            pending = 0f
            read += used
            at += text.length
        }
        return at
    }

    private fun color(tokens: List<Any>, space: List<Any>): Int? {
        val op = (tokens.lastOrNull() as? Operator)?.name ?: return null
        val values = tokens.dropLast(1).map { (it as? COSNumber)?.floatValue() ?: return null }
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
        fun argb(r: Float, g: Float, b: Float) = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        val model =
            when (op) {
                "g" -> "G"
                "rg" -> "RGB"
                "k" -> "CMYK"
                else ->
                    when ((space.firstOrNull() as? COSName)?.name) {
                        "DeviceGray" -> "G"
                        "DeviceRGB" -> "RGB"
                        "DeviceCMYK" -> "CMYK"
                        else -> return null
                    }
            }
        return when {
            model == "G" && values.size == 1 -> argb(values[0], values[0], values[0])
            model == "RGB" && values.size == 3 -> argb(values[0], values[1], values[2])
            model == "CMYK" && values.size == 4 ->
                argb(
                    (1 - values[0]) * (1 - values[3]),
                    (1 - values[1]) * (1 - values[3]),
                    (1 - values[2]) * (1 - values[3]),
                )
            else -> null
        }
    }

    private fun descriptor(font: PDFont?): Triple<Boolean, Boolean, Boolean> {
        val d = font?.fontDescriptor
        val name = font?.name.orEmpty().lowercase()
        val embedded =
            d != null && (d.fontFile != null || d.fontFile2 != null || d.fontFile3 != null) ||
                font is PDType0Font && (font.descendantFont?.fontDescriptor?.let {
                    it.fontFile != null || it.fontFile2 != null || it.fontFile3 != null
                } == true)
        val serif =
            d?.isSerif == true || listOf("times", "serif", "song", "ming", "georgia", "garamond", "simsun").any {
                it in name
            }
        val mono = d?.isFixedPitch == true || listOf("mono", "courier", "consol").any { it in name }
        return Triple(embedded, serif, mono)
    }

    internal fun page(pdf: PDDocument, number: Int): Page {
        val page = pdf.getPage(number)
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = parser.tokens.toList()
        var state = State()
        val stack = mutableListOf<State>()
        val marked = mutableListOf<Boolean>()
        val spans = mutableListOf<Span>()
        var start = 0
        var index = 0
        var showOrdinal = 0
        // An open /PFEdit group: everything up to its EMC is one object.
        var groupStart = -1
        var groupDepth = -1
        var groupPrefix = mutableListOf<Any>()
        var groupGlyphs = mutableListOf<Glyph>()
        var groupBase: State? = null
        var groupEntry = State()
        var groupInfo: State? = null
        var groupAdvance = 0f
        var groupShows = mutableListOf<Int>()
        var groupReason = ""
        var groupTail = 0f

        fun objectInfo(
            text: String,
            base: State,
            reason: String,
            edited: Boolean,
        ): TextObject {
            val (embedded, serif, mono) = descriptor(base.font)
            return TextObject(
                number,
                index++,
                text,
                base.font?.name.orEmpty(),
                base.size,
                reason,
                color = color(base.fill, base.fillSpace),
                edited = edited,
                embedded = embedded,
                serif = serif,
                monospace = mono,
                invisible = base.render == 3 || base.render == 7,
            )
        }

        fun reasonFor(s: State): String? =
            when {
                s.unknownFont -> "ExtGState字体对象暂不替换"
                s.font == null -> "缺少字体"
                s.font!!.isVertical -> "竖排字体保留原件"
                s.font is PDType3Font -> "Type3字形保留原件"
                !(s.size > 0 && s.size.isFinite() && s.horizontal != 0f) -> "特殊文字尺寸保留原件"
                s.render == 3 -> "不可见文字层，请使用OCR校对"
                s.render !in 0..2 -> "文字参与裁剪，保留原件"
                marked.lastOrNull() == true -> "对象含替代文本ActualText，保留原件"
                else -> null
            }

        /** Reads one show operator into [glyphs]; returns (advance, reason). */
        fun show(
            name: String,
            args: List<Any>,
            glyphs: MutableList<Glyph>,
            offset: Int,
            strictReason: String?,
        ): Pair<Float, String> {
            var advance = 0f
            var at = offset
            var adjust = 0f
            val reason =
                runCatching {
                        if (strictReason != null) error(strictReason)
                        val value = args.lastOrNull()
                        val items = if (value is COSArray) value.toList() else listOf(value)
                        val s = state.copy()
                        for (item in items) when (item) {
                            is COSString -> {
                                val before = glyphs.size
                                at = decode(item, s, at, adjust, glyphs, true)
                                adjust = 0f
                                for (g in before until glyphs.size) advance += glyphs[g].advance
                            }
                            is COSNumber -> {
                                adjust += item.floatValue()
                                advance -= item.floatValue() * s.size / 1000f * s.horizontal / 100f
                            }
                            else -> error("文字内容格式暂不支持")
                        }
                        groupTail = adjust
                    }
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
            if (reason.isNotEmpty()) {
                // Still collect what can be read, for selection and copying.
                runCatching {
                    val value = args.lastOrNull()
                    val items = if (value is COSArray) value.toList() else listOf(value)
                    var a = offset
                    for (item in items) if (item is COSString && state.font != null)
                        a = decode(item, state.copy(), a, 0f, glyphs, false)
                }
            }
            return advance to reason
        }

        for ((end, token) in tokens.withIndex()) {
            if (token !is Operator) continue
            val args = tokens.subList(start, end)
            fun number(i: Int) = (args.getOrNull(i) as? COSNumber)?.floatValue() ?: error("文字状态无效")
            val inGroup = groupStart >= 0
            when (token.name) {
                "q" -> stack += state.copy()
                "Q" -> if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                "Tf" -> {
                    state.fontName = args[0] as COSName
                    state.font = page.resources?.getFont(state.fontName)
                    state.size = number(1)
                    state.unknownFont = false
                }
                "Tc" -> state.charSpace = number(0)
                "Tw" -> state.wordSpace = number(0)
                "Tz" -> state.horizontal = number(0)
                "Ts" -> state.rise = number(0)
                "Tr" -> state.render = number(0).toInt()
                "cs" -> {
                    state.fillSpace = args.toList() + token
                    state.fill = state.fillSpace
                }
                "CS" -> {
                    state.strokeSpace = args.toList() + token
                    state.stroke = state.strokeSpace
                }
                in fillOps -> {
                    if (token.name in setOf("g", "rg", "k")) state.fillSpace = emptyList()
                    state.fill = args.toList() + token
                }
                in strokeOps -> {
                    if (token.name in setOf("G", "RG", "K")) state.strokeSpace = emptyList()
                    state.stroke = args.toList() + token
                }
                "gs" -> {
                    val properties = page.resources?.getExtGState(args[0] as COSName)?.cosObject
                    if (properties?.containsKey(COSName.FONT) == true) state.unknownFont = true
                }
                "BMC" -> marked += marked.lastOrNull() ?: false
                "BDC" -> {
                    val value = args.getOrNull(1)
                    val properties =
                        if (value is COSDictionary) value
                        else if (value is COSName) page.resources?.getProperties(value)?.cosObject
                        else null
                    if (args.getOrNull(0) == editTag && groupStart < 0) {
                        groupStart = start
                        groupDepth = marked.size
                        groupPrefix = mutableListOf()
                        groupGlyphs = mutableListOf()
                        groupBase = null
                        groupInfo = null
                        groupEntry = state.copy()
                        groupAdvance = 0f
                        groupShows = mutableListOf()
                        groupReason = ""
                        groupTail = 0f
                    }
                    marked +=
                        (marked.lastOrNull() == true ||
                            properties?.containsKey(COSName.ACTUAL_TEXT) == true)
                }
                "EMC" -> {
                    if (marked.isNotEmpty()) marked.removeAt(marked.lastIndex)
                    if (groupStart >= 0 && marked.size == groupDepth) {
                        val base = groupBase ?: state.copy()
                        val info = groupInfo ?: base
                        val text = groupGlyphs.joinToString("") { it.text }
                        val reason =
                            groupReason.ifEmpty { if (text.isEmpty()) "空文字对象" else "" }
                        spans +=
                            Span(
                                groupStart,
                                end,
                                groupPrefix.toList(),
                                base,
                                state.copy(),
                                groupGlyphs.toList(),
                                groupAdvance,
                                groupShows.toList(),
                                objectInfo(text, info, reason, true),
                            )
                        groupStart = -1
                    }
                }
            }
            if (inGroup && groupStart >= 0 && token.name in positioning && groupGlyphs.isEmpty() &&
                groupShows.isEmpty()
            ) {
                groupPrefix.addAll(args)
                groupPrefix += token
            }
            if (token.name in shows) {
                if (token.name == "\"") {
                    state.wordSpace = number(0)
                    state.charSpace = number(1)
                }
                val ordinal = showOrdinal++
                if (groupStart >= 0) {
                    if (groupBase == null) {
                        // Emission starts from the state at the group's start (after its positioning prefix).
                        groupBase =
                            groupEntry.copy(
                                charSpace = state.charSpace,
                                wordSpace = state.wordSpace,
                                rise = state.rise,
                            )
                        groupInfo = state.copy()
                        if (token.name in setOf("'", "\"")) {
                            if (token.name == "\"") {
                                groupPrefix += COSFloat(state.wordSpace)
                                groupPrefix += Operator.getOperator("Tw")
                                groupPrefix += COSFloat(state.charSpace)
                                groupPrefix += Operator.getOperator("Tc")
                            }
                            groupPrefix += Operator.getOperator("T*")
                        }
                    }
                    val offset = groupGlyphs.sumOf { it.text.length }
                    val (adv, reason) = show(token.name, args, groupGlyphs, offset, reasonFor(state))
                    groupAdvance += adv
                    groupShows += ordinal
                    if (reason.isNotEmpty() && reason != "空文字对象" && groupReason.isEmpty())
                        groupReason = reason
                } else {
                    val glyphs = mutableListOf<Glyph>()
                    val (advance, problem) = show(token.name, args, glyphs, 0, reasonFor(state))
                    val text = glyphs.joinToString("") { it.text }
                    val reason = problem.ifEmpty { if (text.isEmpty()) "空文字对象" else "" }
                    val prefix = mutableListOf<Any>()
                    if (token.name == "\"") {
                        prefix += COSFloat(state.wordSpace)
                        prefix += Operator.getOperator("Tw")
                        prefix += COSFloat(state.charSpace)
                        prefix += Operator.getOperator("Tc")
                    }
                    if (token.name in setOf("'", "\"")) prefix += Operator.getOperator("T*")
                    spans +=
                        Span(
                            start,
                            end,
                            prefix,
                            state.copy(),
                            state.copy(),
                            glyphs,
                            advance,
                            listOf(ordinal),
                            objectInfo(text, state, reason, false),
                        )
                }
            }
            start = end + 1
        }
        return Page(tokens, spans)
    }

    /** Glyph quads per root show operator, in content order. */
    private fun geometry(pdf: PDDocument, number: Int): Map<Int, List<List<PointN>>> {
        val result = mutableMapOf<Int, MutableList<List<PointN>>>()
        val page = pdf.getPage(number)
        val stripper =
            object : PDFTextStripper() {
                var ordinal = 0
                var active = -1
                var form = 0

                override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
                    val nested = operator.name == "Do"
                    val root = operator.name in shows && active < 0 && form == 0
                    if (root) active = ordinal++
                    if (nested) form++
                    try {
                        super.processOperator(operator, operands)
                    } finally {
                        if (nested) form--
                        if (root) active = -1
                    }
                }

                override fun processTextPosition(text: TextPosition) {
                    if (active >= 0 && form == 0)
                        result
                            .getOrPut(active) { mutableListOf() }
                            .add(PdfGlyphGeometry.displayed(text, page.cropBox, page.rotation))
                }
            }
        stripper.startPage = number + 1
        stripper.endPage = number + 1
        stripper.getText(pdf)
        return result
    }

    private fun units(span: Span, quads: Map<Int, List<List<PointN>>>): List<ReaderTextUnit> {
        val boxes = span.shows.flatMap { quads[it].orEmpty() }
        val glyphs = span.glyphs.filter { it.text.isNotEmpty() }
        fun box(q: List<PointN>, s: Int, e: Int) =
            ReaderTextUnit(s, e, q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
        if (boxes.size == span.glyphs.size)
            return span.glyphs.indices
                .filter { span.glyphs[it].text.isNotEmpty() }
                .map { box(boxes[it], span.glyphs[it].start, span.glyphs[it].end) }
        if (boxes.size == glyphs.size) return glyphs.indices.map { box(boxes[it], glyphs[it].start, glyphs[it].end) }
        if (boxes.isEmpty() || glyphs.isEmpty()) return emptyList()
        // Counts differ (unmapped codes): spread characters over the object's box by advance.
        val all = boxes.flatten()
        val left = all.minOf { it.x }
        val right = all.maxOf { it.x }
        val top = all.minOf { it.y }
        val bottom = all.maxOf { it.y }
        val total = glyphs.sumOf { abs(it.advance).toDouble() }.toFloat().takeIf { it > 0f }
        var x = left
        return glyphs.map { g ->
            val w = if (total != null) abs(g.advance) / total * (right - left) else (right - left) / glyphs.size
            ReaderTextUnit(g.start, g.end, x, top, x + w, bottom).also { x += w }
        }
    }

    internal fun objects(pdf: PDDocument, number: Int, parsed: Page = page(pdf, number)): List<TextObject> {
        val quads =
            runCatching { geometry(pdf, number) }
                .onFailure { android.util.Log.w("NativePdfEditor", "Text geometry unavailable", it) }
                .getOrDefault(emptyMap())
        return parsed.spans.map { span ->
            val units = units(span, quads)
            val corners =
                if (units.isEmpty()) emptyList()
                else
                    listOf(
                        PointN(units.minOf { it.left }, units.minOf { it.top }),
                        PointN(units.maxOf { it.right }, units.minOf { it.top }),
                        PointN(units.maxOf { it.right }, units.maxOf { it.bottom }),
                        PointN(units.minOf { it.left }, units.maxOf { it.bottom }),
                    )
            span.objectInfo.copy(corners = corners, units = units)
        }
    }

    suspend fun inspect(input: File, number: Int): Pair<Int, List<TextObject>> {
        currentCoroutineContext().ensureActive()
        return PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            require(number in 0 until pdf.numberOfPages) { "页码无效" }
            pdf.numberOfPages to objects(pdf, number)
        }
    }

    private val complexScripts =
        setOf(
            Character.UnicodeScript.ARABIC,
            Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.BENGALI,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.SYRIAC,
            Character.UnicodeScript.TAMIL,
            Character.UnicodeScript.TELUGU,
            Character.UnicodeScript.MALAYALAM,
            Character.UnicodeScript.KANNADA,
            Character.UnicodeScript.GUJARATI,
            Character.UnicodeScript.GURMUKHI,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.LAO,
        )

    /**
     * Encodes [text] in [font]. Embedded subsets often lack the font program's own character map, so `encode` fails
     * even for characters the document shows; those codes are then found through the font's ToUnicode map.
     */
    fun encode(font: PDFont, text: String): ByteArray =
        runCatching { font.encode(text) }.getOrNull()
            ?: run {
                val reverse = PdfToUnicode.map(font).entries.associate { (code, unicode) -> unicode to code }
                val width = if (font is PDType0Font) 2 else 1
                val out = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    val s = String(Character.toChars(cp))
                    val code =
                        reverse[s]
                            ?: runCatching { font.encode(s) }.getOrNull()?.let { b -> b.fold(0) { a, x -> (a shl 8) or (x.toInt() and 255) } }
                            ?: throw IllegalArgumentException("No glyph for U+%04X".format(cp))
                    if (width == 2) out.write(code shr 8 and 255)
                    out.write(code and 255)
                    i += Character.charCount(cp)
                }
                out.toByteArray()
            }

    /** Characters of [text] that [font] has no glyph for (after encoding), or that it cannot encode at all. */
    fun missing(font: PDFont, text: String): List<String> {
        val out = mutableListOf<String>()
        text.codePoints().toArray().distinct().forEach { cp ->
            val s = String(Character.toChars(cp))
            if (Character.isWhitespace(cp)) return@forEach
            val ok =
                runCatching {
                        val bytes = encode(font, s)
                        val input = ByteArrayInputStream(bytes)
                        var all = bytes.isNotEmpty()
                        while (input.available() > 0) {
                            val code = font.readCode(input)
                            val vector = font as? PDVectorFont
                            if (vector != null && !vector.hasGlyph(code)) all = false
                            // Subsets often keep a glyph slot with no outline: that prints nothing.
                            else if (vector != null && runCatching { vector.getPath(code).isEmpty }.getOrDefault(false))
                                all = false
                        }
                        all
                    }
                    .getOrDefault(false)
            if (!ok) out += s
        }
        return out
    }

    /** Loads fonts for new text into one document, once per id. */
    internal class FontCache(
        private val context: Context,
        private val pdf: PDDocument,
        private val fontFiles: Map<String, File>,
    ) {
        val loaded = mutableMapOf<String, PDFont>()

        fun get(id: String, sha: String, original: PDFont): PDFont {
            if (id == NativeRun.ORIGINAL) return original
            return loaded.getOrPut(id) {
                val font =
                    if (id == "CJK") PdfTextLayer.font(context, pdf)
                    else
                        NativeFontLibrary.open(context, id)?.use { PDType0Font.load(pdf, it, true) }
                            ?: (fontFiles[sha] ?: File(context.filesDir, "native-fonts/$sha.ttf"))
                                .inputStream()
                                .use { PDType0Font.load(pdf, it, true) }
                // setFont registers the subset with PDDocument through the public API.
                PDPageContentStream(pdf, PDPage()).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.endText()
                }
                font
            }
        }
    }

    /** What one object's edit produced, for the report and the preview. */
    data class Outcome(val index: Int, val fonts: List<String>, val scale: Float, val overflow: Boolean)

    private fun op(content: MutableList<Any>, name: String, vararg args: Any) {
        content.addAll(args)
        content += Operator.getOperator(name)
    }

    private fun rgb(color: Int, stroke: Boolean): List<Any> =
        listOf(
            COSFloat(((color shr 16) and 255) / 255f),
            COSFloat(((color shr 8) and 255) / 255f),
            COSFloat((color and 255) / 255f),
            Operator.getOperator(if (stroke) "RG" else "rg"),
        )

    /** Content replacing [span] with [runs]. */
    private fun emit(
        span: Span,
        runs: List<NativeRun>,
        fit: Boolean,
        resources: PDResources,
        fonts: FontCache,
        mapping: PdfTextLayer.Mapping?,
    ): Pair<List<Any>, Outcome> {
        val base = span.base
        val baseFont = checkNotNull(base.font)
        class Piece(val glyphs: List<Glyph>?, val run: NativeRun, val font: PDFont, val bytes: ByteArray, val width: Float)
        val pieces = mutableListOf<Piece>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val size = run.size
            if (run.keepsGlyphs) {
                val glyphs = span.glyphs.filter { it.start >= run.origStart && it.end <= run.origEnd && it.end > it.start }
                    .let { kept ->
                        // Zero-length glyphs (no Unicode) inside the range are kept with their neighbours.
                        val first = span.glyphs.indexOf(kept.firstOrNull() ?: return@let kept)
                        val last = span.glyphs.indexOf(kept.last())
                        span.glyphs.subList(first, last + 1)
                    }
                require(NativeTextRuns.text(listOf(run)) == glyphs.joinToString("") { it.text }) {
                    "文字对象已改变，请重新选择"
                }
                var width = 0f
                for (g in glyphs) {
                    val s = g.state.copy(size = size ?: g.state.size)
                    width += displacement(g.state.font!!, g.code, s, g.bytes.size == 1)
                    width -= g.adjustBefore * s.size / 1000f * s.horizontal / 100f
                }
                pieces += Piece(glyphs, run, glyphs.firstOrNull()?.state?.font ?: baseFont, ByteArray(0), width)
            } else {
                val text = Normalizer.normalize(run.text, Normalizer.Form.NFC)
                require(text.none { Character.isISOControl(it) }) { "一个对象只能输入一行文字" }
                require(text.codePoints().noneMatch { Character.UnicodeScript.of(it) in complexScripts }) {
                    "所选文字需要复杂排字，当前保留原件；可使用Office或正文排版转换"
                }
                val font = fonts.get(run.font, run.fontSha256, baseFont)
                val lacking = missing(font, text)
                require(lacking.isEmpty()) {
                    val which = if (run.font == NativeRun.ORIGINAL) "原字体（文件只嵌入了用到的字）" else "所选字体"
                    "${which}缺少「${lacking.take(8).joinToString("")}」的字形，请换用含这些字的字体"
                }
                val encoded = encode(font, text)
                if (font is PDType0Font && run.font != NativeRun.ORIGINAL) {
                    text.codePoints().toArray().forEach { font.addToSubset(it) }
                    mapping?.record(font, text)
                }
                val s = base.copy(font = font, size = size ?: base.size)
                var width = 0f
                val input = ByteArrayInputStream(encoded)
                while (input.available() > 0) {
                    val left = input.available()
                    val code = font.readCode(input)
                    width += displacement(font, code, s, left - input.available() == 1)
                }
                pieces += Piece(null, run.copy(text = text), font, encoded, width)
            }
        }
        val fixed = pieces.filter { it.glyphs != null }.sumOf { it.width.toDouble() }.toFloat()
        val flexible = pieces.filter { it.glyphs == null }.sumOf { it.width.toDouble() }.toFloat()
        val scale =
            if (fit && flexible > 0f && fixed + flexible > span.advance + 1e-3f)
                ((span.advance - fixed) / flexible).coerceIn(.25f, 1f)
            else 1f
        val content = mutableListOf<Any>()
        op(content, "BDC", editTag, COSDictionary().apply { setInt(COSName.getPDFName("I"), span.objectInfo.index) })
        content.addAll(span.prefix)
        // The emitted state; changes are written only where they differ.
        var font: PDFont? = base.font
        var fontName: COSName? = base.fontName
        var size = base.size
        var horizontal = base.horizontal
        var fill = base.fill
        var fillSpace = base.fillSpace
        var stroke = base.stroke
        var strokeSpace = base.strokeSpace
        var charSpace = base.charSpace
        var wordSpace = base.wordSpace
        val usedFonts = mutableListOf<String>()
        fun setState(
            f: PDFont,
            name: COSName?,
            sz: Float,
            h: Float,
            fl: List<Any>,
            flSpace: List<Any>,
            st: List<Any>,
            stSpace: List<Any>,
            tc: Float,
            tw: Float,
        ) {
            if (f !== font || sz != size) {
                op(content, "Tf", name ?: resources.add(f), COSFloat(sz))
                font = f
                fontName = name
                size = sz
            }
            if (h != horizontal) {
                op(content, "Tz", COSFloat(h))
                horizontal = h
            }
            if (fl != fill || flSpace != fillSpace) {
                if (flSpace.isNotEmpty() && flSpace != fl) content.addAll(flSpace)
                content.addAll(fl)
                fill = fl
                fillSpace = flSpace
            }
            if (st != stroke || stSpace != strokeSpace) {
                if (stSpace.isNotEmpty() && stSpace != st) content.addAll(stSpace)
                content.addAll(st)
                stroke = st
                strokeSpace = stSpace
            }
            if (tc != charSpace) {
                op(content, "Tc", COSFloat(tc))
                charSpace = tc
            }
            if (tw != wordSpace) {
                op(content, "Tw", COSFloat(tw))
                wordSpace = tw
            }
        }
        var emitted = 0f
        for (piece in pieces) {
            val run = piece.run
            val strokes = base.render == 1 || base.render == 2
            if (piece.glyphs != null) {
                var array = COSArray()
                var bytes = java.io.ByteArrayOutputStream()
                fun flush() {
                    if (bytes.size() > 0) array.add(COSString(bytes.toByteArray()))
                    bytes = java.io.ByteArrayOutputStream()
                    if (array.size() > 0) op(content, "TJ", array)
                    array = COSArray()
                }
                for (g in piece.glyphs) {
                    val s = g.state
                    val fl = run.color?.let { rgb(it, false) } ?: s.fill
                    val flSpace = if (run.color != null) emptyList() else s.fillSpace
                    val st = if (run.color != null && strokes) rgb(run.color, true) else s.stroke
                    val stSpace = if (run.color != null && strokes) emptyList() else s.strokeSpace
                    val sz = run.size ?: s.size
                    val changes =
                        g.state.font !== font || sz != size || s.horizontal != horizontal || fl != fill ||
                            flSpace != fillSpace || st != stroke || stSpace != strokeSpace ||
                            s.charSpace != charSpace || s.wordSpace != wordSpace
                    if (changes) {
                        flush()
                        setState(g.state.font!!, s.fontName, sz, s.horizontal, fl, flSpace, st, stSpace, s.charSpace, s.wordSpace)
                    }
                    if (g.adjustBefore != 0f) {
                        if (bytes.size() > 0) array.add(COSString(bytes.toByteArray()))
                        bytes = java.io.ByteArrayOutputStream()
                        array.add(COSFloat(g.adjustBefore))
                    }
                    bytes.write(g.bytes)
                }
                flush()
                emitted += piece.width
                usedFonts += (piece.glyphs.firstOrNull()?.state?.font?.name ?: baseFont.name).orEmpty()
            } else {
                val fl = run.color?.let { rgb(it, false) } ?: base.fill
                val flSpace = if (run.color != null) emptyList() else base.fillSpace
                val st = if (run.color != null && strokes) rgb(run.color, true) else base.stroke
                val stSpace = if (run.color != null && strokes) emptyList() else base.strokeSpace
                val name = if (piece.font === base.font) base.fontName else null
                setState(
                    piece.font,
                    name,
                    run.size ?: base.size,
                    base.horizontal * scale,
                    fl,
                    flSpace,
                    st,
                    stSpace,
                    base.charSpace,
                    base.wordSpace,
                )
                op(content, "Tj", COSString(piece.bytes))
                emitted += piece.width * scale
                usedFonts += piece.font.name.orEmpty()
            }
        }
        val end = span.end_
        setState(
            end.font ?: baseFont,
            end.fontName,
            end.size,
            end.horizontal,
            end.fill,
            end.fillSpace,
            end.stroke,
            end.strokeSpace,
            end.charSpace,
            end.wordSpace,
        )
        if (end.size != 0f && end.horizontal != 0f && abs(emitted - span.advance) > 1e-4f)
            op(
                content,
                "TJ",
                COSArray().apply {
                    add(COSFloat((emitted - span.advance) * 1000f / end.size / (end.horizontal / 100f)))
                },
            )
        op(content, "EMC")
        return content to
            Outcome(span.objectInfo.index, usedFonts.distinct(), scale, emitted > span.advance * 1.002f + .01f)
    }

    /** Rewrites the given pages of [pdf] in memory. Returns per-object outcomes. */
    internal suspend fun rewritePages(
        pdf: PDDocument,
        changes: List<Edit>,
        fonts: FontCache,
        mapping: PdfTextLayer.Mapping?,
        initialFonts: MutableMap<Int, Set<COSName>> = mutableMapOf(),
        progress: suspend (Int) -> Unit = {},
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        var done = 0
        for ((number, selected) in changes.groupBy { it.page }) {
            currentCoroutineContext().ensureActive()
            require(number in 0 until pdf.numberOfPages) { "页码无效" }
            val parsed = page(pdf, number)
            val replacements = mutableMapOf<Int, Pair<Int, List<Any>>>()
            val page = pdf.getPage(number)
            initialFonts[number] = page.resources?.fontNames?.toSet().orEmpty()
            // Adding fonts gets a page-local dictionary, including inherited resources.
            val resources = COSDictionary(page.resources?.cosObject ?: COSDictionary())
            (resources.getDictionaryObject(COSName.FONT) as? COSDictionary)?.let {
                resources.setItem(COSName.FONT, COSDictionary(it))
            }
            page.resources = PDResources(resources)
            for (edit in selected) {
                val span = parsed.spans.firstOrNull { it.objectInfo.index == edit.index } ?: error("文字对象已改变")
                require(span.objectInfo.editable) { span.objectInfo.reason }
                require(span.objectInfo.text == edit.before) { "文字对象已改变，请重新选择" }
                val runs = edit.effectiveRuns
                runs.forEach { run ->
                    val s = run.size
                    require(s == null || (s > 0f && s.isFinite() && s <= 1000f)) { "字号应为0–1000pt之间的正数" }
                    require(!run.keepsGlyphs || (run.origStart in 0..run.origEnd && run.origEnd <= edit.before.length)) {
                        "文字对象已改变，请重新选择"
                    }
                }
                val (content, outcome) = emit(span, runs, edit.fit, page.resources, fonts, mapping)
                replacements[span.start] = span.end to content
                outcomes += outcome
                progress(++done * 90 / changes.size)
            }
            val written = mutableListOf<Any>()
            var cursor = 0
            while (cursor < parsed.tokens.size) {
                val replacement = replacements[cursor]
                if (replacement == null) written += parsed.tokens[cursor++]
                else {
                    written.addAll(replacement.second)
                    cursor = replacement.first + 1
                }
            }
            val stream = PDStream(pdf)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(written) }
            page.setContents(stream)
        }
        return outcomes
    }

    private suspend fun checkFonts(context: Context, changes: List<Edit>, fontFiles: Map<String, File>) {
        for ((id, sha) in changes.flatMap { it.fontIds }.distinct()) {
            if (id == NativeRun.ORIGINAL || id == "CJK" || NativeFontLibrary.isBuiltIn(id)) continue
            require(
                PdfAssembly.sha256(fontFiles[sha] ?: File(context.filesDir, "native-fonts/$sha.ttf")) == sha
            ) {
                "导入字体已改变，请重新选择"
            }
        }
    }

    /** Validates the edit list: one entry per object. */
    private fun validated(request: JSONObject): List<Edit> {
        val changes = edits(request)
        require(changes.isNotEmpty() && changes.map { it.key }.distinct().size == changes.size) { "请选择要修改的对象" }
        return changes
    }

    data class Preview(val file: File, val outcomes: List<Outcome>)

    /**
     * A one-page PDF of [number] with [edits] applied, for display and re-inspection while editing. The source is not
     * changed.
     */
    suspend fun preview(
        context: Context,
        input: File,
        number: Int,
        edits: List<Edit>,
        output: File,
        fontFiles: Map<String, File> = emptyMap(),
    ): Preview {
        val changes = edits.filter { it.page == number }
        checkFonts(context, changes, fontFiles)
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            val fonts = FontCache(context, pdf, fontFiles)
            val outcomes = if (changes.isEmpty()) emptyList() else rewritePages(pdf, changes, fonts, null)
            fonts.loaded.values.filterIsInstance<PDType0Font>().forEach { it.subset() }
            PDDocument().use { out ->
                out.importPage(pdf.getPage(number))
                out.save(output)
            }
            return Preview(output, outcomes)
        }
    }

    suspend fun apply(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File> = emptyMap(),
        stages: JobFileStages? = null,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject =
        if (stages == null) rewrite(context, input, output, request, fontFiles, false, progress)
        else NativeEditStages.apply(context, input, output, request, fontFiles, stages, progress)

    internal suspend fun rewrite(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File>,
        patchOnly: Boolean,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "请保存为新副本" }
        require(PdfAssembly.sha256(input) == request.getString("sourceSha256")) { "来源已改变，请重新打开编辑器" }
        val changes = validated(request)
        val part = File(output.path + ".part")
        val report =
            JSONObject()
                .put("sourceSha256", request.getString("sourceSha256"))
                .put("operation", "native-text-edit")
                .put("engine", "native-runs-2")
                .put("edits", JSONArray())
        val mapping = PdfTextLayer.Mapping()
        try {
            checkFonts(context, changes, fontFiles)
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val fonts = FontCache(context, pdf, fontFiles)
                val initialFonts = mutableMapOf<Int, Set<COSName>>()
                val outcomes = rewritePages(pdf, changes, fonts, mapping, initialFonts, progress)
                for (edit in changes) {
                    val outcome = outcomes.firstOrNull { it.index == edit.index && edit.page in initialFonts.keys }
                    report
                        .getJSONArray("edits")
                        .put(
                            edit.json()
                                .put("actualFont", outcome?.fonts?.joinToString(",").orEmpty())
                                .put("actualHorizontalScale", outcome?.scale ?: 1f)
                                .put("overflow", outcome?.overflow ?: false)
                        )
                }
                if (!patchOnly) pdf.save(part)
                else {
                    require(initialFonts.size == 1) { "文字流阶段必须对应一页" }
                    // Subset only the fonts introduced by this patch, before cloning their streams.
                    fonts.loaded.values.filterIsInstance<PDType0Font>().forEach { it.subset() }
                    val number = initialFonts.keys.single()
                    val source = pdf.getPage(number)
                    PDDocument().use { patch ->
                        val target = PDPage(source.mediaBox)
                        patch.addPage(target)
                        val cloner = PDFCloneUtility(patch)
                        val additions = COSDictionary()
                        source.resources.fontNames
                            .filterNot { it in initialFonts.getValue(number) }
                            .forEach { name ->
                                additions.setItem(
                                    name,
                                    cloner.cloneForNewDocument(source.resources.getFont(name).cosObject),
                                )
                            }
                        target.resources =
                            PDResources(COSDictionary().apply { setItem(COSName.FONT, additions) })
                        source.contents.use { target.setContents(PDStream(patch, it, COSName.FLATE_DECODE)) }
                        patch.save(part)
                    }
                }
            }
            mapping.finish(part)
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output)) { "无法保存修改副本" }
            progress(100)
            return report.put("outputBytes", output.length()).put("originalRetained", true)
        } finally {
            part.delete()
        }
    }
}
