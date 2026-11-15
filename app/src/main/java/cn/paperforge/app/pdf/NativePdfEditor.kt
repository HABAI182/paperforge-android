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
import kotlin.math.roundToInt
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
        /** Characters whose code has no Unicode mapping (shown as U+FFFD; their glyphs are kept when untouched). */
        val unreadable: Int = 0,
        /** In a preview: the source line this line was redrawn from (the writer's /PFEdit marker), else -1. */
        val sourceLine: Int = -1,
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

    /** The page's text as visual lines (see [PdfTextModel]), one [TextObject] per line. */
    internal fun objects(pdf: PDDocument, number: Int): List<TextObject> {
        val model = PdfTextModel.read(pdf, number)
        return model.lines.map { line -> lineObject(model, line) }
    }

    internal fun lineObject(model: PdfTextModel, line: PdfTextModel.Line): TextObject {
        val glyphs = line.glyphs
        val first = glyphs.first()
        val (embedded, serif, mono) = descriptor(first.state.font)
        val units = line.chars.map(model::unitOf)
        val corners =
            listOf(
                PointN(units.minOf { it.left }, units.minOf { it.top }),
                PointN(units.maxOf { it.right }, units.minOf { it.top }),
                PointN(units.maxOf { it.right }, units.maxOf { it.bottom }),
                PointN(units.minOf { it.left }, units.maxOf { it.bottom }),
            )
        val sizes = glyphs.map { it.drawnSize }.sorted()
        return TextObject(
            model.number,
            line.index,
            line.text,
            first.state.font?.name.orEmpty(),
            (sizes[sizes.size / 2] * 10).roundToInt() / 10f,
            line.reason,
            corners,
            units,
            color(first.state.fill, first.state.fillSpace),
            glyphs.any { it.tag >= 0 },
            embedded,
            serif,
            mono,
            glyphs.all { it.state.render == 3 || it.state.render == 7 },
            unreadable = glyphs.count { it.text == "�" },
            sourceLine = glyphs.firstOrNull { it.tag >= 0 }?.tag ?: -1,
        )
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

    /** Codes drawn with each font on the pages read so far, keyed by the font dictionary. */
    internal val seenCodes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<COSBase, MutableSet<Int>>())

    private val subsetName = Regex("^[A-Z]{6}[+]")

    /**
     * Characters of [text] that [font] cannot show. An embedded subset of a simple (8-bit) font may keep an encoding
     * that names letters it does not contain; renderers then draw nothing. For such fonts only codes the document
     * already draws with that font are trusted.
     */
    fun missing(font: PDFont, text: String): List<String> {
        val seen = seenCodes[font.cosObject]
        val strictSubset = font !is PDType0Font && subsetName.containsMatchIn(font.name.orEmpty()) && font.isEmbedded
        if (strictSubset) {
            return text.codePoints().toArray().distinct().filterNot { Character.isWhitespace(it) }.map { String(Character.toChars(it)) }.filter { s ->
                val code = runCatching { encode(font, s) }.getOrNull()?.let { b -> if (b.size == 1) b[0].toInt() and 255 else -1 } ?: -1
                code < 0 || seen == null || code !in seen
            }
        }
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

    /** Rewrites the given pages of [pdf] in memory. Returns per-line outcomes. */
    internal suspend fun rewritePages(
        pdf: PDDocument,
        changes: List<Edit>,
        fonts: FontCache,
        mapping: PdfTextLayer.Mapping?,
        progress: suspend (Int) -> Unit = {},
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        var done = 0
        for ((number, selected) in changes.groupBy { it.page }) {
            currentCoroutineContext().ensureActive()
            require(number in 0 until pdf.numberOfPages) { "页码无效" }
            val model = PdfTextModel.read(pdf, number)
            outcomes += PdfLineWriter.apply(pdf, model, selected, fonts, mapping)
            done += selected.size
            progress(done * 90 / changes.size)
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
        // Line edits can touch forms as well as the page, so the document is rewritten in one pass.
        rewrite(context, input, output, request, fontFiles, progress)

    internal suspend fun rewrite(
        context: Context,
        input: File,
        output: File,
        request: JSONObject,
        fontFiles: Map<String, File>,
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
                .put("engine", "native-lines-3")
                .put("edits", JSONArray())
        val mapping = PdfTextLayer.Mapping()
        try {
            checkFonts(context, changes, fontFiles)
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                val fonts = FontCache(context, pdf, fontFiles)
                val outcomes = rewritePages(pdf, changes, fonts, mapping, progress)
                for (edit in changes) {
                    val outcome = outcomes.firstOrNull { it.index == edit.index }
                    report
                        .getJSONArray("edits")
                        .put(
                            edit.json()
                                .put("actualFont", outcome?.fonts?.joinToString(",").orEmpty())
                                .put("actualHorizontalScale", outcome?.scale ?: 1f)
                                .put("overflow", outcome?.overflow ?: false)
                        )
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
