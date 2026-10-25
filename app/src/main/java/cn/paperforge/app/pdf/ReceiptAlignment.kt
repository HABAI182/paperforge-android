package cn.paperforge.app.pdf

import android.graphics.Bitmap
import java.io.File
import kotlin.math.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Translation after upright width normalization. Ambiguous/repeated or blank matches stay
 * sequential.
 */
object ReceiptAlignment {
    fun match(previous: File, current: File): StitchSeam {
        OrientedImageRegion(previous, 256).use { a ->
            OrientedImageRegion(current, 256).use { b ->
                val bandA = a.band((a.height - 1024).coerceAtLeast(0), minOf(1024, a.height))
                val bandB = b.band(0, minOf(768, b.height))
                try {
                    return align(bandA, bandB, b.height)
                } finally {
                    bandA.recycle()
                    bandB.recycle()
                }
            }
        }
    }

    private fun align(a: Bitmap, b: Bitmap, currentHeight: Int): StitchSeam {
        val source = Mat()
        val target = Mat()
        val ga = Mat()
        val gb = Mat()
        val ea = Mat()
        val eb = Mat()
        val result = Mat()
        val all = mutableListOf(source, target, ga, gb, ea, eb, result)
        try {
            Utils.bitmapToMat(a, source)
            Utils.bitmapToMat(b, target)
            Imgproc.cvtColor(source, ga, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(target, gb, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(ga, ea, 40.0, 120.0)
            Imgproc.Canny(gb, eb, 40.0, 120.0)
            val margin = 48
            val patchHeight = minOf(112, b.height / 3, a.height / 2)
            if (patchHeight < 24) return StitchSeam(mode = "UNRESOLVED")
            var patchTop = -1
            for (y in 0..minOf(384, b.height - patchHeight) step 16) {
                val section = eb.submat(Rect(margin, y, b.width - margin * 2, patchHeight))
                val density = Core.countNonZero(section).toDouble() / section.total()
                section.release()
                if (density >= .015 && density < .5) {
                    patchTop = y
                    break
                }
            }
            if (patchTop < 0) return StitchSeam(mode = "UNRESOLVED")
            val patch = eb.submat(Rect(margin, patchTop, b.width - margin * 2, patchHeight))
            all += patch
            Imgproc.matchTemplate(ea, patch, result, Imgproc.TM_CCOEFF_NORMED)
            val peak = Core.minMaxLoc(result)
            val exclusion =
                result.submat(
                    Rect(
                        maxOf(0, peak.maxLoc.x.toInt() - 16),
                        maxOf(0, peak.maxLoc.y.toInt() - patchHeight / 2),
                        minOf(result.cols(), peak.maxLoc.x.toInt() + 17) -
                            maxOf(0, peak.maxLoc.x.toInt() - 16),
                        minOf(result.rows(), peak.maxLoc.y.toInt() + patchHeight / 2 + 1) -
                            maxOf(0, peak.maxLoc.y.toInt() - patchHeight / 2),
                    )
                )
            exclusion.setTo(Scalar(-1.0))
            exclusion.release()
            val second = Core.minMaxLoc(result).maxVal
            val separation = (peak.maxVal - second).toFloat()
            val overlap = a.height + patchTop - peak.maxLoc.y.toFloat()
            val ratio = overlap / currentHeight
            val shift = (peak.maxLoc.x.toFloat() - margin) / b.width
            val valid =
                peak.maxVal.isFinite() &&
                    peak.maxVal >= .76 &&
                    separation >= .035 &&
                    ratio in 0.01f..0.95f &&
                    abs(shift) <= .2f
            return StitchSeam(
                if (valid) ratio else 0f,
                if (valid) shift else 0f,
                if (valid) "AUTO" else "UNRESOLVED",
                peak.maxVal.toFloat(),
                separation,
            )
        } finally {
            all.forEach { it.release() }
        }
    }
}
