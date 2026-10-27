package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.*

/**
 * Editable visible text, pictures and basic paths. Unsupported page effects retain actual
 * appearance.
 */
object NativeSlides {
    private data class P(val x: Double, val y: Double) {
        operator fun plus(p: P) = P(x + p.x, y + p.y)

        operator fun minus(p: P) = P(x - p.x, y - p.y)

        operator fun times(v: Double) = P(x * v, y * v)

        fun length() = hypot(x, y)
    }

    private data class Style(
        val font: String,
        val points: Double,
        val bold: Boolean,
        val italic: Boolean,
        val rgb: Int,
        val alpha: Double,
    )

    private data class Run(
        val style: Style,
        val angle: Double,
        val direction: P,
        val origin: P,
        var end: P,
        val points: MutableList<P>,
        val text: StringBuilder,
    )

    private fun number(v: Double) = v.roundToLong()

    private fun color(rgb: Int, alpha: Double) =
        "<a:solidFill><a:srgbClr val=\"${"%06X".format(Locale.ROOT,rgb and 0xffffff)}\"><a:alpha val=\"${(alpha*100000).roundToInt().coerceIn(0,100000)}\"/></a:srgbClr></a:solidFill>"

    private fun xfrm(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        angle: Double = 0.0,
        flip: Boolean = false,
    ) =
        "<a:xfrm rot=\"${(angle*180/PI*60000).roundToLong()}\" flipV=\"${if(flip)1 else 0}\"><a:off x=\"${number(x)}\" y=\"${number(y)}\"/><a:ext cx=\"${number(w).coerceAtLeast(1)}\" cy=\"${number(h).coerceAtLeast(1)}\"/></a:xfrm>"

    private fun picture(id: Int, relation: Int, corners: List<P>): String {
        val center = corners.reduce { a, b -> a + b } * .25
        val u = corners[1] - corners[0]
        val v = corners[3] - corners[0]
        val w = u.length()
        val h = v.length()
        val angle = atan2(u.y, u.x)
        val flip = u.x * v.y - u.y * v.x < 0
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"image$relation\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>${xfrm(center.x-w/2,center.y-h/2,w,h,angle,flip)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
    }

