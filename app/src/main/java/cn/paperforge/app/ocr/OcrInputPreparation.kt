package cn.paperforge.app.ocr

import android.graphics.Bitmap
import cn.paperforge.app.imaging.EnhancePipeline
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Illumination-only OCR view. No binarization, ink deletion, sharpening or glyph synthesis. */
internal object OcrInputPreparation {
    const val VERSION = "ocr-paper-field-3"

    fun paper(source: Bitmap): Bitmap {
        val rgba = Mat()
        val gray = Mat()
        val field = Mat()
        val corrected = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val window = (minOf(source.width, source.height) * .035f).toInt().coerceIn(21, 151) or 1
            EnhancePipeline.paperField(gray, field, window)
            Core.max(field, Scalar.all(32.0), field)
            Core.divide(gray, field, corrected, 255.0)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(corrected, it)
            }
        } finally {
            listOf(rgba, gray, field, corrected).forEach(Mat::release)
        }
    }
}
