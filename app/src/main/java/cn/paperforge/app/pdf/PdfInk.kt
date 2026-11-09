package cn.paperforge.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Pen marks saved as PDF ink annotations, drawn over the reader's pages. Android's page renderer draws no
 * annotations at all: an annotated copy opened in the reader looked exactly like the document it came from.
 */
object PdfInk {
    class Stroke(val points: FloatArray, val color: Int, val width: Float)

    /** Page box (left, top) in PDF units and the strokes on it, for each page that has ink. */
    class PageInk(val left: Float, val top: Float, val strokes: List<Stroke>)

    fun read(file: File): Map<Int, PageInk> =
        runCatching {
                PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                    val result = HashMap<Int, PageInk>()
                    pdf.pages.forEachIndexed { index, page ->
                        // A turned page would need its own mapping; such pages keep the renderer's view.
                        if (page.rotation % 360 != 0) return@forEachIndexed
                        val strokes = ArrayList<Stroke>()
                        for (annotation in page.annotations) {
                            val cos = annotation.cosObject
                            if (cos.getNameAsString(COSName.SUBTYPE) != "Ink") continue
                            if (cos.getInt(COSName.F, 0) and 2 != 0) continue
                            val list = cos.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray ?: continue
                            val color = color(cos)
                            val width = width(cos)
                            for (k in 0 until list.size()) {
                                val path = list.getObject(k) as? COSArray ?: continue
                                val points = FloatArray(path.size()) { (path.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
                                if (points.size >= 4) strokes += Stroke(points, color, width)
                            }
                        }
                        if (strokes.isNotEmpty()) {
                            val box = page.cropBox
                            result[index] = PageInk(box.lowerLeftX, box.upperRightY, strokes)
                        }
                    }
                    result
                }
            }
            .getOrDefault(emptyMap())

    private fun color(cos: COSDictionary): Int {
        val c = cos.getDictionaryObject(COSName.C) as? COSArray
        val v = FloatArray(c?.size() ?: 0) { ((c!!.getObject(it) as? COSNumber)?.floatValue() ?: 0f).coerceIn(0f, 1f) }
        val alpha = (cos.getFloat(COSName.CA, 1f).coerceIn(0f, 1f) * 255).toInt()
        fun of(r: Float, g: Float, b: Float) = Color.argb(alpha, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        return when (v.size) {
            1 -> of(v[0], v[0], v[0])
            3 -> of(v[0], v[1], v[2])
            4 -> of((1 - v[0]) * (1 - v[3]), (1 - v[1]) * (1 - v[3]), (1 - v[2]) * (1 - v[3]))
            else -> of(1f, 0f, 0f)
        }
    }

    private fun width(cos: COSDictionary): Float {
        val border = cos.getDictionaryObject(COSName.BS) as? COSDictionary
        return border?.getFloat(COSName.W, 1f) ?: 1f
    }

    /** Draws [ink] on [bitmap], which shows the page at [scale] pixels per PDF unit, shifted by ([left], [top]) pixels. */
    fun draw(bitmap: Bitmap, ink: PageInk, scale: Float, left: Float = 0f, top: Float = 0f) {
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        for (stroke in ink.strokes) {
            paint.color = stroke.color
            paint.strokeWidth = (stroke.width * scale).coerceAtLeast(1f)
            val path = Path()
            val p = stroke.points
            for (i in 0 until p.size / 2) {
                val x = (p[2 * i] - ink.left) * scale - left
                val y = (ink.top - p[2 * i + 1]) * scale - top
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