    private class PageObjects(
        page: PDPage,
        val canvasWidth: Long,
        val canvasHeight: Long,
        val scratch: File,
    ) : PDFGraphicsStreamEngine(page) {
        val unsupported = linkedSetOf<String>()
        var canSimplify = true
        val shapes = mutableListOf<String>()
        val images = mutableListOf<File>()
        val fontMap = linkedMapOf<String, String>()
        var textBoxes = 0
        var paths = 0
        var invisible = 0
        private var current = PointF()
        private var start = PointF()
        private val commands = mutableListOf<Pair<String, List<P>>>()
        private var pending: Run? = null
        private val rotation = (page.rotation % 360 + 360) % 360
        private val box = page.cropBox
        private val unit = page.userUnit.toDouble()
        val width = (if (rotation in setOf(90, 270)) box.height else box.width) * unit
        val height = (if (rotation in setOf(90, 270)) box.width else box.height) * unit
        private val scale = min(canvasWidth / 12700.0 / width, canvasHeight / 12700.0 / height)
        private val fitW = width * scale * 12700
        private val fitH = height * scale * 12700
        private val offX = (canvasWidth - fitW) / 2
        private val offY = (canvasHeight - fitH) / 2

        private fun p(x: Float, y: Float): P {
            val u = (x - box.lowerLeftX) / box.width
            val v = 1 - (y - box.lowerLeftY) / box.height
            val q =
                when (rotation) {
                    90 -> P(1 - v.toDouble(), u.toDouble())
                    180 -> P(1 - u.toDouble(), 1 - v.toDouble())
                    270 -> P(v.toDouble(), 1 - u.toDouble())
                    else -> P(u.toDouble(), v.toDouble())
                }
            return P(offX + q.x * fitW, offY + q.y * fitH)
        }

        private fun p(q: PointF) = p(q.x, q.y)

        private fun cropped(points: List<P>) {
            val tolerance = 6350.0
            if (
                points.any {
                    it.x < offX - tolerance ||
                        it.x > offX + fitW + tolerance ||
                        it.y < offY - tolerance ||
                        it.y > offY + fitH + tolerance
                }
            )
                unsupported += "跨越裁边的对象"
        }

        private fun effects() {
            if (graphicsState.softMask != null || graphicsState.blendMode != BlendMode.NORMAL)
                unsupported += "混合或软遮罩"
        }

        private fun face(name: String): String {
            val clean = name.substringAfter('+')
            val lower = clean.lowercase(Locale.ROOT)
            val target =
                when {
                    "noto" in lower && ("sc" in lower || "cjk" in lower) -> "Noto Sans CJK SC"
                    "serif" in lower || "times" in lower -> "Liberation Serif"
                    "mono" in lower || "courier" in lower -> "Liberation Mono"
                    else -> "Liberation Sans"
                }
            fontMap[clean] = target
            return target
        }

        override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            effects()
            val mode = graphicsState.textState.renderingMode.intValue()
            if (mode == 3 || graphicsState.nonStrokeAlphaConstant == 0.0) {
                invisible++
                return
            }
            if (mode != 0) unsupported += "文字描边或裁剪"
            if (font.isVertical || font.subType == "Type3") {
                canSimplify = false
                unsupported += "竖排或 Type3 字体"
                return
            }
            val text = font.toUnicode(code)
            if (
                text.isNullOrEmpty() ||
                    '\uFFFD' in text ||
                    text.any { Character.getType(it) == Character.PRIVATE_USE.toInt() }
            ) {
                canSimplify = false
                unsupported += "缺失的文字映射"
                return
            }
            if (
                text.codePoints().anyMatch { cp ->
                    Character.UnicodeScript.of(cp) in
                        setOf(
                            Character.UnicodeScript.ARABIC,
                            Character.UnicodeScript.DEVANAGARI,
                            Character.UnicodeScript.BENGALI,
                            Character.UnicodeScript.THAI,
                        )
                }
            )
                unsupported += "复杂文字整形"
            val descriptor = font.fontDescriptor
            val ascent = (descriptor?.ascent?.div(1000f) ?: .9f).coerceAtLeast(.1f)
            val descent = (descriptor?.descent?.div(1000f) ?: -.2f).coerceAtMost(0f)
            val a = p(matrix.transformPoint(0f, 0f))
            val b = p(matrix.transformPoint(displacement.x, displacement.y))
            val u = b - a
            val length = u.length()
            if (length < .0001) {
                canSimplify = false
                unsupported += "零宽复合字形"
                return
            }
            val direction = u * (1.0 / length)
            val angle = atan2(u.y, u.x)
            val points =
                listOf(
                    p(matrix.transformPoint(0f, descent)),
                    p(matrix.transformPoint(displacement.x, descent + displacement.y)),
                    p(matrix.transformPoint(displacement.x, ascent + displacement.y)),
                    p(matrix.transformPoint(0f, ascent)),
                )
            val vertical = points[3] - points[0]
            cropped(points)
            if (abs(direction.x * vertical.x + direction.y * vertical.y) > vertical.length() * .015)
                unsupported += "斜切文字变换"
            val fontPoints =
                hypot(matrix.shearX.toDouble(), matrix.scaleY.toDouble()) * unit * scale
            val name = font.name.orEmpty()
            val lower = name.lowercase(Locale.ROOT)
            val rgb =
                try {
                    graphicsState.nonStrokingColor.toRGB()
                } catch (_: Exception) {
                    unsupported += "复杂文字颜色"
                    0
                }
            val style =
                Style(
                    face(name),
                    fontPoints,
                    "bold" in lower || "black" in lower,
                    "italic" in lower || "oblique" in lower,
                    rgb,
                    graphicsState.nonStrokeAlphaConstant,
                )
            val old = pending
            val gap = if (old == null) P(0.0, 0.0) else a - old.end
            val along = gap.x * direction.x + gap.y * direction.y
            val across = abs(gap.x * direction.y - gap.y * direction.x)
            val em = fontPoints * 12700
            if (
                old == null ||
                    old.style != style ||
                    abs(old.angle - angle) > .01 ||
                    across > em * .15 ||
                    along < -em * .25 ||
                    along > em * 1.5
            ) {
                flush()
                pending =
                    Run(style, angle, direction, a, b, points.toMutableList(), StringBuilder(text))
            } else {
                if (
                    along > em * .22 &&
                        !old.text.last().isWhitespace() &&
                        !text.first().isWhitespace()
                )
                    old.text.append(' ')
                old.text.append(text)
                old.points.addAll(points)
                old.end = b
            }
        }

