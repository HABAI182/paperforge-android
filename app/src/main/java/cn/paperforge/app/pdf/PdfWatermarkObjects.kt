package cn.paperforge.app.pdf

import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/**
 * Structural watermark removal. A page's content is read as drawing units (a text object, an XObject placement, a
 * path block, a marked-content sequence, an annotation). Units are scored as watermark candidates from standard
 * tags (`/Artifact /Watermark`, watermark layers and annotations), repetition across pages, rotation, transparency,
 * size, colour and tiling. Removing a unit deletes its drawing operators only; graphics and text state operators stay,
 * so everything after it is drawn exactly as before. Nothing is painted over.
 */
object PdfWatermarkObjects {
    enum class Kind(val label: String) {
        TEXT("文字"),
        IMAGE("图片"),
        FORM("图形组"),
        VECTOR("矢量图形"),
        TAGGED("标记的水印"),
        LAYER("水印图层"),
        ANNOTATION("水印批注"),
        OVERLAY("半透明叠加图片"),
    }

    /** One unit on one page. [box] is in normalised displayed page coordinates. */
    data class Unit(
        val page: Int,
        val ordinal: Int,
        val kind: Kind,
        val signature: String,
        val label: String,
        val box: List<PointN>,
        val start: Int,
        val end: Int,
        val alpha: Float,
        val angle: Float,
        val area: Float,
        val light: Boolean,
        val tagged: Boolean,
        /** Drawn inside an `/Artifact` section (header, footer, background), where word processors put watermarks. */
        val artifact: Boolean = false,
    )

    /** Units sharing one signature: what the user sees and chooses. */
    data class Candidate(
        val id: String,
        val kind: Kind,
        val label: String,
        val units: List<Unit>,
        val score: Float,
        val reasons: List<String>,
        val suggested: Boolean,
    ) {
        val pages
            get() = units.map { it.page }.toSortedSet()
    }

