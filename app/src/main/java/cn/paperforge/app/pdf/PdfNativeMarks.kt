package cn.paperforge.app.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.*
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.Calendar
import kotlin.math.*
import org.json.JSONArray

/** Standard annotation dictionaries plus embedded appearances; source page contents stay native. */
object PdfNativeMarks {
    private val snapshot = COSName.getPDFName("PFMark")

    private fun owned(annotation: PDAnnotation) = annotation.cosObject.getString(snapshot) != null

    fun read(input: File): List<PdfMark> =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
            val seen = mutableSetOf<String>()
            pdf.pages.flatMapIndexed { index, page ->
                page.annotations.filter(::owned).mapNotNull { annotation ->
                    runCatching {
                            PdfMark.decode(
                                    JSONArray()
                                        .put(
                                            org.json.JSONObject(
                                                annotation.cosObject.getString(snapshot)
                                            )
                                        )
                                )
                                .single()
                                .let { mark ->
                                    mark.copy(
                                        page = index,
                                        id =
                                            if (seen.add(mark.id)) mark.id
                                            else java.util.UUID.randomUUID().toString(),
                                    )
                                }
                        }
                        .getOrNull()
                }
            }
        }

    /**
     * Preview excludes only annotations managed by this editor; the draft draws them separately.
     */
    fun preview(input: File, output: File): File {
        PdfTools.write(input, output) { pdf ->
            pdf.pages.forEach { page -> page.annotations = page.annotations.filterNot(::owned) }
        }
        return output
    }

    fun apply(context: Context, pdf: PDDocument, marks: List<PdfMark>, editable: Boolean) {
        pdf.pages.forEach { page -> page.annotations = page.annotations.filterNot(::owned) }
        val font =
            if (marks.any { it.kind in setOf("text", "note", "label") })
                PdfTextLayer.font(context, pdf)
            else null
        marks
            .groupBy { it.page }
            .forEach { (index, items) ->
                require(index in 0 until pdf.numberOfPages) { "批注页码已失效" }
                val page = pdf.getPage(index)
                val added =
                    items.filter { it.points.isNotEmpty() }.map { create(pdf, page, it, font) }
                if (editable) page.annotations.addAll(added)
                else
                    PDPageContentStream(
                            pdf,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true,
                        )
                        .use { content ->
                            added.forEach { annotation ->
                                content.saveGraphicsState()
                                val appearance =
                                    annotation.appearance.normalAppearance.appearanceStream
                                val box = appearance.bBox
                                val rect = annotation.rectangle
                                val sx = rect.width / box.width
                                val sy = rect.height / box.height
                                content.transform(
                                    Matrix(
                                        sx,
                                        0f,
                                        0f,
                                        sy,
                                        rect.lowerLeftX - box.lowerLeftX * sx,
                                        rect.lowerLeftY - box.lowerLeftY * sy,
                                    )
                                )
                                content.drawForm(appearance)
                                content.restoreGraphicsState()
                            }
                        }
            }
    }

    private fun create(
        pdf: PDDocument,
        page: PDPage,
        mark: PdfMark,
        font: PDType0Font?,
    ): PDAnnotationMarkup {
        val crop = page.cropBox
        fun point(p: PointN): PointF =
            PdfCoordinates.unrotate(p, page.rotation).let {
                PointF(
                    crop.lowerLeftX + it.x * crop.width,
                    crop.lowerLeftY + (1 - it.y) * crop.height,
                )
            }
        val points = mark.points.map(::point)
        val a = mark.points.first()
        val z = mark.points.last()
        val displayCorners =
            listOf(
                PointN(min(a.x, z.x), min(a.y, z.y)),
                PointN(max(a.x, z.x), min(a.y, z.y)),
                PointN(min(a.x, z.x), max(a.y, z.y)),
                PointN(max(a.x, z.x), max(a.y, z.y)),
            )
        val corners = displayCorners.map(::point)
        val stroke = (mark.width * min(crop.width, crop.height)).coerceAtLeast(.5f)
        val fontSize = max(10f, mark.width * crop.width * 5)
        val rgb =
            floatArrayOf(
                Color.red(mark.color) / 255f,
                Color.green(mark.color) / 255f,
                Color.blue(mark.color) / 255f,
            )
        val color = PDColor(rgb, PDDeviceRGB.INSTANCE)
        val annotation: PDAnnotationMarkup =
            when (mark.kind) {
                "highlight",
                "underline",
                "strikeout" ->
                    PDAnnotationTextMarkup(
                            when (mark.kind) {
                                "underline" -> "Underline"
                                "strikeout" -> "StrikeOut"
                                else -> "Highlight"
                            }
                        )
                        .apply {
                            quadPoints = corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
                            constantOpacity = if (mark.kind == "highlight") .3f else 1f
                        }
                "rect" -> PDAnnotationSquareCircle("Square")
                "note" -> PDAnnotationText().apply { name = PDAnnotationText.NAME_NOTE }
                "label" -> PDAnnotationRubberStamp().apply { name = "Draft" }
                "text" ->
                    PDAnnotationMarkup().apply { cosObject.setName(COSName.SUBTYPE, "FreeText") }
                else ->
                    PDAnnotationMarkup().apply {
                        cosObject.setName(COSName.SUBTYPE, "Ink")
                        inkList = arrayOf(points.flatMap { listOf(it.x, it.y) }.toFloatArray())
                    }
            }
        var bounds = points
        var labelWidth = 20f
        if (mark.kind in setOf("text", "label", "note")) {
            val text = if (mark.kind == "note") "N" else mark.text
            val w =
                if (mark.kind == "note") 20f
                else
                    max(
                        28f,
                        font!!.getStringWidth(PdfTextLayer.safe(font, text.replace('\n', ' '))) /
                            1000f * fontSize + 8,
                    )
            val h = if (mark.kind == "note") 20f else fontSize * 1.5f
            labelWidth = w
            val radians = Math.toRadians(page.rotation.toDouble())
            val origin = points.first()
            fun local(x: Float, y: Float) =
                PointF(
                    origin.x + (x * cos(radians) - y * sin(radians)).toFloat(),
                    origin.y + (x * sin(radians) + y * cos(radians)).toFloat(),
                )
            bounds = listOf(local(-3f, -3f), local(w, -3f), local(-3f, h), local(w, h))
        }
        val rectangle =
            PDRectangle(
                bounds.minOf { it.x } - stroke,
                bounds.minOf { it.y } - stroke,
                (bounds.maxOf { it.x } - bounds.minOf { it.x } + stroke * 2).coerceAtLeast(1f),
                (bounds.maxOf { it.y } - bounds.minOf { it.y } + stroke * 2).coerceAtLeast(1f),
            )
        annotation.rectangle = rectangle
        annotation.page = page
        annotation.annotationName = "PF-${mark.id}"
        annotation.titlePopup = "PaperForge"
        annotation.contents = mark.text
        annotation.creationDate = Calendar.getInstance()
        annotation.cosObject.setDate(COSName.M, Calendar.getInstance())
        annotation.isPrinted = true
        annotation.color = color
        annotation.borderStyle = PDBorderStyleDictionary().apply { width = stroke }
        annotation.cosObject.setString(snapshot, mark.json().toString())
        val appearance =
            PDAppearanceStream(pdf).apply {
                bBox = rectangle
                resources = PDResources()
            }
        PDPageContentStream(pdf, appearance).use { content ->
            content.setStrokingColor(color)
            content.setNonStrokingColor(color)
            content.setLineWidth(stroke)
            content.setLineCapStyle(1)
            content.setLineJoinStyle(1)
            when (mark.kind) {
                "highlight" -> {
                    content.setGraphicsStateParameters(
                        PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = .3f
                            cosObject.setName(COSName.getPDFName("BM"), "Multiply")
                        }
                    )
                    content.moveTo(corners[0].x, corners[0].y)
                    listOf(corners[1], corners[3], corners[2]).forEach {
                        content.lineTo(it.x, it.y)
                    }
                    content.closePath()
                    content.fill()
                }
                "underline",
                "strikeout" -> {
                    val fraction = if (mark.kind == "underline") .92f else .5f
                    fun middle(x: PointF, y: PointF) =
                        PointF(x.x + (y.x - x.x) * fraction, x.y + (y.y - x.y) * fraction)
                    val left = middle(corners[0], corners[2])
                    val right = middle(corners[1], corners[3])
                    content.moveTo(left.x, left.y)
                    content.lineTo(right.x, right.y)
                    content.stroke()
                }
                "rect" -> {
                    content.addRect(
                        points.minOf { it.x },
                        points.minOf { it.y },
                        abs(points.first().x - points.last().x),
                        abs(points.first().y - points.last().y),
                    )
                    content.stroke()
                }
                "text",
                "label",
                "note" -> {
                    val p = points.first()
                    content.transform(
                        Matrix.getRotateInstance(Math.toRadians(page.rotation.toDouble()), p.x, p.y)
                    )
                    if (mark.kind in setOf("label", "note")) {
                        content.addRect(
                            -2f,
                            -2f,
                            if (mark.kind == "note") 20f else labelWidth,
                            if (mark.kind == "note") 20f else fontSize * 1.4f,
                        )
                        content.stroke()
                    }
                    val selectedFont = font!!
                    val name = appearance.resources.add(selectedFont)
                    annotation.defaultAppearance =
                        "/${name.name} $fontSize Tf ${rgb.joinToString(" ")} rg"
                    annotation.cosObject.setItem(COSName.DR, appearance.resources)
                    content.beginText()
                    content.setFont(selectedFont, if (mark.kind == "note") 14f else fontSize)
                    content.showText(
                        PdfTextLayer.safe(
                            selectedFont,
                            if (mark.kind == "note") "N" else mark.text.replace('\n', ' '),
                        )
                    )
                    content.endText()
                }
                else -> {
                    content.moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { content.lineTo(it.x, it.y) }
                    if (points.size == 1) content.lineTo(points.first().x + .1f, points.first().y)
                    content.stroke()
                }
            }
        }
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        if (annotation is PDAnnotationText) {
            annotation.constructAppearances(pdf)
            // The bundled note handler replaces the BBox with local icon coordinates but retains
            // the translation from the previous global BBox. Reset it for the newly local stream.
            annotation.appearance.normalAppearance.appearanceStream.cosObject.removeItem(
                COSName.MATRIX
            )
        }
        return annotation
    }
}