        fun flush() {
            val r = pending ?: return
            pending = null
            val u = r.direction
            val v = P(-u.y, u.x)
            val xs = r.points.map { it.x * u.x + it.y * u.y }
            val w = xs.max() - xs.min()
            // DrawingML lays the first line from its em box. PDF font descriptors describe
            // glyph extents instead; using their ascent as the frame top shifts the baseline.
            val em = r.style.points * 12700
            val h = em * 1.25
            val center = r.origin + u * (w / 2) + v * (h / 2 - em)
            val id = shapes.size + 2
            val props =
                "sz=\"${(r.style.points*100).roundToInt().coerceAtLeast(100)}\" b=\"${if(r.style.bold)1 else 0}\" i=\"${if(r.style.italic)1 else 0}\""
            shapes +=
                "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Editable text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(center.x-w/2,center.y-h/2,w,h,r.angle)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" wrap=\"none\" anchor=\"t\" vertOverflow=\"overflow\"><a:noAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr marL=\"0\" indent=\"0\"><a:buNone/></a:pPr><a:r><a:rPr $props>${color(r.style.rgb,r.style.alpha)}<a:latin typeface=\"${SlidesPackage.xml(r.style.font)}\"/><a:ea typeface=\"Noto Sans CJK SC\"/></a:rPr><a:t xml:space=\"preserve\">${SlidesPackage.xml(r.text.toString())}</a:t></a:r><a:endParaRPr $props/></a:p></p:txBody></p:sp>"
            textBoxes++
        }

        private fun paint(
            fill: Boolean,
            stroke: Boolean,
            winding: Path.FillType = Path.FillType.WINDING,
        ) {
            flush()
            effects()
            if (commands.isEmpty()) return
            if (winding == Path.FillType.EVEN_ODD && commands.count { it.first == "M" } > 1)
                unsupported += "复合奇偶填充"
            if (stroke && graphicsState.lineDashPattern.dashArray.isNotEmpty())
                unsupported += "虚线笔画"
            val points = commands.flatMap { it.second }
            val left = points.minOf { it.x }
            cropped(points)
            val top = points.minOf { it.y }
            val w = (points.maxOf { it.x } - left).coerceAtLeast(1.0)
            val h = (points.maxOf { it.y } - top).coerceAtLeast(1.0)
            fun point(p: P) = "<a:pt x=\"${number(p.x-left)}\" y=\"${number(p.y-top)}\"/>"
            val path =
                commands.joinToString("") { (kind, ps) ->
                    when (kind) {
                        "M" -> "<a:moveTo>${point(ps[0])}</a:moveTo>"
                        "L" -> "<a:lnTo>${point(ps[0])}</a:lnTo>"
                        "C" -> "<a:cubicBezTo>${ps.joinToString(""){point(it)}}</a:cubicBezTo>"
                        else -> "<a:close/>"
                    }
                }
            val fillXml =
                if (fill)
                    color(
                        graphicsState.nonStrokingColor.toRGB(),
                        graphicsState.nonStrokeAlphaConstant,
                    )
                else "<a:noFill/>"
            val ctm = graphicsState.currentTransformationMatrix
            val lineWidth =
                graphicsState.lineWidth *
                    hypot(ctm.scaleX.toDouble(), ctm.shearY.toDouble()) *
                    unit *
                    scale *
                    12700
            val lineXml =
                if (stroke)
                    "<a:ln w=\"${number(lineWidth).coerceAtLeast(1)}\">${color(graphicsState.strokingColor.toRGB(),graphicsState.alphaConstant)}<a:prstDash val=\"solid\"/></a:ln>"
                else "<a:ln><a:noFill/></a:ln>"
            val id = shapes.size + 2
            shapes +=
                "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Editable path $id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(left,top,w,h)}<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"${number(w)}\" h=\"${number(h)}\" fill=\"${if(fill) "norm" else "none"}\" stroke=\"${if(stroke)1 else 0}\">$path</a:path></a:pathLst></a:custGeom>$fillXml$lineXml</p:spPr></p:sp>"
            paths++
            commands.clear()
        }

