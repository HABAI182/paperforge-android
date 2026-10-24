package cn.paperforge.app.pdf

import android.graphics.Path
import android.graphics.PointF
import cn.paperforge.app.model.PointN
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.File

/** Image placements in displayed-page coordinates; pixels remain in the native renderer. */
object ReaderImages {
    fun regions(file: File, index: Int): List<List<PointN>> =
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(index)
            val crop = page.cropBox
            val result = mutableListOf<List<PointN>>()
            val forms = mutableSetOf<COSStream>()
            var point = PointF()
            val engine =
                object : PDFGraphicsStreamEngine(page) {
                    override fun drawImage(image: PDImage) {
                        val matrix = graphicsState.currentTransformationMatrix
                        val corners =
                            listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (x, y) ->
                                val p = matrix.transformPoint(x, y)
                                val n =
                                    PointN(
                                        (p.x - crop.lowerLeftX) / crop.width,
                                        1 - (p.y - crop.lowerLeftY) / crop.height,
                                    )
                                when ((page.rotation % 360 + 360) % 360) {
                                    90 -> PointN(1 - n.y, n.x)
                                    180 -> PointN(1 - n.x, 1 - n.y)
                                    270 -> PointN(n.y, 1 - n.x)
                                    else -> n
                                }
                            }
                        result.add(corners)
                    }

                    override fun showForm(form: PDFormXObject) {
                        if (forms.size >= 32 || !forms.add(form.cosObject)) return
                        try {
                            super.showForm(form)
                        } finally {
                            forms.remove(form.cosObject)
                        }
                    }

                    override fun appendRectangle(a: PointF, b: PointF, c: PointF, d: PointF) {
                        point = a
                    }

                    override fun clip(rule: Path.FillType) {}

                    override fun moveTo(x: Float, y: Float) {
                        point = PointF(x, y)
                    }

                    override fun lineTo(x: Float, y: Float) {
                        point = PointF(x, y)
                    }

                    override fun curveTo(
                        x1: Float,
                        y1: Float,
                        x2: Float,
                        y2: Float,
                        x3: Float,
                        y3: Float,
                    ) {
                        point = PointF(x3, y3)
                    }

                    override fun getCurrentPoint() = point

                    override fun closePath() {}

                    override fun endPath() {}

                    override fun strokePath() {}

                    override fun fillPath(rule: Path.FillType) {}

                    override fun fillAndStrokePath(rule: Path.FillType) {}

                    override fun shadingFill(name: COSName) {}
                }
            engine.processPage(page)
            result
        }
}