    private val showOps = setOf("Tj", "TJ", "'", "\"")
    private val paintOps = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "sh")
    private val pathOps = setOf("m", "l", "c", "v", "y", "h", "re")
    private val clipOps = setOf("W", "W*")
    private val keywords = listOf("watermark", "水印", "draft", "草稿", "confidential", "机密", "sample", "样本", "copy", "副本")

    /** Marks that scanning and conversion apps stamp on their exports. */
    private val appSignatures =
        listOf(
            Regex("camscanner", RegexOption.IGNORE_CASE),
            Regex("扫描全能王|全能扫描王|扫描王"),
            Regex("adobe ?scan", RegexOption.IGNORE_CASE),
            Regex("(microsoft|office) ?lens", RegexOption.IGNORE_CASE),
            Regex("夸克扫描|夸克|白描|福昕扫描|迅捷|掌上识别|手机扫描"),
            Regex("scanned (with|by)", RegexOption.IGNORE_CASE),
            Regex("(genius|tiny|turbo|fast) ?scan(ner)?|scanbot|notebloc|smallpdf|ilovepdf", RegexOption.IGNORE_CASE),
            Regex("由.{1,12}(创建|生成|扫描)"),
            Regex("evaluation (copy|version)|trial version|未注册|试用版", RegexOption.IGNORE_CASE),
        )

    private fun corner(u: Unit): Boolean =
        u.box.size == 4 && u.area < .04f && (u.box[2].y > .91f || u.box[0].y < .09f)

    /**
     * Joins consecutive translucent vector shapes drawn one after another (a word-processor watermark exported as
     * outlines is one shape per letter) into one unit; units are joined only when nothing else is drawn between them.
     */
    private fun joinShapes(units: List<Unit>): List<Unit> {
        val out = mutableListOf<Unit>()
        for (u in units.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.kind == Kind.VECTOR && u.kind == Kind.VECTOR && last.page == u.page &&
                last.alpha < .95f && abs(last.alpha - u.alpha) < .01f && abs(last.angle - u.angle) < 3f && u.start > last.end
            ) {
                val box = bounds(last.box + u.box)
                out[out.lastIndex] =
                    last.copy(signature = "shapes:" + sha((last.signature + u.signature).toByteArray()), end = u.end, box = box, area = area(box), label = "矢量图形（多个）")
            } else out += u
        }
        return out
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }

    private class GState(
        var ctm: Matrix = Matrix(),
        var fillAlpha: Float = 1f,
        var strokeAlpha: Float = 1f,
        var fill: Float = 0f,
        /** The fill colour is this pattern (`/Pattern cs /P1 scn`), else null. */
        var fillPattern: COSName? = null,
    ) {
        fun copy() = GState(ctm.clone(), fillAlpha, strokeAlpha, fill, fillPattern)
    }

    /** Alpha of an image's soft mask: the most opaque value (0-1) and the share of pixels that show at all. */
    private class Faint(val maxAlpha: Float, val coverage: Float, val images: Int)

    private fun maskStats(image: PDImageXObject): Faint? {
        val mask = runCatching { image.softMask }.getOrNull() ?: return null
        val n = mask.width * mask.height
        if (n <= 0) return null
        val bytes =
            if (mask.bitsPerComponent == 8)
                runCatching { mask.stream.createInputStream().use { it.readBytes() } }.getOrNull()?.takeIf { it.size >= n }
            else null
        var max = 0
        var shown = 0
        if (bytes != null) {
            for (i in 0 until n) {
                val v = bytes[i].toInt() and 255
                if (v > 0) shown++
                if (v > max) max = v
            }
            return Faint(max / 255f, shown.toFloat() / n, 1)
        }
        val bmp = runCatching { mask.image }.getOrNull() ?: return null
        val step = max(1, sqrt(bmp.width * bmp.height / 250_000.0).toInt())
        var seen = 0
        for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) {
            val v = bmp.getPixel(x, y) and 255
            seen++
            if (v > 0) shown++
            if (v > max) max = v
        }
        return Faint(max / 255f, shown.toFloat() / max(1, seen), 1)
    }

    /** A faint overlay: never more than 35 % opaque, or sparse and at most half opaque (text drawn as pixels). */
    private fun Faint.isOverlay() = images > 0 && (maxAlpha <= .35f || coverage < .2f && maxAlpha <= .5f)

    private val fillOps = setOf("f", "F", "f*", "B", "B*", "b", "b*")

    /**
     * What a pattern or form draws, when it draws nothing but masked images (directly, through forms or nested
     * patterns): their combined alpha statistics. Null when it draws anything else (paths, text, opaque images).
     */
    private fun overlayContent(tokens: List<Any>, resources: PDResources?, depth: Int): Faint? {
        if (depth > 3) return null
        var maxAlpha = 0f
        var coverage = 0f
        var images = 0
        var pattern: COSName? = null
        var start = 0
        for ((end, t) in tokens.withIndex()) {
            if (t !is Operator) continue
            val args = tokens.subList(start, end)
            start = end + 1
            val f: Faint? =
                when (t.name) {
                    "scn", "sc" -> { pattern = args.lastOrNull() as? COSName; null }
                    "cs" -> { if ((args.firstOrNull() as? COSName)?.name != "Pattern") pattern = null; null }
                    "Do" ->
                        when (val x = runCatching { resources?.getXObject(args[0] as COSName) }.getOrNull()) {
                            is PDImageXObject -> maskStats(x) ?: return null
                            is PDFormXObject ->
                                overlayContent(PDFStreamParser(x).apply { parse() }.tokens.toList(), x.resources, depth + 1) ?: return null
                            else -> return null
                        }
                    in fillOps -> pattern?.let { patternOverlay(resources, it, depth + 1) } ?: return null
                    in showOps, "S", "s", "sh", "BI" -> return null
                    else -> null
                }
            if (f != null) {
                maxAlpha = max(maxAlpha, f.maxAlpha)
                coverage = max(coverage, f.coverage)
                images += f.images
            }
        }
        return Faint(maxAlpha, coverage, images)
    }

    private fun patternOverlay(resources: PDResources?, name: COSName, depth: Int): Faint? {
        val pattern =
            runCatching { resources?.getPattern(name) }.getOrNull() as? com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
                ?: return null
        val tokens = runCatching { PDFStreamParser(pattern).apply { parse() }.tokens.toList() }.getOrNull() ?: return null
        return overlayContent(tokens, pattern.resources, depth)
    }

    private fun luminance(op: String, values: List<Float>): Float? =
        when {
            op in setOf("g", "G") && values.size == 1 -> values[0]
            op in setOf("rg", "RG", "sc", "scn") && values.size == 3 -> .299f * values[0] + .587f * values[1] + .114f * values[2]
            op in setOf("k", "K") && values.size == 4 -> (1 - values[3]) * (1 - (values[0] + values[1] + values[2]) / 3)
            else -> null
        }

    /** Text quads per root show-operator ordinal (displayed, normalised). */
    private fun textQuads(pdf: PDDocument, number: Int): Map<Int, List<PointN>> {
        val result = mutableMapOf<Int, MutableList<PointN>>()
        val page = pdf.getPage(number)
        val stripper =
            object : PDFTextStripper() {
                var ordinal = 0
                var active = -1
                var form = 0

                override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
                    val nested = operator.name == "Do"
                    val root = operator.name in showOps && active < 0 && form == 0
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
                        result.getOrPut(active) { mutableListOf() }
                            .addAll(PdfGlyphGeometry.displayed(text, page.cropBox, page.rotation))
                }
            }
        stripper.startPage = number + 1
        stripper.endPage = number + 1
        runCatching { stripper.getText(pdf) }
        return result
    }

    private fun displayed(page: PDPage, x: Float, y: Float): PointN {
        val box = page.cropBox
        val n = PointN((x - box.lowerLeftX) / box.width, 1 - (y - box.lowerLeftY) / box.height)
        return when ((page.rotation % 360 + 360) % 360) {
            90 -> PointN(1 - n.y, n.x)
            180 -> PointN(1 - n.x, 1 - n.y)
            270 -> PointN(n.y, 1 - n.x)
            else -> n
        }
    }

    private fun bounds(points: List<PointN>): List<PointN> {
        if (points.isEmpty()) return emptyList()
        val l = points.minOf { it.x }.coerceIn(0f, 1f)
        val r = points.maxOf { it.x }.coerceIn(0f, 1f)
        val t = points.minOf { it.y }.coerceIn(0f, 1f)
        val b = points.maxOf { it.y }.coerceIn(0f, 1f)
        return listOf(PointN(l, t), PointN(r, t), PointN(r, b), PointN(l, b))
    }

    private fun area(box: List<PointN>) = if (box.size < 4) 0f else (box[2].x - box[0].x) * (box[2].y - box[0].y)

    private fun angle(m: Matrix): Float {
        val a = Math.toDegrees(atan2(m.shearY.toDouble(), m.scaleX.toDouble())).toFloat()
        return ((a % 180) + 180) % 180
    }

    private fun decodeText(font: com.tom_roush.pdfbox.pdmodel.font.PDFont?, value: Any?): String {
        if (font == null) return ""
        val items = if (value is COSArray) value.toList() else listOf(value)
        return buildString {
            for (item in items) if (item is COSString) {
                val input = java.io.ByteArrayInputStream(item.bytes)
                while (input.available() > 0) {
                    val left = input.available()
                    val code = runCatching { font.readCode(input) }.getOrNull() ?: break
                    if (input.available() >= left) break
                    append(PdfToUnicode.lookup(font, code) ?: runCatching { font.toUnicode(code) }.getOrNull() ?: "")
                }
            }
        }
    }

    /** Reads the drawing units of one page. */
    fun units(pdf: PDDocument, number: Int): List<Unit> {
        val page = pdf.getPage(number)
        val resources = page.resources ?: PDResources()
        val tokens = PDFStreamParser(page).apply { parse() }.tokens.toList()
        val quads = textQuads(pdf, number)
        val out = mutableListOf<Unit>()
        var g = GState()
        val stack = mutableListOf<GState>()
        var start = 0
        var showOrdinal = 0
        var unitOrdinal = 0
        // Open structures.
        var textStart = -1
        var textBuffer = StringBuilder()
        var textShows = mutableListOf<Int>()
        var textMatrix = Matrix()
        var textFont: com.tom_roush.pdfbox.pdmodel.font.PDFont? = null
        var textAlpha = 1f
        var textFill = 0f
        var pathStart = -1
        val pathPoints = mutableListOf<PointN>()
        var current = PointN(0f, 0f)
        data class Marked(val start: Int, val tag: String, val watermark: Boolean, val layer: String?)
        val marked = mutableListOf<Marked>()
        var pathClips = false

        fun emit(kind: Kind, signature: String, label: String, box: List<PointN>, s: Int, e: Int, alpha: Float, angle: Float, light: Boolean, tagged: Boolean = false) {
            val artifact = marked.any { it.tag == "Artifact" && !it.watermark }
            out += Unit(number, unitOrdinal++, kind, signature, label, box, s, e, alpha, angle, area(box), light, tagged, artifact)
        }

        for ((end, token) in tokens.withIndex()) {
            if (token !is Operator) continue
            val args = tokens.subList(start, end)
            fun num(i: Int) = (args.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f
            when (val name = token.name) {
                "q" -> stack += g.copy()
                "Q" -> if (stack.isNotEmpty()) g = stack.removeAt(stack.lastIndex)
                "cm" -> g.ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).multiply(g.ctm)
                "gs" -> {
                    val state = runCatching { resources.getExtGState(args[0] as COSName) }.getOrNull()
                    state?.nonStrokingAlphaConstant?.let { g.fillAlpha = it }
                    state?.strokingAlphaConstant?.let { g.strokeAlpha = it }
                }
                "g", "rg", "k", "sc", "scn" -> {
                    g.fillPattern = if (name == "scn" || name == "sc") args.lastOrNull() as? COSName else null
                    luminance(name, args.mapNotNull { (it as? COSNumber)?.floatValue() })?.let { g.fill = 1 - it }
                }
                "cs" -> if ((args.firstOrNull() as? COSName)?.name != "Pattern") g.fillPattern = null
                "BT" -> {
                    textStart = start
                    textBuffer = StringBuilder()
                    textShows = mutableListOf()
                    textMatrix = Matrix()
                }
                "Tm" -> textMatrix = Matrix(num(0), num(1), num(2), num(3), num(4), num(5))
                "Tf" -> textFont = runCatching { resources.getFont(args[0] as COSName) }.getOrNull()
                "ET" -> {
                    if (textStart >= 0 && textShows.isNotEmpty()) {
                        val text = textBuffer.toString().trim()
                        val box = bounds(textShows.flatMap { quads[it].orEmpty() })
                        val m = textMatrix.multiply(g.ctm)
                        if (text.isNotEmpty())
                            emit(Kind.TEXT, "text:" + text.lowercase().filterNot { it.isWhitespace() }, text, box, textStart, end, textAlpha, angle(m), textFill < .45f)
                    }
                    textStart = -1
                }
                in pathOps -> {
                    if (pathStart < 0) pathStart = start
                    val pts = args.mapNotNull { (it as? COSNumber)?.floatValue() }
                    var i = 0
                    while (i + 1 < pts.size) {
                        val p = g.ctm.transformPoint(pts[i], pts[i + 1])
                        pathPoints += displayed(page, p.x, p.y)
                        i += 2
                    }
                    if (name == "re" && pts.size == 4) {
                        val p = g.ctm.transformPoint(pts[0] + pts[2], pts[1] + pts[3])
                        pathPoints += displayed(page, p.x, p.y)
                    }
                }
                in clipOps -> pathClips = true
                in paintOps, "n" -> {
                    // A path that also clips stays: removing the clip would change what is drawn after it.
                    val overlay =
                        if (name in fillOps && g.fillPattern != null && !pathClips && pathStart >= 0)
                            patternOverlay(resources, g.fillPattern!!, 0)?.takeIf { it.isOverlay() }
                        else null
                    if (overlay != null && pathPoints.isNotEmpty()) {
                        // A pattern that only lays faint masked images over the page: a background watermark (CSS
                        // background-image in browser exports, tiled stamp images).
                        val box = bounds(pathPoints)
                        emit(Kind.OVERLAY, "overlay:pattern", "整页背景图案中的半透明图片", box, pathStart, end, overlay.maxAlpha, 0f, true)
                    } else if (name != "n" && !pathClips && pathStart >= 0 && pathPoints.isNotEmpty()) {
                        val box = bounds(pathPoints)
                        val signature = "path:" + sha(tokens.subList(pathStart, end + 1).joinToString(" ") { t -> (t as? COSNumber)?.let { "%.1f".format(it.floatValue()) } ?: t.toString() }.toByteArray())
                        emit(Kind.VECTOR, signature, "矢量图形", box, pathStart, end, min(g.fillAlpha, g.strokeAlpha), angle(g.ctm), g.fill < .45f)
                    }
                    pathStart = -1
                    pathClips = false
                    pathPoints.clear()
                }
                "Do" -> {
                    val key = args.getOrNull(0) as? COSName
                    val obj = key?.let { runCatching { resources.getXObject(it) }.getOrNull() }
                    val corners =
                        if (obj is PDFormXObject) {
                            val b = obj.bBox
                            val m = (obj.matrix ?: Matrix()).multiply(g.ctm)
                            listOf(b.lowerLeftX to b.lowerLeftY, b.upperRightX to b.lowerLeftY, b.upperRightX to b.upperRightY, b.lowerLeftX to b.upperRightY)
                                .map { (x, y) -> m.transformPoint(x, y).let { displayed(page, it.x, it.y) } }
                        } else listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (x, y) -> g.ctm.transformPoint(x, y).let { displayed(page, it.x, it.y) } }
                    val box = bounds(corners)
                    when (obj) {
                        is PDImageXObject -> {
                            val faint = if (area(box) > .2f) maskStats(obj)?.takeIf { it.isOverlay() } else null
                            if (faint != null)
                                emit(Kind.OVERLAY, "overlay:image", "大面积半透明叠加图片", box, start, end, faint.maxAlpha * g.fillAlpha, angle(g.ctm), true)
                            else {
                                val raw = runCatching { obj.cosObject.createRawInputStream().use { it.readBytes() } }.getOrDefault(ByteArray(0))
                                emit(Kind.IMAGE, "image:" + sha(raw), "图片 ${obj.width}×${obj.height}", box, start, end, g.fillAlpha, angle(g.ctm), false)
                            }
                        }
                        is PDFormXObject -> {
                            val raw = runCatching { obj.cosObject.createRawInputStream().use { it.readBytes() } }.getOrDefault(ByteArray(0))
                            val text = runCatching { formText(obj) }.getOrDefault("")
                            val tagged = obj.cosObject.getCOSDictionary(COSName.getPDFName("PieceInfo"))?.containsKey(COSName.getPDFName("ADBE_CompoundType")) == true
                            emit(Kind.FORM, "form:" + sha(raw), text.ifBlank { "图形组" }, box, start, end, g.fillAlpha, angle(g.ctm), false, tagged)
                        }
                    }
                }
                "BMC", "BDC" -> {
                    val tag = (args.getOrNull(0) as? COSName)?.name.orEmpty()
                    val props = args.getOrNull(1).let { v ->
                        if (v is COSDictionary) v else if (v is COSName) runCatching { resources.getProperties(v)?.cosObject }.getOrNull() else null
                    }
                    val subtype = props?.getNameAsString(COSName.SUBTYPE).orEmpty()
                    val layer =
                        if (tag == "OC") (props?.getString(COSName.NAME) ?: props?.getDictionaryObject(COSName.NAME)?.toString()) else null
                    marked += Marked(start, tag, tag == "Artifact" && subtype.equals("Watermark", true), layer)
                }
                "EMC" -> {
                    val m = marked.removeLastOrNull()
                    if (m != null) {
                        val inside = out.filter { it.start >= m.start && it.end <= end }
                        val box = bounds(inside.flatMap { it.box })
                        val label = inside.firstOrNull { it.kind == Kind.TEXT }?.label ?: inside.firstOrNull()?.label ?: ""
                        if (m.watermark)
                            emit(Kind.TAGGED, "tagged:" + inside.joinToString("|") { it.signature }.let { sha(it.toByteArray()) }, label.ifEmpty { "Acrobat 水印" }, box, m.start, end, inside.minOfOrNull { it.alpha } ?: 1f, inside.firstOrNull()?.angle ?: 0f, false, true)
                        else if (m.layer != null && keywords.any { m.layer.contains(it, true) })
                            emit(Kind.LAYER, "layer:" + m.layer, "图层「${m.layer}」", box, m.start, end, 1f, 0f, false, true)
                    }
                }
            }
            if (token.name in showOps) {
                textShows += showOrdinal
                textAlpha = g.fillAlpha
                textFill = g.fill
                textBuffer.append(decodeText(textFont, args.lastOrNull()))
                showOrdinal++
            }
            start = end + 1
        }
        // Annotations marked as watermarks.
        page.annotations.forEachIndexed { i, a ->
            if (a.subtype == "Watermark") {
                val r = a.rectangle
                val box = bounds(listOf(displayed(page, r.lowerLeftX, r.lowerLeftY), displayed(page, r.upperRightX, r.upperRightY)))
                emit(Kind.ANNOTATION, "annot:Watermark", "水印批注", box, -1 - i, -1 - i, 1f, 0f, false, true)
            }
        }
        return out
    }

    /** Text drawn by a form, following nested forms (a placed page is often a form inside a form). */
    private fun formText(form: PDFormXObject, depth: Int = 0): String {
        val tokens = PDFStreamParser(form).apply { parse() }.tokens
        var font: com.tom_roush.pdfbox.pdmodel.font.PDFont? = null
        var start = 0
        return buildString {
            for ((end, t) in tokens.withIndex()) {
                if (t !is Operator) continue
                val args = tokens.subList(start, end)
                if (t.name == "Tf") font = runCatching { form.resources?.getFont(args[0] as COSName) }.getOrNull()
                if (t.name in showOps) append(decodeText(font, args.lastOrNull()))
                if (t.name == "Do" && depth < 3) {
                    val inner = runCatching { form.resources?.getXObject(args[0] as COSName) }.getOrNull()
                    if (inner is PDFormXObject) append(runCatching { formText(inner, depth + 1) }.getOrDefault(""))
                }
                start = end + 1
                if (length > 60) break
            }
        }.trim()
    }

    /** Groups units into candidates and scores them. */
    fun candidates(pdf: PDDocument, pageUnits: List<List<Unit>>): List<Candidate> {
        val pages = pdf.numberOfPages
        val all = pageUnits.flatMap { joinShapes(it) }
        val producer = runCatching { pdf.documentInformation?.producer.orEmpty() + " " + pdf.documentInformation?.creator.orEmpty() }.getOrDefault("")
        val scanApp = producer.contains("intsig", true) || appSignatures.any { it.containsMatchIn(producer) }
        // Corner text carrying a scanning app's name, per page, for the logo images beside it.
        val stamps = all.filter { it.kind == Kind.TEXT && corner(it) && appSignatures.any { r -> r.containsMatchIn(it.label) } }
        // Units inside a tagged watermark or layer are represented by that container.
        val containers = all.filter { it.kind in setOf(Kind.TAGGED, Kind.LAYER) }
        val loose = all.filter { u -> u.kind in setOf(Kind.TAGGED, Kind.LAYER, Kind.ANNOTATION) || containers.none { c -> c.page == u.page && u.start >= c.start && u.end <= c.end && c !== u } }
        return loose
            .groupBy { it.signature }
            .map { (signature, units) ->
                val first = units.first()
                val pageSet = units.map { it.page }.toSet()
                val perPage = units.groupBy { it.page }.values.maxOf { it.size }
                val reasons = mutableListOf<String>()
                var evidence = 0f
                var score = 0f
                if (first.tagged || first.kind == Kind.ANNOTATION) {
                    score += 1f
                    reasons += when (first.kind) {
                        Kind.TAGGED -> "PDF 标记为水印（Artifact/Watermark）"
                        Kind.LAYER -> "位于水印图层"
                        Kind.ANNOTATION -> "水印批注"
                        else -> "生成软件标记为水印"
                    }
                    evidence += 1f
                }
                val repeated = pages > 1 && pageSet.size >= max(2, ceil(pages * .6).toInt())
                if (repeated) {
                    score += .35f
                    reasons += "出现在 ${pageSet.size}/$pages 页"
                }
                val rotated = units.any { it.angle in 15f..75f || it.angle in 105f..165f }
                if (rotated) {
                    score += .3f
                    evidence += 1
                    reasons += "斜向放置"
                }
                val transparent = units.any { it.alpha < .95f }
                if (transparent) {
                    score += .3f
                    evidence += 1
                    reasons += "半透明"
                }
                if (perPage >= 3 && first.kind != Kind.VECTOR) {
                    score += .3f
                    evidence += 1
                    reasons += "同页平铺 $perPage 次"
                }
                val large = units.any { it.area > .12f || (it.box.size == 4 && it.box[2].x - it.box[0].x > .5f && first.kind == Kind.TEXT) }
                if (large && first.kind in setOf(Kind.TEXT, Kind.FORM, Kind.IMAGE)) {
                    score += .1f
                    reasons += "跨越正文区域"
                }
                if (first.light && first.kind == Kind.TEXT) {
                    score += .1f
                    reasons += "浅色"
                }
                val keyword = keywords.any { first.label.contains(it, true) }
                if (keyword) {
                    score += .15f
                    reasons += "含水印常用词"
                }
                // A scanning app's stamp: its name in a page corner, and the small logo next to it.
                val signed = first.kind == Kind.TEXT && appSignatures.any { it.containsMatchIn(first.label) }
                if (signed && units.all(::corner)) {
                    score += 1f
                    evidence += 1
                    reasons += "扫描软件在页角加的标记"
                } else if (signed) {
                    score += .4f
                    evidence += 1
                    reasons += "扫描软件名称"
                }
                val logo =
                    first.kind == Kind.IMAGE && units.all { u ->
                        corner(u) && stamps.any { t -> t.page == u.page && abs(t.box[0].x - u.box[2].x) < .2f && abs(t.box[0].y - u.box[0].y) < .06f }
                    }
                if (logo) {
                    score += 1f
                    evidence += 1
                    reasons += "扫描软件标记旁的徽标"
                }
                if (!signed && !logo && scanApp && units.all(::corner) && first.kind in setOf(Kind.TEXT, Kind.IMAGE)) {
                    score += .5f
                    evidence += 1
                    reasons += "扫描软件导出的页角标记"
                }
                // Faint masked images laid over (or under) the whole page, directly or through a tiling pattern.
                if (first.kind == Kind.OVERLAY) {
                    score += 1f
                    evidence += 1
                    reasons += "覆盖整页的半透明图片（最不透明处 ${(units.maxOf { it.alpha } * 100).roundToInt()}%），常见于网页导出的水印"
                }
                // Word and WPS draw their watermark inside the page header: a large pale or translucent figure that
                // reaches well into the body. A header logo stays near the top edge and is small.
                val headerFigure =
                    first.kind != Kind.TAGGED && units.all { u ->
                        u.artifact && (u.alpha < .95f || u.light) && u.area > .03f && u.box.size == 4 &&
                            (u.box[0].y + u.box[2].y) / 2 in 0.25f..0.75f && u.box[2].y - u.box[0].y > .1f
                    }
                if (headerFigure) {
                    score += .5f
                    evidence += 1
                    reasons += "页眉中的浅色大图形（Word/WPS 水印）"
                }
                // Ordinary page furniture: full-page images (scans), horizontal opaque body text, page numbers.
                val fullPageImage = first.kind == Kind.IMAGE && units.all { it.area > .8f }
                if (fullPageImage) score = 0f
                val suggested = score >= .6f && evidence >= 1f && !fullPageImage
                // Repetition alone (headers, footers, table rules, logos) does not make a candidate.
                val listed = score >= .35f && (evidence >= 1f || keyword) && !fullPageImage
                if (!listed) null
                else
                    Candidate(
                        signature,
                        first.kind,
                        first.label.take(40),
                        units,
                        score,
                        reasons,
                        suggested,
                    )
            }
            .filterNotNull()
            .sortedByDescending { it.score }
    }

    data class Scan(val candidates: List<Candidate>, val pages: Int, val encrypted: Boolean, val canModify: Boolean)

    fun scan(input: File): Scan =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            val canModify = !pdf.isEncrypted || pdf.currentAccessPermission.canModify()
            val units = (0 until pdf.numberOfPages).map { runCatching { units(pdf, it) }.getOrDefault(emptyList()) }
            Scan(candidates(pdf, units), pdf.numberOfPages, pdf.isEncrypted, canModify)
        }

    private fun neutralize(tokens: List<Any>, from: Int, to: Int): List<Any> {
        // Keep state operators; drop what draws (text showing, XObjects, paths, shadings, inline images).
        val out = mutableListOf<Any>()
        var start = from
        var i = from
        while (i <= to) {
            val t = tokens[i]
            if (t is Operator) {
                val args = tokens.subList(start, i)
                val drop =
                    t.name in showOps || t.name == "Do" || t.name in paintOps || t.name in pathOps || t.name in clipOps ||
                        t.name == "n" || t.name == "BI" || t.name == "BDC" || t.name == "BMC" || t.name == "EMC"
                if (!drop) {
                    out.addAll(args)
                    out += t
                } else if (t.name == "\"") {
                    // Keeps the word/char spacing the operator sets.
                    out += args[0]
                    out += Operator.getOperator("Tw")
                    out += args[1]
                    out += Operator.getOperator("Tc")
                    out += Operator.getOperator("T*")
                } else if (t.name == "'") out += Operator.getOperator("T*")
                start = i + 1
            }
            i++
        }
        return out
    }

    data class Removal(val units: Int, val pages: Int)

    /** Removes the [chosen] units in place in [pdf] (an opened copy). */
    fun remove(pdf: PDDocument, chosen: List<Unit>): Removal {
        var count = 0
        for ((number, list) in chosen.groupBy { it.page }) {
            val page = pdf.getPage(number)
            val tokens = PDFStreamParser(page).apply { parse() }.tokens.toList()
            // Re-read the page so ranges match this document exactly, grouped as candidates() groups them: a
            // watermark exported as one outline per letter is chosen as one joined unit.
            val current = units(pdf, number).let { raw -> raw + joinShapes(raw).filter { j -> raw.none { it === j } } }
            val ranges =
                list.mapNotNull { want -> current.firstOrNull { it.ordinal == want.ordinal && it.signature == want.signature } }
            val annotations = ranges.filter { it.kind == Kind.ANNOTATION }
            if (annotations.isNotEmpty()) {
                val keep = page.annotations.filterIndexed { i, _ -> annotations.none { it.start == -1 - i } }
                page.annotations = keep
                count += annotations.size
            }
            val streams = ranges.filter { it.start >= 0 }.sortedBy { it.start }
            if (streams.isEmpty()) continue
            // Outer ranges only (a tagged sequence includes its inner units).
            val outer = streams.filter { r -> streams.none { o -> o !== r && o.start <= r.start && o.end >= r.end && (o.start != r.start || o.end != r.end) } }
            val written = mutableListOf<Any>()
            var cursor = 0
            for (r in outer) {
                if (r.start < cursor) continue
                written.addAll(tokens.subList(cursor, r.start))
                written.addAll(neutralize(tokens, r.start, r.end))
                cursor = r.end + 1
                count++
            }
            written.addAll(tokens.subList(cursor, tokens.size))
            val stream = PDStream(pdf)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(written) }
            page.setContents(stream)
        }
        return Removal(count, chosen.map { it.page }.toSet().size)
    }

    /** Writes a copy of [input] without the units of [candidateIds] on [pages] (null: all pages). */
    fun apply(input: File, output: File, candidateIds: Set<String>, pages: Set<Int>?): Removal {
        require(input.canonicalFile != output.canonicalFile) { "输出必须是新文件" }
        val part = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                require(!pdf.isEncrypted || pdf.currentAccessPermission.canModify()) { "此 PDF 设置了禁止修改的权限，不能去除水印" }
                val units = (0 until pdf.numberOfPages).map { units(pdf, it) }
                val chosen =
                    candidates(pdf, units).filter { it.id in candidateIds }.flatMap { it.units }.filter { pages == null || it.page in pages }
                val result = remove(pdf, chosen)
                if (pdf.isEncrypted) pdf.isAllSecurityToBeRemoved = false
                pdf.save(part)
                check(part.renameTo(output)) { "无法保存副本" }
                return result
            }
        } finally {
            part.delete()
        }
    }

    /** One page of [input] with the chosen candidates removed, for preview. */
    fun preview(input: File, output: File, page: Int, candidateIds: Set<String>): Removal =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            val units = (0 until pdf.numberOfPages).map { if (it == page) units(pdf, it) else runCatching { units(pdf, it) }.getOrDefault(emptyList()) }
            val chosen = candidates(pdf, units).filter { it.id in candidateIds }.flatMap { it.units }.filter { it.page == page }
            val result = remove(pdf, chosen)
            PDDocument().use { out ->
                out.importPage(pdf.getPage(page))
                out.save(output)
            }
            result
        }
}