        override fun drawImage(image: PDImage) {
            flush()
            effects()
            if (graphicsState.nonStrokeAlphaConstant < .999) unsupported += "透明图片"
            val m = graphicsState.currentTransformationMatrix
            val corners =
                listOf(
                    p(m.transformPoint(0f, 1f)),
                    p(m.transformPoint(1f, 1f)),
                    p(m.transformPoint(1f, 0f)),
                    p(m.transformPoint(0f, 0f)),
                )
            val u = corners[1] - corners[0]
            val v = corners[3] - corners[0]
            cropped(corners)
            if (abs(u.x * v.x + u.y * v.y) > u.length() * v.length() * .015) unsupported += "斜切图片"
            val bitmap = image.image?.copy(Bitmap.Config.ARGB_8888, false) ?: return
            val file = File(scratch, "${UUID.randomUUID()}.png")
            try {
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            images += file
            shapes += picture(shapes.size + 2, images.size, corners)
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            flush()
            commands += "M" to listOf(p(p0))
            commands += "L" to listOf(p(p1))
            commands += "L" to listOf(p(p2))
            commands += "L" to listOf(p(p3))
            commands += "Z" to emptyList()
            current = p0
            start = p0
        }

        override fun clip(windingRule: Path.FillType) {
            flush()
            val points = commands.flatMap { it.second }
            val full =
                points.isNotEmpty() &&
                    points.minOf { it.x } <= offX + 1 &&
                    points.minOf { it.y } <= offY + 1 &&
                    points.maxOf { it.x } >= offX + fitW - 1 &&
                    points.maxOf { it.y } >= offY + fitH - 1 &&
                    commands.count { it.first == "L" } == 3
            if (!full) unsupported += "局部裁剪"
        }

        override fun moveTo(x: Float, y: Float) {
            flush()
            current = PointF(x, y)
            start = current
            commands += "M" to listOf(p(x, y))
        }

        override fun lineTo(x: Float, y: Float) {
            current = PointF(x, y)
            commands += "L" to listOf(p(x, y))
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = PointF(x3, y3)
            commands += "C" to listOf(p(x1, y1), p(x2, y2), p(x3, y3))
        }

        override fun getCurrentPoint() = current

        override fun closePath() {
            commands += "Z" to emptyList()
            current = start
        }

        override fun endPath() {
            commands.clear()
        }

        override fun strokePath() = paint(false, true)

        override fun fillPath(windingRule: Path.FillType) = paint(true, false, windingRule)

        override fun fillAndStrokePath(windingRule: Path.FillType) = paint(true, true, windingRule)

        override fun shadingFill(shadingName: COSName) {
            flush()
            unsupported += "渐变或网格着色"
        }
    }

