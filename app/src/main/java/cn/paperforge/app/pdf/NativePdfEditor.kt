package cn.paperforge.app.pdf

import android.content.Context
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/**
 * Edits direct page text operators. Each replacement restores the original text state and advance.
 */
object NativePdfEditor {
    private val shows = setOf("Tj", "TJ", "'", "\"")

    data class TextObject(
        val page: Int,
        val index: Int,
        val text: String,
        val font: String,
        val size: Float,
        val reason: String = "",
        val corners: List<PointN> = emptyList(),
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
    ) {
        val key
            get() = "$page:$index"

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
                )
        }
    }

    fun edits(request: JSONObject): List<Edit> =
        request.getJSONArray("edits").let { a ->
            List(a.length()) { Edit.decode(a.getJSONObject(it)) }
        }

    private data class State(
        var font: PDFont? = null,
        var size: Float = 0f,
        var charSpace: Float = 0f,
        var wordSpace: Float = 0f,
        var horizontal: Float = 100f,
        var render: Int = 0,
        var unknownFont: Boolean = false,
    )

    private data class Span(
        val start: Int,
        val end: Int,
        val operator: String,
        val state: State,
        val advance: Float,
        val objectInfo: TextObject,
    )

    private data class Page(val tokens: List<Any>, val spans: List<Span>)

    private fun string(
        value: COSString,
        state: State,
        decode: Boolean = true,
    ): Pair<String, Float> {
        val font = requireNotNull(state.font) { "缺少字体" }
        val input = ByteArrayInputStream(value.bytes)
        val text = StringBuilder()
        var advance = 0f
        while (input.available() > 0) {
            val left = input.available()
            val code = font.readCode(input)
            require(input.available() < left) { "无法读取字形" }
            if (decode) {
                val unicode = font.toUnicode(code)
                require(!unicode.isNullOrEmpty() && '\uFFFD' !in unicode) { "缺少可用的Unicode映射" }
                text.append(unicode)
            }
            advance +=
                font.getDisplacement(code).x * state.size +
                    state.charSpace +
                    if (left - input.available() == 1 && code == 32) state.wordSpace else 0f
        }
        return text.toString() to advance
    }

    private fun page(pdf: PDDocument, number: Int): Page {
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
        for ((end, token) in tokens.withIndex()) {
            if (token !is Operator) continue
            val args = tokens.subList(start, end)
            fun number(i: Int) = (args.getOrNull(i) as? COSNumber)?.floatValue() ?: error("文字状态无效")
            when (token.name) {
                "q" -> stack += state.copy()
                "Q" -> if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                "Tf" -> {
                    state.font = page.resources?.getFont(args[0] as COSName)
                    state.size = number(1)
                    state.unknownFont = false
                }
                "Tc" -> state.charSpace = number(0)
                "Tw" -> state.wordSpace = number(0)
                "Tz" -> state.horizontal = number(0)
                "Tr" -> state.render = number(0).toInt()
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
                    marked +=
                        (marked.lastOrNull() == true ||
                            properties?.containsKey(COSName.ACTUAL_TEXT) == true)
                }
                "EMC" -> if (marked.isNotEmpty()) marked.removeAt(marked.lastIndex)
            }
            if (token.name in shows) {
                if (token.name == "\"") {
                    state.wordSpace = number(0)
                    state.charSpace = number(1)
                }
                var text = ""
                var advance = 0f
                val reason =
                    runCatching {
                            require(!state.unknownFont) { "ExtGState字体对象暂不替换" }
                            require(state.font != null) { "缺少字体" }
                            require(!state.font!!.isVertical) { "竖排字体保留原件" }
                            require(
                                state.size > 0 && state.size.isFinite() && state.horizontal != 0f
                            ) {
                                "特殊文字尺寸保留原件"
                            }
                            require(state.render in 0..2) {
                                if (state.render == 3) "不可见文字层，请使用OCR校对" else "文字参与裁剪，保留原件"
                            }
                            require(marked.lastOrNull() != true) { "对象含替代文本ActualText，保留原件" }
                            val value = args.lastOrNull()
                            val items = if (value is COSArray) value.toList() else listOf(value)
                            for (item in items) when (item) {
                                is COSString ->
                                    string(item, state).let { (s, width) ->
                                        text += s
                                        advance += width
                                    }
                                is COSNumber -> advance -= item.floatValue() * state.size / 1000f
                                else -> error("文字内容格式暂不支持")
                            }
                            require(text.isNotEmpty()) { "空文字对象" }
                        }
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                spans +=
                    Span(
                        start,
                        end,
                        token.name,
                        state.copy(),
                        advance,
                        TextObject(
                            number,
                            index++,
                            text,
                            state.font?.name.orEmpty(),
                            state.size,
                            reason,
                        ),
                    )
            }
            start = end + 1
        }
        return Page(tokens, spans)
    }

    private fun geometry(pdf: PDDocument, number: Int): Map<Int, List<PointN>> {
        val result = mutableMapOf<Int, MutableList<PointN>>()
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
                            .addAll(PdfGlyphGeometry.displayed(text, page.cropBox, page.rotation))
                }
            }
        stripper.startPage = number + 1
        stripper.endPage = number + 1
        stripper.getText(pdf)
        return result
    }

    suspend fun inspect(input: File, number: Int): Pair<Int, List<TextObject>> {
        currentCoroutineContext().ensureActive()
        return PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            require(number in 0 until pdf.numberOfPages) { "页码无效" }
            val parsed = page(pdf, number)
            val boxes =
                runCatching { geometry(pdf, number) }
                    .onFailure {
                        android.util.Log.w("NativePdfEditor", "Text geometry unavailable", it)
                    }
                    .getOrDefault(emptyMap())
            pdf.numberOfPages to
                parsed.spans.map { span ->
                    val points = boxes[span.objectInfo.index].orEmpty()
                    val corners =
                        if (points.isEmpty()) emptyList()
                        else
                            listOf(
                                PointN(points.minOf { it.x }, points.minOf { it.y }),
                                PointN(points.maxOf { it.x }, points.minOf { it.y }),
                                PointN(points.maxOf { it.x }, points.maxOf { it.y }),
                                PointN(points.minOf { it.x }, points.maxOf { it.y }),
                            )
                    span.objectInfo.copy(corners = corners)
                }
        }
    }

    suspend fun apply(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File> = emptyMap(),
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "请保存为新副本" }
        require(PdfAssembly.sha256(input) == request.getString("sourceSha256")) { "来源已改变，请重新打开编辑器" }
        val changes = edits(request)
        require(changes.isNotEmpty() && changes.map { it.key }.distinct().size == changes.size) {
            "请选择要修改的对象"
        }
        val part = File(output.path + ".part")
        val report =
            JSONObject()
                .put("sourceSha256", request.getString("sourceSha256"))
                .put("operation", "native-text-edit")
                .put("edits", JSONArray())
        val mapping = PdfTextLayer.Mapping()
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val fonts = mutableMapOf<String, PDType0Font>()
                fun font(edit: Edit, original: PDFont): PDFont {
                    if (edit.font == "ORIGINAL") return original
                    return fonts.getOrPut(edit.font) {
                        val loaded =
                            if (edit.font == "CJK") PdfTextLayer.font(context, pdf)
                            else {
                                val file =
                                    fontFiles[edit.fontSha256]
                                        ?: File(
                                            context.filesDir,
                                            "native-fonts/${edit.fontSha256}.ttf",
                                        )
                                // fontSha256 is checked before loading in the suspend loop below.
                                file.inputStream().use { PDType0Font.load(pdf, it, true) }
                            }
                        // setFont registers a subset with PDDocument using the pinned public API.
                        PDPageContentStream(pdf, PDPage()).use { stream ->
                            stream.beginText()
                            stream.setFont(loaded, 12f)
                            stream.endText()
                        }
                        loaded
                    }
                }
                for (edit in changes.filter { it.font !in setOf("ORIGINAL", "CJK") }) require(
                    PdfAssembly.sha256(
                        fontFiles[edit.fontSha256]
                            ?: File(context.filesDir, "native-fonts/${edit.fontSha256}.ttf")
                    ) == edit.fontSha256
                ) {
                    "导入字体已改变，请重新选择"
                }
                var done = 0
                for ((number, selected) in changes.groupBy { it.page }) {
                    currentCoroutineContext().ensureActive()
                    require(number in 0 until pdf.numberOfPages) { "页码无效" }
                    val parsed = page(pdf, number)
                    val replacements = mutableMapOf<Int, Pair<Int, List<Any>>>()
                    val page = pdf.getPage(number)
                    // Adding fonts gets a page-local dictionary, including inherited resources.
                    val resources = COSDictionary(page.resources?.cosObject ?: COSDictionary())
                    (resources.getDictionaryObject(COSName.FONT) as? COSDictionary)?.let {
                        resources.setItem(COSName.FONT, COSDictionary(it))
                    }
                    page.resources = PDResources(resources)
                    for (edit in selected) {
                        val span =
                            parsed.spans.firstOrNull { it.objectInfo.index == edit.index }
                                ?: error("文字对象已改变")
                        require(span.objectInfo.editable) { span.objectInfo.reason }
                        require(span.objectInfo.text == edit.before) { "文字对象已改变，请重新选择" }
                        val text = Normalizer.normalize(edit.text, Normalizer.Form.NFC)
                        require(text.none { Character.isISOControl(it) }) { "一个对象只能输入一行文字" }
                        require(
                            text.codePoints().noneMatch {
                                Character.UnicodeScript.of(it) in
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
                            }
                        ) {
                            "所选文字需要复杂排字，当前保留原件；可使用Office或正文排版转换"
                        }
                        val original = checkNotNull(span.state.font)
                        val target = font(edit, original)
                        val size = edit.size ?: span.state.size
                        require(size > 0f && size.isFinite() && size <= 1000f) {
                            "字号应为0–1000pt之间的正数"
                        }
                        val encoded =
                            try {
                                target.encode(text)
                            } catch (error: IllegalArgumentException) {
                                throw IllegalArgumentException(
                                    "所选字体缺少新文字的字形，请选PaperForge中英字体或导入相应字体：${error.message}",
                                    error,
                                )
                            }
                        if (target is PDType0Font && edit.font != "ORIGINAL") {
                            text.codePoints().toArray().forEach { target.addToSubset(it) }
                            mapping.record(target, text)
                        }
                        val changedState = span.state.copy(font = target, size = size)
                        val width = string(COSString(encoded), changedState, false).second
                        val horizontal =
                            if (edit.fit && span.advance > 0f && width > 0f)
                                span.state.horizontal * minOf(1f, span.advance / width)
                            else span.state.horizontal
                        val originalName = page.resources.add(original)
                        val targetName = page.resources.add(target)
                        val content = mutableListOf<Any>()
                        fun op(name: String, vararg args: Any) {
                            content.addAll(args)
                            content += Operator.getOperator(name)
                        }
                        if (span.operator == "\"") {
                            op("Tw", COSFloat(span.state.wordSpace))
                            op("Tc", COSFloat(span.state.charSpace))
                        }
                        if (span.operator in setOf("'", "\"")) op("T*")
                        op("Tf", targetName, COSFloat(size))
                        op("Tz", COSFloat(horizontal))
                        op("Tj", COSString(encoded))
                        op("Tf", originalName, COSFloat(span.state.size))
                        op("Tz", COSFloat(span.state.horizontal))
                        val adjustment =
                            (width * horizontal / span.state.horizontal - span.advance) * 1000f /
                                span.state.size
                        op("TJ", COSArray().apply { add(COSFloat(adjustment)) })
                        replacements[span.start] = span.end to content
                        report
                            .getJSONArray("edits")
                            .put(
                                edit
                                    .json()
                                    .put("actualFont", target.name)
                                    .put("originalAdvance", span.advance)
                                    .put("actualHorizontalScale", horizontal)
                            )
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
                    stream.createOutputStream(COSName.FLATE_DECODE).use {
                        ContentStreamWriter(it).writeTokens(written)
                    }
                    page.setContents(stream)
                }
                pdf.save(part)
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