    suspend fun convert(
        context: Context,
        input: File,
        output: File,
        preferEditable: Boolean = false,
        progress: suspend (Int) -> Unit = {},
    ): JSONObject {
        val part = File(output.path + ".part")
        val scratch =
            File(context.cacheDir, "native-slides/${UUID.randomUUID()}").apply { mkdirs() }
        val report = JSONArray()
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                require(pdf.numberOfPages > 0)
                val first = pdf.getPage(0)
                val rotated = first.rotation % 180 != 0
                val width =
                    ((if (rotated) first.cropBox.height else first.cropBox.width) *
                            first.userUnit *
                            12700)
                        .roundToLong()
                val height =
                    ((if (rotated) first.cropBox.width else first.cropBox.height) *
                            first.userUnit *
                            12700)
                        .roundToLong()
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        ZipOutputStream(part.outputStream().buffered()).use { zip ->
                            SlidesPackage.header(context, zip, pdf.numberOfPages, width, height)
                            for (index in 0 until pdf.numberOfPages) {
                                currentCoroutineContext().ensureActive()
                                val objects =
                                    PageObjects(pdf.getPage(index), width, height, scratch)
                                var parsed = true
                                try {
                                    objects.processPage(pdf.getPage(index))
                                    objects.flush()
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    parsed = false
                                    objects.unsupported += failure.message ?: "复杂页面内容"
                                }
                                val editable =
                                    objects.unsupported.isEmpty() ||
                                        (preferEditable && parsed && objects.canSimplify)
                                val mode =
                                    if (editable) {
                                        if (objects.unsupported.isEmpty()) "editable-objects"
                                        else "editable-simplified"
                                    } else "page-appearance"
                                val names = mutableListOf<String>()
                                val body =
                                    if (editable) {
                                        objects.images.forEachIndexed { n, file ->
                                            val name = "page${index+1}-image${n+1}.png"
                                            names += name
                                            zip.putNextEntry(ZipEntry("ppt/media/$name"))
                                            file.inputStream().use { it.copyTo(zip) }
                                            zip.closeEntry()
                                        }
                                        objects.shapes.joinToString("")
                                    } else {
                                        val name = "page${index+1}-appearance.png"
                                        names += name
                                        val page = renderer.openPage(index)
                                        try {
                                            val scale = 2200f / maxOf(page.width, page.height)
                                            val bitmap =
                                                Bitmap.createBitmap(
                                                    (page.width * scale).toInt().coerceAtLeast(1),
                                                    (page.height * scale).toInt().coerceAtLeast(1),
                                                    Bitmap.Config.ARGB_8888,
                                                )
                                            try {
                                                bitmap.eraseColor(Color.WHITE)
                                                page.render(
                                                    bitmap,
                                                    null,
                                                    null,
                                                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                                                )
                                                zip.putNextEntry(ZipEntry("ppt/media/$name"))
                                                check(
                                                    bitmap.compress(
                                                        Bitmap.CompressFormat.PNG,
                                                        100,
                                                        zip,
                                                    )
                                                )
                                                zip.closeEntry()
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        } finally {
                                            page.close()
                                        }
                                        val fit =
                                            min(width / objects.width, height / objects.height)
                                        val w = objects.width * fit
                                        val h = objects.height * fit
                                        val x = (width - w) / 2
                                        val y = (height - h) / 2
                                        picture(
                                            2,
                                            1,
                                            listOf(
                                                P(x, y),
                                                P(x + w, y),
                                                P(x + w, y + h),
                                                P(x, y + h),
                                            ),
                                        )
                                    }
                                SlidesPackage.text(
                                    zip,
                                    "ppt/slides/slide${index+1}.xml",
                                    SlidesPackage.slide(body),
                                )
                                SlidesPackage.text(
                                    zip,
                                    "ppt/slides/_rels/slide${index+1}.xml.rels",
                                    SlidesPackage.relationships(names),
                                )
                                report.put(
                                    JSONObject()
                                        .put("page", index + 1)
                                        .put("mode", mode)
                                        .put("textBoxes", if (editable) objects.textBoxes else 0)
                                        .put("paths", if (editable) objects.paths else 0)
                                        .put("pictures", names.size)
                                        .put(
                                            "appearanceReasons",
                                            JSONArray(objects.unsupported.toList()),
                                        )
                                        .put("fontMap", JSONObject(objects.fontMap))
                                        .put("invisibleTextSkipped", objects.invisible)
                                )
                                objects.images.forEach { it.delete() }
                                progress((index + 1) * 100 / pdf.numberOfPages)
                            }
                            val summary =
                                JSONObject()
                                    .put("format", 1)
                                    .put("mode", "editable-visible-objects")
                                    .put("preferEditable", preferEditable)
                                    .put(
                                        "detail",
                                        if (preferEditable)
                                            "优先保留可编辑对象，复杂剪裁或遮罩可能被简化；未完整解码的页保留整页外观。扫描图仍为图片，字体以本机字体近似。"
                                        else
                                            "可见原生文字、图片和基础路径作为对象；复杂页保留整页外观，扫描图仍为图片。字体以本机可用字体近似。原件可随时对照。",
                                    )
                                    .put("pages", report)
                            SlidesPackage.text(
                                zip,
                                "paperforge-conversion.json",
                                summary.toString(2),
                            )
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(part.renameTo(output))
            return JSONObject()
                .put("mode", "可编辑内容 PPTX")
                .put(
                    "editablePages",
                    (0 until report.length()).count {
                        report.getJSONObject(it).getString("mode") == "editable-objects"
                    },
                )
                .put(
                    "appearancePages",
                    (0 until report.length()).count {
                        report.getJSONObject(it).getString("mode") == "page-appearance"
                    },
                )
                .put(
                    "simplifiedPages",
                    (0 until report.length()).count {
                        report.getJSONObject(it).getString("mode") == "editable-simplified"
                    },
                )
                .put("pages", report)
        } finally {
            part.delete()
            scratch.listFiles()?.forEach { it.delete() }
            scratch.delete()
        }
    }
}
